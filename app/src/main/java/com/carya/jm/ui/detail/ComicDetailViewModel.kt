package com.carya.jm.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.cache.CacheManager
import com.carya.jm.data.download.DownloadManager
import com.carya.jm.data.download.DownloadProgress
import com.carya.jm.data.download.DownloadService
import com.carya.jm.data.model.ComicDetail
import com.carya.jm.data.model.ComicItem
import com.carya.jm.data.model.Episode
import com.carya.jm.data.model.parseComicDetail
import com.carya.jm.data.model.parsePhotoInfo
import com.carya.jm.data.python.PythonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ComicDetailUiState(
    val detail: ComicDetail? = null,
    val isFetchingDetail: Boolean = false,
    val error: String? = null,
)

class ComicDetailViewModel(
    application: Application,
    private val albumId: String,
    private val comicItem: ComicItem?,
) : AndroidViewModel(application) {

    private val python = PythonService()
    private val cacheDir = application.cacheDir.absolutePath

    /** 当前漫画是否已收藏（来源：收藏缓存，收藏/取消后后台刷新校正）。 */
    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()

    /** 收藏切换任务（防重复点击）。 */
    private var favoriteJob: Job? = null

    /** 详情页预加载任务：进入详情页就开始拉章节信息 + 预下载第一章原图。 */
    private var preloadJob: Job? = null

    private val _state = MutableStateFlow(ComicDetailUiState())
    val state: StateFlow<ComicDetailUiState> = _state.asStateFlow()

    /** Filter the global download progress to only show this album's status. */
    val downloadProgress: StateFlow<DownloadProgress> = DownloadService.activeDownloads
        .map { map -> map[albumId] ?: DownloadProgress() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadProgress())

    init {
        if (comicItem != null) {
            _state.value = ComicDetailUiState(
                detail = ComicDetail(
                    id = comicItem.id,
                    title = comicItem.title,
                    author = comicItem.author,
                    description = "",
                    tags = emptyList(),
                    pageCount = 0,
                    pubDate = "",
                    updateDate = "",
                    coverUrl = comicItem.coverUrl,
                    episodes = emptyList(),
                ),
            )
        }

        val cached = CacheManager.get().getCachedAlbumDetail(albumId)
        if (cached != null) {
            _state.value = _state.value.copy(detail = mergeDetail(_state.value.detail, cached))
        }

        // 收藏状态初始化：只看缓存，不做额外请求
        _isFavorited.value = CacheManager.get().getCachedFavorites()?.any { it.id == albumId } ?: false

        val current = _state.value.detail
        val needsFetch = current == null ||
            current.episodes.isEmpty() ||
            current.description.isBlank() ||
            current.tags.isEmpty()

        if (needsFetch) {
            fetchMissingDetail()
        } else {
            maybeStartPreload()
        }
    }

    fun fetchMissingDetail() {
        _state.value = _state.value.copy(isFetchingDetail = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.getAlbumDetail(albumId)
                }
                if (result.optBoolean("ok", false)) {
                    val fetched = parseComicDetail(result)
                    if (fetched != null) {
                        CacheManager.get().cacheAlbumDetail(fetched)
                        _state.value = ComicDetailUiState(
                            detail = mergeDetail(_state.value.detail, fetched),
                            isFetchingDetail = false,
                        )
                        maybeStartPreload()
                    } else {
                        onFetchFailed("解析详情数据失败")
                    }
                } else {
                    val msg = result.optString("message", "")
                        .ifBlank { result.optString("error", "加载失败") }
                    onFetchFailed(msg)
                }
            } catch (e: Exception) {
                onFetchFailed(e.message ?: "网络错误")
            }
        }
    }

    /**
     * 详情获取失败时的统一处理：记录错误，并添加兜底章节让用户可以阅读。
     * 单章节漫画的 photo_id == album_id，用 albumId 构造一个兜底 Episode。
     */
    private fun onFetchFailed(errorMsg: String) {
        val current = _state.value.detail
        val withFallback = if (current != null && current.episodes.isEmpty()) {
            current.copy(episodes = listOf(Episode(albumId, "1", "第1章")))
        } else {
            current
        }
        _state.value = _state.value.copy(
            isFetchingDetail = false,
            error = errorMsg,
            detail = withFallback,
        )
    }

    fun loadDetail() = fetchMissingDetail()

    /**
     * 详情页预加载：进入详情页就开始加载本子图片，让阅读器"秒开"。
     * 1. 拉取第一章的章节信息（暖 Python 内存缓存 + 写磁盘缓存，含首图尺寸）
     * 2. 预下载第一章全部原图到 photos_raw（阅读器打开后只需本地解扰）
     * 3. 预拉第二章章节信息（切章秒开）
     * 离开详情页（onCleared）自动取消。
     */
    private fun maybeStartPreload() {
        if (preloadJob?.isActive == true) return
        val detail = _state.value.detail ?: return
        if (detail.episodes.isEmpty()) return
        // 已下载的漫画直接离线阅读，无需预加载
        if (DownloadManager.get().isDownloaded(detail.id)) return
        // 本专辑正在后台下载，避免预加载抢占带宽
        if (DownloadService.activeDownloads.value[detail.id]?.isDownloading == true) return

        val first = detail.episodes.firstOrNull() ?: return
        val second = detail.episodes.getOrNull(1)
        preloadJob = viewModelScope.launch {
            // 1 + 2：第一章信息 + 原图预下载
            try {
                val infoJson = withContext(Dispatchers.IO) {
                    python.getPhotoInfo(first.id, cacheDir)
                }
                val info = parsePhotoInfo(infoJson)
                if (info != null && info.images.isNotEmpty()) {
                    CacheManager.get().cachePhotoInfo(first.id, infoJson.toString())
                    withContext(Dispatchers.IO) {
                        python.downloadImagesBatch(
                            first.id,
                            info.images.map { it.index },
                            cacheDir,
                        )
                    }
                }
            } catch (_: Exception) {
                // 预加载失败不影响正常使用，阅读器打开时会重新拉取
            }

            // 3：第二章信息预加载
            if (second != null) {
                try {
                    val infoJson = withContext(Dispatchers.IO) {
                        python.getPhotoInfo(second.id, cacheDir)
                    }
                    if (parsePhotoInfo(infoJson) != null) {
                        CacheManager.get().cachePhotoInfo(second.id, infoJson.toString())
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onCleared() {
        preloadJob?.cancel()
        super.onCleared()
    }

    fun isDownloaded(): Boolean = DownloadManager.get().isDownloaded(albumId)

    fun downloadAlbum() {
        val detail = _state.value.detail ?: return
        if (detail.episodes.isEmpty()) return
        // 多本并发下载：只禁止同一本的重复下载，其它漫画可同时下载
        if (DownloadService.activeDownloads.value[detail.id]?.isDownloading == true) return

        DownloadService.startDownload(
            context = getApplication(),
            albumId = detail.id,
            title = detail.title,
            author = detail.author,
            coverUrl = detail.coverUrl,
            episodes = detail.episodes,
        )
    }

    fun cancelDownload() {
        DownloadService.cancelDownload(getApplication(), albumId)
    }

    /**
     * 收藏 / 取消收藏（详情页心形按钮）。
     * 乐观更新 UI → Python 切换 → 失败回滚；鉴权失败时静默重登录重试一次。
     * 成功后：本地收藏缓存即时增删，再后台拉最新收藏列表写缓存并校正状态。
     */
    fun toggleFavorite() {
        if (favoriteJob?.isActive == true) return
        val detail = _state.value.detail ?: return
        val target = !_isFavorited.value
        favoriteJob = viewModelScope.launch { doToggleFavorite(detail, target, retriedAuth = false) }
    }

    /**
     * 收藏切换执行体：乐观更新 → Python 调用 → 鉴权失败时重登重试一次。
     *
     * 使用 NonCancellable 确保即使用户退出详情页（viewModelScope 被取消），
     * toggle 调用和缓存写入也能完成——否则退出时协程被取消会导致
     * 收藏/取消收藏操作丢失。
     */
    private suspend fun doToggleFavorite(detail: ComicDetail, target: Boolean, retriedAuth: Boolean) {
        _isFavorited.value = target // 乐观更新
        withContext(NonCancellable) {
            try {
                val result = withContext(Dispatchers.IO) { python.toggleFavorite(albumId) }
                if (result.optBoolean("ok", false)) {
                    withContext(Dispatchers.IO) { updateFavoritesCache(detail, target) }
                } else {
                    val err = result.optString("error", "")
                    if (!retriedAuth && isAuthError(err)) {
                        silentReloginAndRetryFavorite(detail, target)
                    } else {
                        _isFavorited.value = !target
                    }
                }
            } catch (_: Exception) {
                _isFavorited.value = !target
            }
        }
    }

    private fun isAuthError(err: String): Boolean =
        err.contains("请先登入") || err.contains("請先登入") || err.contains("请先登录") ||
            err.contains("請先登錄") || err.contains("登录") || err.contains("登入") ||
            err.contains("登錄") || err.contains("未登录") || err.contains("未登入") ||
            err.contains("401") || err.contains("403") ||
            err.contains("login", ignoreCase = true) ||
            err.contains("cookie", ignoreCase = true) ||
            err.contains("unauthorized", ignoreCase = true) ||
            err.contains("not logged", ignoreCase = true)

    /** 鉴权失败时的静默重登录：用保存的凭据重新 login，成功后重试收藏切换。 */
    private suspend fun silentReloginAndRetryFavorite(detail: ComicDetail, target: Boolean) {
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        if (savedUser.isNullOrBlank() || savedPass.isNullOrBlank()) {
            _isFavorited.value = !target
            return
        }
        try {
            val loginResult = withContext(Dispatchers.IO) { python.login(savedUser, savedPass) }
            if (loginResult.optBoolean("ok", false)) {
                prefs.edit().putString("session_json", loginResult.toString()).apply()
                val retryResult = withContext(Dispatchers.IO) { python.toggleFavorite(albumId) }
                if (retryResult.optBoolean("ok", false)) {
                    withContext(Dispatchers.IO) { updateFavoritesCache(detail, target) }
                } else {
                    _isFavorited.value = !target
                }
            } else {
                _isFavorited.value = !target
            }
        } catch (_: Exception) {
            _isFavorited.value = !target
        }
    }

    /** 本地收藏缓存即时增删（收藏页秒开，不依赖网络）。 */
    private fun updateFavoritesCache(detail: ComicDetail, favorited: Boolean) {
        val cached = CacheManager.get().getCachedFavorites()?.toMutableList() ?: mutableListOf()
        if (favorited) {
            if (cached.none { it.id == detail.id }) {
                cached.add(0, ComicItem(detail.id, detail.title, detail.author, detail.coverUrl))
            }
        } else {
            cached.removeAll { it.id == detail.id }
        }
        CacheManager.get().cacheFavorites(cached)
    }

    private fun mergeDetail(base: ComicDetail?, extra: ComicDetail): ComicDetail {
        if (base == null) return extra
        return ComicDetail(
            id = base.id.ifBlank { extra.id },
            title = base.title.ifBlank { extra.title },
            author = base.author.ifBlank { extra.author },
            coverUrl = base.coverUrl.ifBlank { extra.coverUrl },
            description = base.description.ifBlank { extra.description },
            tags = if (base.tags.isNotEmpty()) base.tags else extra.tags,
            pageCount = if (base.pageCount > 0) base.pageCount else extra.pageCount,
            pubDate = base.pubDate.ifBlank { extra.pubDate },
            updateDate = base.updateDate.ifBlank { extra.updateDate },
            episodes = if (base.episodes.isNotEmpty()) base.episodes else extra.episodes,
        )
    }
}
