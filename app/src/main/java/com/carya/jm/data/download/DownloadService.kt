package com.carya.jm.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.carya.jm.data.model.Episode
import com.carya.jm.data.python.PythonService
import com.carya.jm.data.settings.AppSettings
import com.carya.jm.ui.reader.ImageDescrambler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 前台下载服务：支持**多本漫画同时下载**。
 *
 * 每个 album 一个独立 job 和进度条目（[activeDownloads] 以 albumId 为键），
 * 任意一本完成/失败/取消都不影响其它下载。前台通知是汇总条，
 * 每本还有一个独立的下载进度通知。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** albumId -> 正在运行的下载协程 */
    private val jobs = mutableMapOf<String, Job>()

    /** albumId -> 当前进度（下载中 / 完成 / 出错都先留在表里，3 秒后清理） */
    private val progressMap = ConcurrentHashMap<String, DownloadProgress>()

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val albumId = intent.getStringExtra(EXTRA_ALBUM_ID)
                if (albumId != null) {
                    cancelAlbum(albumId)
                } else {
                    jobs.keys.toList().forEach { cancelAlbum(it) }
                }
                maybeStopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val albumId = intent?.getStringExtra(EXTRA_ALBUM_ID) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "漫画下载"
                val author = intent?.getStringExtra(EXTRA_AUTHOR) ?: ""
                val coverUrl = intent?.getStringExtra(EXTRA_COVER_URL) ?: ""
                val episodesJson = intent?.getStringExtra(EXTRA_EPISODES) ?: "[]"

                // 同一本已在下载则忽略重复请求
                if (jobs.containsKey(albumId)) return START_NOT_STICKY

                // 第一个下载启动前台服务（汇总通知）
                if (progressMap.isEmpty()) {
                    startForegroundCompat()
                }

                jobs[albumId] = scope.launch {
                    try {
                        downloadComic(albumId, title, author, coverUrl, episodesJson)
                        // 成功后 scheduleFinish 在 downloadComic 内触发
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateProgress(
                            albumId,
                            DownloadProgress(
                                albumId = albumId,
                                title = title,
                                coverUrl = coverUrl,
                                isDownloading = false,
                                error = e.message ?: "下载失败",
                            ),
                        )
                        notificationManager.cancel(notificationId(albumId))
                        scheduleFinish(albumId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun cancelAlbum(albumId: String) {
        jobs.remove(albumId)?.cancel()
        progressMap.remove(albumId)
        publishMap()
        notificationManager.cancel(notificationId(albumId))
    }

    /** 没有任何进行中下载时停止前台与自身。 */
    private fun maybeStopSelf() {
        if (jobs.isEmpty() && progressMap.values.none { it.isDownloading }) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateSummaryNotification()
        }
    }

    /**
     * 下载结束（成功或失败）后：把最终状态保留 3 秒供 UI 读取，
     * 然后移除该本进度；若没有其它下载则停止服务。
     */
    private fun scheduleFinish(albumId: String) {
        scope.launch {
            delay(FINISH_RETAIN_MS)
            progressMap.remove(albumId)
            publishMap()
            notificationManager.cancel(notificationId(albumId))
            jobs.remove(albumId)
            maybeStopSelf()
        }
    }

    private fun updateProgress(albumId: String, progress: DownloadProgress) {
        if (albumId.isBlank()) return
        progressMap[albumId] = progress
        publishMap()
    }

    private fun publishMap() {
        _activeDownloads.value = progressMap.toMap()
    }

    /**
     * Download the whole album through jmcomic's official `download_album`
     * (Python `download_album_api`): jmcomic downloads chapters on parallel
     * workers (photo threads) and each chapter's images on an image thread
     * pool. Raw scrambled images land in {offlineDir}/{photoId}/{filename}.
     *
     * The Python call blocks until everything is downloaded, so it runs on its
     * own IO coroutine while a poller reads the progress sidecar for real-time
     * UI/notification updates. Afterwards every raw image is descrambled in
     * place (ImageDescrambler), then everything is zipped and exported.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadComic(
        albumId: String,
        title: String,
        author: String,
        coverUrl: String,
        episodesJson: String,
    ) {
        val episodes = parseEpisodes(JSONArray(episodesJson))
        if (episodes.isEmpty()) {
            updateProgress(albumId, DownloadProgress(albumId = albumId, error = "没有可下载的章节"))
            return
        }

        val python = PythonService()
        val offlineDir = DownloadManager.get().getOfflineDir(albumId)
        offlineDir.mkdirs()

        // Per-album sidecar files written by the Python side.
        val progressFile = File(cacheDir, "download_progress_$albumId.json")
        val descrambleFile = File(cacheDir, "download_descramble_$albumId.json")
        progressFile.delete()
        descrambleFile.delete()

        updateProgress(
            albumId,
            DownloadProgress(
                albumId = albumId,
                title = title,
                coverUrl = coverUrl,
                isDownloading = true,
                totalChapters = episodes.size,
            ),
        )
        updateAlbumNotification(albumId, title, 0, episodes.size, 0, 0)
        updateSummaryNotification()

        // Phase 1: one blocking downloadAlbumApi call; while jmcomic downloads,
        // poll the progress sidecar AND descramble finished files concurrently.
        val workerPool = Dispatchers.IO.limitedParallelism(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )
        val processedPaths = ConcurrentHashMap.newKeySet<String>()
        val processedCount = AtomicInteger(0)

        val download = scope.async(Dispatchers.IO) {
            python.downloadAlbumApi(
                albumId = albumId,
                outputDir = offlineDir.absolutePath,
                progressFile = progressFile.absolutePath,
                descrambleFile = descrambleFile.absolutePath,
            )
        }
        val poller = scope.launch {
            while (isActive) {
                val p = readProgress(progressFile)
                if (p != null) {
                    val prev = progressMap[albumId] ?: DownloadProgress(albumId = albumId, title = title)
                    updateProgress(
                        albumId,
                        prev.copy(
                            totalImages = p.totalImages.takeIf { it > 0 } ?: prev.totalImages,
                            downloadedImages = p.downloadedImages,
                            currentChapter = p.currentChapter,
                            chapterTitle = p.chapterTitle,
                            totalChapters = p.totalChapters.takeIf { it > 0 } ?: prev.totalChapters,
                        ),
                    )
                    val cur = progressMap[albumId] ?: prev
                    updateAlbumNotification(
                        albumId,
                        title,
                        cur.currentChapter + 1,
                        episodes.size,
                        cur.downloadedImages,
                        cur.totalImages,
                    )
                    updateSummaryNotification()
                }
                delay(PROGRESS_POLL_MS)
            }
        }

        // Descramble worker: Python writes each chapter's {filename, num} map
        // entry in before_photo (chapter start), so as soon as a chapter's raw
        // files land on disk we can descramble them — in parallel with download.
        // The worker stops when the download is done and nothing is left to do.
        val worker = scope.launch {
            try {
                while (isActive) {
                    val work = collectPendingWork(
                        readDescrambleMap(descrambleFile),
                        offlineDir,
                        processedPaths,
                    )
                    if (work.isNotEmpty()) {
                        processBatch(albumId, work, workerPool, processedPaths, processedCount)
                    } else if (download.isCompleted) {
                        break
                    } else {
                        delay(PROGRESS_POLL_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore worker-side errors; the final drain + failure checks
                // below surface any real problem.
            }
        }

        val result = try {
            download.await()
        } catch (e: Throwable) {
            worker.cancel()
            throw e
        } finally {
            poller.cancel()
        }

        // Phase 2: drain whatever the worker hasn't processed yet (files that
        // landed right at the end, or chapters whose map entry arrived late).
        worker.join()
        val descrambleMap = readDescrambleMap(descrambleFile)
        val totalImages = descrambleMap.values.sumOf { it.files.size }
        if (totalImages == 0) {
            throw Exception("下载完成但未找到图片文件，请检查网络或登录状态")
        }
        val prev = progressMap[albumId] ?: DownloadProgress(albumId = albumId, title = title)
        updateProgress(
            albumId,
            prev.copy(
                chapterTitle = "正在处理图片…",
                totalImages = totalImages,
                currentChapter = episodes.size,
            ),
        )
        val leftover = collectPendingWork(descrambleMap, offlineDir, processedPaths)
        if (leftover.isNotEmpty()) {
            processBatch(albumId, leftover, workerPool, processedPaths, processedCount)
        }
        worker.cancel()

        if (!result.optBoolean("ok", false)) {
            throw Exception(result.optString("error", "下载失败"))
        }
        val totalProcessed = processedCount.get()
        if (totalProcessed == 0) {
            throw Exception("图片处理失败，请重试")
        }

        // Phase 3: optionally pack ZIP into the public Downloads folder
        // (setting "在下载目录保存副本", default off — internal copy only).
        if (AppSettings.storeZipToDownloads) {
            val cur = progressMap[albumId] ?: DownloadProgress(albumId = albumId, title = title)
            updateProgress(
                albumId,
                cur.copy(
                    chapterTitle = "正在打包…",
                    downloadedImages = totalProcessed,
                ),
            )
            val zipFile = createZip(offlineDir, title)
            saveZipToDownloads(zipFile, title)
        }

        DownloadManager.get().addDownload(
            DownloadedComic(
                albumId = albumId,
                title = title,
                author = author,
                coverUrl = coverUrl,
                episodes = episodes,
                downloadDate = System.currentTimeMillis(),
                totalImages = totalProcessed,
            )
        )

        updateProgress(
            albumId,
            DownloadProgress(
                albumId = albumId,
                title = title,
                coverUrl = coverUrl,
                isDownloading = false,
                completed = true,
                totalChapters = episodes.size,
                totalImages = totalImages,
                downloadedImages = totalProcessed,
            ),
        )

        showCompleteNotification(albumId, title)
        updateSummaryNotification()
        scheduleFinish(albumId)
    }

    // ---- Progress / descramble sidecar readers ---------------------------

    private data class ProgressSnapshot(
        val totalImages: Int,
        val downloadedImages: Int,
        val totalChapters: Int,
        val currentChapter: Int,
        val chapterTitle: String,
    )

    private data class DescrambleChapter(
        val title: String,
        val files: List<DescrambleFile>,
    )

    private data class DescrambleFile(
        val filename: String,
        val num: Int,
    )

    /** One file scheduled for descrambling. */
    private data class DescrambleWork(
        val file: File,
        val num: Int,
    )

    private fun readProgress(file: File): ProgressSnapshot? {
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            ProgressSnapshot(
                totalImages = json.optInt("total_images"),
                downloadedImages = json.optInt("downloaded_images"),
                totalChapters = json.optInt("total_chapters"),
                currentChapter = json.optInt("current_chapter"),
                chapterTitle = json.optString("chapter_title"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readDescrambleMap(file: File): LinkedHashMap<String, DescrambleChapter> {
        val map = LinkedHashMap<String, DescrambleChapter>()
        if (!file.exists()) return map
        return try {
            val root = JSONObject(file.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val photoId = keys.next()
                val chapter = root.getJSONObject(photoId)
                val filesArr = chapter.optJSONArray("files") ?: continue
                val files = (0 until filesArr.length()).mapNotNull { i ->
                    val f = filesArr.optJSONObject(i) ?: return@mapNotNull null
                    DescrambleFile(
                        filename = f.optString("filename"),
                        num = f.optInt("num"),
                    )
                }
                if (files.isNotEmpty()) {
                    map[photoId] = DescrambleChapter(
                        title = chapter.optString("title"),
                        files = files,
                    )
                }
            }
            map
        } catch (_: Exception) {
            map
        }
    }

    /**
     * Collect files that are mapped, already on disk, and not yet processed.
     * Files are claimed (added to [processedPaths]) here so concurrent
     * collectors never schedule the same file twice.
     */
    private fun collectPendingWork(
        map: LinkedHashMap<String, DescrambleChapter>,
        offlineDir: File,
        processedPaths: MutableSet<String>,
    ): List<DescrambleWork> {
        val work = ArrayList<DescrambleWork>()
        for ((photoId, chapter) in map) {
            for (f in chapter.files) {
                val file = File(offlineDir, "$photoId/${f.filename}")
                if (!file.exists()) continue
                if (!processedPaths.add(file.absolutePath)) continue
                work.add(DescrambleWork(file, f.num))
            }
        }
        return work
    }

    /** Descramble a batch of files in parallel (bounded pool), updating progress. */
    private suspend fun processBatch(
        albumId: String,
        work: List<DescrambleWork>,
        pool: CoroutineDispatcher,
        processedPaths: MutableSet<String>,
        processedCount: AtomicInteger,
    ) {
        withContext(pool) {
            coroutineScope {
                work.forEach { w ->
                    launch {
                        coroutineContext.ensureActive()
                        val rawFile = w.file
                        if (rawFile.exists()) {
                            val tmpFile = File(rawFile.parentFile, "tmp_${rawFile.name}")
                            if (ImageDescrambler.descramble(rawFile.absolutePath, tmpFile.absolutePath, w.num)) {
                                rawFile.delete()
                                tmpFile.renameTo(rawFile)
                                processedCount.incrementAndGet()
                            } else {
                                tmpFile.delete()
                                // Release the claim so the final drain can retry
                                // with the complete (post-download) map.
                                processedPaths.remove(rawFile.absolutePath)
                            }
                        }
                        val prev = progressMap[albumId] ?: DownloadProgress(albumId = albumId)
                        updateProgress(albumId, prev.copy(downloadedImages = processedCount.get()))
                    }
                }
            }
        }
    }

    private fun parseEpisodes(arr: JSONArray): List<Episode> =
        (0 until arr.length()).map { i ->
            val ep = arr.getJSONObject(i)
            Episode(
                id = ep.optString("id"),
                index = ep.optString("index"),
                title = ep.optString("title"),
            )
        }

    private fun createZip(sourceDir: File, title: String): File {
        val downloadsDir = File(filesDir, "downloads").also { it.mkdirs() }
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val zipFile = File(downloadsDir, "${safeTitle}.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(sourceDir).path
                    zos.putNextEntry(ZipEntry(relativePath))
                    FileInputStream(file).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        return zipFile
    }

    private fun saveZipToDownloads(zipFile: File, title: String) {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeTitle}.zip")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JM")
            }
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(zipFile).use { fis -> fis.copyTo(out) }
        }
    }

    // ---- Notifications ----------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "漫画下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "漫画下载进度通知" }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun notificationId(albumId: String): Int =
        NOTIFICATION_ID + (albumId.hashCode() and 0x7fffffff) % 10000 + 1

    /** 每本独立的下载进度通知。 */
    private fun buildAlbumNotification(
        title: String,
        chapter: Int,
        totalChapters: Int,
        downloaded: Int,
        total: Int,
    ): Notification {
        val progressText = when {
            total > 0 && downloaded > 0 -> "$downloaded/$total 张图片"
            totalChapters > 0 && chapter > 0 -> "第 $chapter/$totalChapters 章"
            else -> "准备中…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在下载: $title")
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (total > 0) setProgress(total, downloaded, false)
                else if (totalChapters > 0) setProgress(totalChapters, chapter, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun updateAlbumNotification(
        albumId: String,
        title: String,
        chapter: Int,
        totalChapters: Int,
        downloaded: Int,
        total: Int,
    ) {
        notificationManager.notify(
            notificationId(albumId),
            buildAlbumNotification(title, chapter, totalChapters, downloaded, total),
        )
    }

    /** 前台汇总通知：显示正在下载的漫画数量与总进度。 */
    private fun buildSummaryNotification(): Notification {
        val active = progressMap.values.filter { it.isDownloading }
        val count = active.size
        val sumDownloaded = active.sumOf { it.downloadedImages }
        val sumTotal = active.sumOf { it.totalImages }
        val first = active.firstOrNull()
        val title = if (count > 0) "正在下载 $count 本漫画" else "漫画下载"
        val text = when {
            sumTotal > 0 -> "共 $sumDownloaded/$sumTotal 张${first?.let { " · ${it.title}" } ?: ""}"
            first != null -> first.title
            else -> "准备中…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (sumTotal > 0) setProgress(sumTotal, sumDownloaded, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun updateSummaryNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildSummaryNotification())
    }

    private fun showCompleteNotification(albumId: String, title: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("下载完成: $title")
            .setContentText("漫画已保存到下载目录")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId(albumId) + 10000, notification)
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat() {
        val notification = buildSummaryNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1234
        const val CHANNEL_ID = "comic_download"
        const val ACTION_START = "com.carya.jm.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.carya.jm.CANCEL_DOWNLOAD"
        const val EXTRA_ALBUM_ID = "albumId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_COVER_URL = "coverUrl"
        const val EXTRA_EPISODES = "episodes"

        /** How often the progress sidecar is polled while jmcomic downloads. */
        private const val PROGRESS_POLL_MS = 400L

        /** 下载结束后保留完成/错误进度条目的时长（供 UI 读取）。 */
        private const val FINISH_RETAIN_MS = 3000L

        private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

        fun startDownload(
            context: Context,
            albumId: String,
            title: String,
            author: String,
            coverUrl: String,
            episodes: List<Episode>,
        ) {
            val episodesJson = JSONArray().apply {
                episodes.forEach { ep ->
                    put(org.json.JSONObject().apply {
                        put("id", ep.id)
                        put("index", ep.index)
                        put("title", ep.title)
                    })
                }
            }.toString()

            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ALBUM_ID, albumId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AUTHOR, author)
                putExtra(EXTRA_COVER_URL, coverUrl)
                putExtra(EXTRA_EPISODES, episodesJson)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 取消指定漫画的下载；albumId 为空时取消全部。 */
        fun cancelDownload(context: Context, albumId: String? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                if (albumId != null) putExtra(EXTRA_ALBUM_ID, albumId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
