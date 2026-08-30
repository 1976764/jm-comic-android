package com.carya.jm.ui.reader

import android.app.Application
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.cache.CacheManager
import com.carya.jm.data.download.DownloadManager
import com.carya.jm.data.model.Episode
import com.carya.jm.data.model.PhotoInfo
import com.carya.jm.data.model.parseComicDetail
import com.carya.jm.data.model.parseDownloadedImage
import com.carya.jm.data.model.parsePhotoInfo
import com.carya.jm.data.python.PythonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fallback aspect ratio (width / height) for typical manga pages. */
private const val DEFAULT_ASPECT_RATIO = 2f / 3f

/** Number of images to download per batch (matches Python ThreadPoolExecutor max_workers). */
private const val BATCH_SIZE = 3

/** How far ahead of current position to prefetch. */
private const val PREFETCH_AHEAD = 8

/** How far behind current position to backfill. */
private const val PREFETCH_BEHIND = 2

/** 解扰并发数：全分辨率图片内存占用大，2 个 worker 平衡速度与内存。 */
private const val DESCRAMBLE_WORKERS = 2

/** 阅读第 N 章时，预加载第 N+1 章的前几张图片数量。 */
private const val PRELOAD_NEXT_CHAPTER_IMAGES = 6

/** State of a single page image in the reader. */
sealed class ReaderImageState {
    /** [aspectRatio] = width / height, used to reserve correct space. */
    data class Loading(val index: Int, val aspectRatio: Float) : ReaderImageState()
    data class Ready(val index: Int, val path: String, val aspectRatio: Float) : ReaderImageState()
    data class Error(val index: Int, val message: String) : ReaderImageState()
}

data class ReaderUiState(
    val isLoading: Boolean = false,
    val images: List<ReaderImageState> = emptyList(),
    val title: String = "",
    val currentEpisodeId: String = "",
    val episodes: List<Episode> = emptyList(),
    val error: String? = null,
)

/**
 * Reader view model with batch concurrent image loading.
 *
 * **Architecture:**
 * 1. [getPhotoInfo] fetches metadata instantly (no downloads).
 * 2. A background coroutine downloads images in **batches of 8** using
 *    Python's `download_images_batch` (ThreadPoolExecutor, 8 concurrent
 *    network requests).
 * 3. Download order is **prioritized by scroll position**: images near
 *    the user's current visible index are downloaded first, then images
 *    ahead are prefetched so they're ready before the user scrolls to them.
 * 4. After each batch download completes, images are descrambled
 *    sequentially (local CPU, fast) and state is updated immediately.
 *
 * This gives near-instant first-page display and smooth continuous
 * scrolling without waiting for the entire chapter to download.
 */
class ReaderViewModel(
    application: Application,
    private val albumId: String,
    private val photoId: String,
    episodes: List<Episode>? = null,
) : AndroidViewModel(application) {

    private val python = PythonService()
    private val cacheDir = application.cacheDir.absolutePath

    /** 串行化对 _state 的多线程更新（并行解扰时多个 worker 同时写）。 */
    private val stateLock = Any()

    /** 已触发过预加载的章节 id（避免重复预加载）。 */
    private val preloadedChapters = mutableSetOf<String>()

    /** Default aspect ratio for the current chapter (from first image). */
    private var defaultAspectRatio: Float = DEFAULT_ASPECT_RATIO

    /** Current visible image index, used to prioritize downloads. */
    private var currentVisibleIndex: Int = 0

    /** List of pending image indices that still need downloading. */
    private var pendingIndices: List<Int> = emptyList()

    private val _state = MutableStateFlow(
        ReaderUiState(
            isLoading = false,
            currentEpisodeId = photoId,
            episodes = episodes ?: emptyList(),
        )
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        loadChapter(photoId)
        if (episodes.isNullOrEmpty()) {
            loadEpisodes()
        }
    }

    /** Update the current visible index for download prioritization. */
    fun updateVisibleIndex(index: Int) {
        val clamped = index.coerceAtLeast(0)
        if (clamped != currentVisibleIndex) {
            currentVisibleIndex = clamped
            // Re-prioritize remaining downloads if there's a significant jump.
            reprioritizeIfNeeded()
        }
    }

    /** Load (or switch to) a chapter by photo id. */
    fun loadChapter(newPhotoId: String) {
        downloadJob?.cancel()
        currentVisibleIndex = 0

        // 1. 优先检查离线下载 — 命中则完全离线阅读，无需网络
        if (tryLoadOffline(newPhotoId)) return

        // 2. 检查本地缓存 — 命中则直接展示，后台刷新
        val cachedJson = CacheManager.get().getCachedPhotoInfo(newPhotoId)
        if (cachedJson != null) {
            try {
                val cachedInfo = parsePhotoInfo(JSONObject(cachedJson))
                if (cachedInfo != null && cachedInfo.images.isNotEmpty()) {
                    applyPhotoInfo(newPhotoId, cachedInfo, fromCache = true)
                    refreshPhotoInfo(newPhotoId)
                    return
                }
            } catch (_: Exception) { /* 缓存损坏，走正常流程 */ }
        }

        // 3. 无缓存，显示 loading 并从网络获取
        _state.value = _state.value.copy(
            isLoading = true,
            error = null,
            currentEpisodeId = newPhotoId,
            images = emptyList(),
        )

        fetchPhotoInfo(newPhotoId)
    }

    /**
     * Try to load chapter images from the offline (downloaded) directory.
     * Returns true if images were found and loaded, false otherwise.
     *
     * Enables fully offline reading for downloaded comics — no network needed.
     */
    private fun tryLoadOffline(newPhotoId: String): Boolean {
        if (!DownloadManager.get().isDownloaded(albumId)) return false

        val offlineChapterDir = File(
            getApplication<Application>().filesDir,
            "offline_comics/$albumId/$newPhotoId",
        )
        if (!offlineChapterDir.exists()) return false

        val imageFiles = offlineChapterDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("webp", "jpg", "png", "jpeg") }
            ?.sortedBy { it.name }
            ?: return false

        if (imageFiles.isEmpty()) return false

        val imageStates = imageFiles.mapIndexed { index, file ->
            val ratio = readImageAspectRatio(file.absolutePath) ?: DEFAULT_ASPECT_RATIO
            ReaderImageState.Ready(index, file.absolutePath, ratio)
        }

        val episodeTitle = _state.value.episodes.find { it.id == newPhotoId }?.title ?: ""

        _state.value = _state.value.copy(
            isLoading = false,
            error = null,
            images = imageStates,
            title = episodeTitle,
            currentEpisodeId = newPhotoId,
        )

        pendingIndices = emptyList()
        return true
    }

    /** 从网络获取 photo info 并展示。 */
    private fun fetchPhotoInfo(newPhotoId: String) {
        viewModelScope.launch {
            try {
                val infoJson = withContext(Dispatchers.IO) {
                    python.getPhotoInfo(newPhotoId, cacheDir)
                }
                val info = parsePhotoInfo(infoJson)
                if (info == null) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = infoJson.optString("error", "加载失败"),
                    )
                    return@launch
                }

                // 缓存 photo info 供下次使用
                CacheManager.get().cachePhotoInfo(newPhotoId, infoJson.toString())

                applyPhotoInfo(newPhotoId, info, fromCache = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "网络错误",
                )
            }
        }
    }

    /** 后台刷新 photo info，仅更新元数据，不重新触发 loading。 */
    private fun refreshPhotoInfo(newPhotoId: String) {
        viewModelScope.launch {
            try {
                val infoJson = withContext(Dispatchers.IO) {
                    python.getPhotoInfo(newPhotoId, cacheDir)
                }
                // 确保用户没有已切换到其他章节
                if (_state.value.currentEpisodeId != newPhotoId) return@launch

                val info = parsePhotoInfo(infoJson)
                if (info != null) {
                    CacheManager.get().cachePhotoInfo(newPhotoId, infoJson.toString())
                    applyPhotoInfo(newPhotoId, info, fromCache = false)
                }
            } catch (_: Exception) { /* 后台刷新失败，保持缓存数据 */ }
        }
    }

    /** 将 PhotoInfo 应用到 UI 状态，并启动批量图片下载。 */
    private fun applyPhotoInfo(newPhotoId: String, info: PhotoInfo, fromCache: Boolean) {
        defaultAspectRatio = if (info.defaultWidth > 0 && info.defaultHeight > 0) {
            info.defaultWidth.toFloat() / info.defaultHeight.toFloat()
        } else {
            DEFAULT_ASPECT_RATIO
        }

        val decodedDir = File(cacheDir, "photos_decoded/$newPhotoId")
        val isDownloaded = DownloadManager.get().isDownloaded(albumId)
        val offlineDir = if (isDownloaded) {
            File(getApplication<Application>().filesDir, "offline_comics/$albumId/$newPhotoId")
        } else {
            null
        }

        val imageStates = info.images.map { img ->
            val decodedPath = File(decodedDir, img.filename).absolutePath
            val decodedFile = File(decodedPath)
            if (decodedFile.exists()) {
                val ratio = readImageAspectRatio(decodedPath) ?: defaultAspectRatio
                ReaderImageState.Ready(img.index, decodedPath, ratio)
            } else {
                val offlineFile = offlineDir?.let { File(it, img.filename) }
                if (offlineFile != null && offlineFile.exists()) {
                    val ratio = readImageAspectRatio(offlineFile.absolutePath) ?: defaultAspectRatio
                    ReaderImageState.Ready(img.index, offlineFile.absolutePath, ratio)
                } else {
                    ReaderImageState.Loading(img.index, defaultAspectRatio)
                }
            }
        }

        _state.value = _state.value.copy(
            isLoading = false,
            error = null,
            images = imageStates,
            title = info.title,
            currentEpisodeId = newPhotoId,
        )

        // Build the pending download list — skip images in cache or offline
        pendingIndices = info.images.filter { img ->
            val decodedExists = File(decodedDir, img.filename).exists()
            val offlineExists = offlineDir?.let { File(it, img.filename).exists() } ?: false
            !decodedExists && !offlineExists
        }.map { it.index }

        if (pendingIndices.isNotEmpty()) {
            startBatchDownloads(newPhotoId, info.images)
        }

        // 循环预加载：当前章节已就绪，后台预加载下一章
        preloadNextChapter(newPhotoId)
    }

    /**
     * 循环预加载：每打开/看完第 N 章，就预加载第 N+1 章的章节信息
     * 和它的前几张图片（详情页已负责第 1 章，这里延续后续链条）。
     * 已下载的漫画走离线阅读，跳过；preloadedChapters 去重防重复。
     */
    private fun preloadNextChapter(currentPhotoId: String) {
        val episodes = _state.value.episodes
        if (episodes.size < 2) return
        val idx = episodes.indexOfFirst { it.id == currentPhotoId }
        if (idx < 0) return
        val next = episodes.getOrNull(idx + 1) ?: return
        // 已下载的漫画直接离线阅读，无需预加载
        if (DownloadManager.get().isDownloaded(albumId)) return
        if (!preloadedChapters.add(next.id)) return

        viewModelScope.launch {
            try {
                // 1. 下一章章节信息（Python 内存缓存 + 磁盘缓存）
                val infoJson = withContext(Dispatchers.IO) {
                    python.getPhotoInfo(next.id, cacheDir)
                }
                val info = parsePhotoInfo(infoJson)
                if (info != null && info.images.isNotEmpty()) {
                    CacheManager.get().cachePhotoInfo(next.id, infoJson.toString())
                    // 2. 只预下载前几张，避免抢当前章节带宽
                    val firstN = info.images.take(PRELOAD_NEXT_CHAPTER_IMAGES).map { it.index }
                    withContext(Dispatchers.IO) {
                        python.downloadImagesBatch(next.id, firstN, cacheDir)
                    }
                }
            } catch (_: Exception) {
                // 预加载失败不影响阅读
            }
        }
    }

    /**
     * Download images in priority-ordered batches.
     *
     * Priority: images closest to [currentVisibleIndex] are downloaded
     * first (with a forward bias since the user reads downward), then
     * remaining images are downloaded in order.
     */
    private fun startBatchDownloads(
        photoId: String,
        images: List<com.carya.jm.data.model.PhotoImageInfo>,
    ) {
        downloadJob = viewModelScope.launch {
            while (pendingIndices.isNotEmpty()) {
                // Chapter switch guard
                if (_state.value.currentEpisodeId != photoId) return@launch

                // Build priority-ordered batch from pending indices.
                val batch = buildPriorityBatch(pendingIndices)

                // 1. Batch download raw images (Python, 8 concurrent network requests)
                val batchResults = withContext(Dispatchers.IO) {
                    python.downloadImagesBatch(photoId, batch, cacheDir)
                }

                // Chapter switch guard after network round-trip
                if (_state.value.currentEpisodeId != photoId) return@launch

                // 2. 并行解扰本批图片，每张完成立即显示
                val resultsArray = batchResults.optJSONArray("images")
                if (resultsArray != null) {
                    descrambleBatch(photoId, images, resultsArray)
                }

                // Remove completed indices from pending list
                val completedIndices = batch.toSet()
                pendingIndices = pendingIndices.filter { it !in completedIndices }
            }
        }
    }

    /**
     * Build a batch of [BATCH_SIZE] indices from [pending], ordered by
     * proximity to [currentVisibleIndex] with a forward bias.
     *
     * Priority order:
     * 1. Current position ± PREFETCH_BEHIND
     * 2. Next PREFETCH_AHEAD images after current
     * 3. Remaining in order
     */
    private fun buildPriorityBatch(pending: List<Int>): List<Int> {
        if (pending.isEmpty()) return emptyList()

        val current = currentVisibleIndex
        val result = mutableListOf<Int>()
        val remaining = pending.toMutableList()

        // 1. Backfill: a few images behind current position (if pending)
        for (i in maxOf(0, current - PREFETCH_BEHIND) until current) {
            if (i in remaining && result.size < BATCH_SIZE) {
                result.add(i)
                remaining.remove(i)
            }
        }

        // 2. Prefetch ahead: images from current to current + PREFETCH_AHEAD
        for (i in current..(current + PREFETCH_AHEAD)) {
            if (i in remaining && result.size < BATCH_SIZE) {
                result.add(i)
                remaining.remove(i)
            }
        }

        // 3. Fill remaining slots with next available indices
        while (result.size < BATCH_SIZE && remaining.isNotEmpty()) {
            result.add(remaining.first())
            remaining.removeAt(0)
        }

        return result
    }

    /**
     * Re-prioritize downloads if the user has scrolled significantly.
     * Cancels the current download job and restarts with new priority.
     */
    private fun reprioritizeIfNeeded() {
        // Only reprioritize if there are still pending downloads
        if (pendingIndices.isEmpty()) return

        // Restart the download job with updated priority
        downloadJob?.cancel()

        // Re-launch with new priority order
        downloadJob = viewModelScope.launch {
            val photoId = _state.value.currentEpisodeId

            // Rebuild pending list from current state (some may have completed)
            pendingIndices = _state.value.images
                .filterIsInstance<ReaderImageState.Loading>()
                .map { it.index }

            while (pendingIndices.isNotEmpty()) {
                if (_state.value.currentEpisodeId != photoId) return@launch

                val batch = buildPriorityBatch(pendingIndices)

                val batchResults = withContext(Dispatchers.IO) {
                    python.downloadImagesBatch(photoId, batch, cacheDir)
                }

                if (_state.value.currentEpisodeId != photoId) return@launch

                val resultsArray = batchResults.optJSONArray("images")
                if (resultsArray != null) {
                    // Re-fetch images list from info for filename lookup
                    val imagesList = getPhotoImagesList(photoId)
                    descrambleBatch(photoId, imagesList, resultsArray)
                }

                val completedIndices = batch.toSet()
                pendingIndices = pendingIndices.filter { it !in completedIndices }
            }
        }
    }

    /** Get the PhotoImageInfo list from cached photo info, or empty if unavailable. */
    private fun getPhotoImagesList(photoId: String): List<com.carya.jm.data.model.PhotoImageInfo> {
        val cachedJson = CacheManager.get().getCachedPhotoInfo(photoId) ?: return emptyList()
        return try {
            parsePhotoInfo(JSONObject(cachedJson))?.images ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 并行解扰一批已下载的图片（本地 CPU 操作），**每张完成立即更新 UI**
     * （不再等整批处理完才一起显示）。用 [DESCRAMBLE_WORKERS] 个 worker：
     * 全分辨率图片内存占用大，2 个并发在速度与峰值内存之间取得平衡。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun descrambleBatch(
        photoId: String,
        imagesList: List<com.carya.jm.data.model.PhotoImageInfo>,
        resultsArray: JSONArray,
    ) {
        val decodedDir = File(cacheDir, "photos_decoded/$photoId")
        val pool = Dispatchers.IO.limitedParallelism(DESCRAMBLE_WORKERS)
        withContext(pool) {
            coroutineScope {
                (0 until resultsArray.length()).map { i ->
                    launch {
                        coroutineContext.ensureActive()
                        val resultJson = resultsArray.getJSONObject(i)
                        val downloaded = parseDownloadedImage(resultJson)
                        if (downloaded == null) {
                            val index = resultJson.optInt("index", -1)
                            if (index >= 0) {
                                updateImageState(
                                    index,
                                    ReaderImageState.Error(
                                        index,
                                        resultJson.optString("error", "下载失败"),
                                    ),
                                )
                            }
                            return@launch
                        }

                        val rawPath = downloaded.path
                        val decodedPath = File(decodedDir, downloaded.filename).absolutePath
                        val imgInfo = imagesList.find { it.index == downloaded.index }
                        val num = imgInfo?.scrambleNum ?: downloaded.scrambleNum

                        // Descramble + read actual dimensions in one IO block,
                        // then publish immediately — 该图片一完成就显示。
                        val success = ImageDescrambler.descramble(rawPath, decodedPath, num)
                        val path = if (success) decodedPath else rawPath
                        val r = readImageAspectRatio(path) ?: defaultAspectRatio
                        updateImageState(
                            downloaded.index,
                            ReaderImageState.Ready(downloaded.index, path, r),
                        )
                    }
                }
            }
        }
    }

    /** Update a single image state in the list (synchronized for parallel writers). */
    private fun updateImageState(index: Int, newState: ReaderImageState) {
        synchronized(stateLock) {
            val current = _state.value.images.toMutableList()
            if (index in current.indices) {
                current[index] = newState
                _state.value = _state.value.copy(images = current)
            }
        }
    }

    /** Read the actual width/height ratio of an image file without loading the full bitmap. */
    private fun readImageAspectRatio(path: String): Float? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth.toFloat() / options.outHeight.toFloat()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Fetch album detail so the chapter selector has the full episode list. */
    private fun loadEpisodes() {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.getAlbumDetail(albumId)
                }
                val detail = parseComicDetail(result)
                if (detail != null) {
                    _state.value = _state.value.copy(episodes = detail.episodes)
                }
            } catch (_: Exception) {
                // Episodes are optional; the reader still works without them.
            }
        }
    }

    /** Friendly title for the chapter currently being shown. */
    fun currentEpisodeTitle(): String {
        val ep = _state.value.episodes.find { it.id == _state.value.currentEpisodeId }
        return when {
            ep != null && ep.title.isNotBlank() -> ep.title
            _state.value.title.isNotBlank() -> _state.value.title
            else -> "阅读"
        }
    }

    companion object {
        fun factory(
            application: Application,
            albumId: String,
            photoId: String,
            episodes: List<Episode>? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReaderViewModel(application, albumId, photoId, episodes) as T
            }
        }
    }
}
