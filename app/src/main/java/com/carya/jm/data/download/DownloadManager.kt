package com.carya.jm.data.download

import android.content.Context
import android.provider.MediaStore
import com.carya.jm.data.model.Episode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DownloadedComic(
    val albumId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val episodes: List<Episode>,
    val downloadDate: Long,
    val totalImages: Int,
)

data class DownloadProgress(
    val albumId: String = "",
    val title: String = "",
    val coverUrl: String = "",
    val isDownloading: Boolean = false,
    val currentChapter: Int = 0,
    val totalChapters: Int = 0,
    val chapterTitle: String = "",
    val downloadedImages: Int = 0,
    val totalImages: Int = 0,
    val error: String? = null,
    val completed: Boolean = false,
)

class DownloadManager private constructor(private val context: Context) {

    private val metadataFile = File(context.filesDir, "downloaded_comics.json")

    companion object {
        @Volatile
        private var instance: DownloadManager? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) instance = DownloadManager(context.applicationContext)
                }
            }
        }

        fun get(): DownloadManager =
            instance ?: throw IllegalStateException("DownloadManager not initialized")
    }

    /** 离线阅读图片目录: filesDir/offline_comics/{albumId}/{photoId}/ *.webp */
    fun getOfflineDir(albumId: String): File =
        File(context.filesDir, "offline_comics/$albumId").also { it.mkdirs() }

    fun getOfflineChapterDir(albumId: String, photoId: String): File =
        File(getOfflineDir(albumId), photoId).also { it.mkdirs() }

    fun getDownloads(): List<DownloadedComic> {
        if (!metadataFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(metadataFile.readText())
            (0 until arr.length()).map { i ->
                val json = arr.getJSONObject(i)
                val epArr = json.optJSONArray("episodes")
                val episodes = if (epArr != null) {
                    (0 until epArr.length()).map { j ->
                        val ep = epArr.getJSONObject(j)
                        Episode(
                            id = ep.optString("id"),
                            index = ep.optString("index"),
                            title = ep.optString("title"),
                        )
                    }
                } else emptyList()

                DownloadedComic(
                    albumId = json.optString("albumId"),
                    title = json.optString("title"),
                    author = json.optString("author"),
                    coverUrl = json.optString("coverUrl"),
                    episodes = episodes,
                    downloadDate = json.optLong("downloadDate"),
                    totalImages = json.optInt("totalImages"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isDownloaded(albumId: String): Boolean =
        getDownloads().any { it.albumId == albumId }

    fun addDownload(comic: DownloadedComic) {
        val list = getDownloads().filter { it.albumId != comic.albumId }.toMutableList()
        list.add(comic)
        saveDownloads(list)
    }

    fun removeDownload(albumId: String) {
        // Find the comic title before removing metadata (needed for ZIP cleanup)
        val comic = getDownloads().find { it.albumId == albumId }
        val list = getDownloads().filter { it.albumId != albumId }
        saveDownloads(list)

        // 1. Delete offline reading image directory
        getOfflineDir(albumId).deleteRecursively()

        // 2. Delete internal ZIP copy (filesDir/downloads/{title}.zip)
        if (comic != null) {
            val safeTitle = comic.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val zipFile = File(context.filesDir, "downloads/${safeTitle}.zip")
            zipFile.delete()
        }

        // 3. Delete ZIP from MediaStore Downloads (public Downloads/JM/ directory)
        if (comic != null) {
            deleteZipFromMediaStore(comic.title)
        }
    }

    fun removeDownloads(albumIds: List<String>) {
        val set = albumIds.toSet()
        val toRemove = getDownloads().filter { it.albumId in set }
        val list = getDownloads().filter { it.albumId !in set }
        saveDownloads(list)

        albumIds.forEach { getOfflineDir(it).deleteRecursively() }

        // Delete ZIP files for each removed comic
        for (comic in toRemove) {
            val safeTitle = comic.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val zipFile = File(context.filesDir, "downloads/${safeTitle}.zip")
            zipFile.delete()
            deleteZipFromMediaStore(comic.title)
        }
    }

    /**
     * Delete a ZIP file from the public Downloads/JM/ directory via MediaStore.
     * Queries MediaStore for entries matching the comic title and deletes them.
     */
    private fun deleteZipFromMediaStore(title: String) {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val displayName = "${safeTitle}.zip"

        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(displayName)
            context.contentResolver.delete(collection, selection, selectionArgs)
        } catch (_: Exception) {
            // MediaStore deletion may fail on some devices; ignore silently
        }
    }

    private fun saveDownloads(list: List<DownloadedComic>) {
        val arr = JSONArray()
        list.forEach { comic ->
            val epArr = JSONArray()
            comic.episodes.forEach { ep ->
                epArr.put(JSONObject().apply {
                    put("id", ep.id)
                    put("index", ep.index)
                    put("title", ep.title)
                })
            }
            arr.put(JSONObject().apply {
                put("albumId", comic.albumId)
                put("title", comic.title)
                put("author", comic.author)
                put("coverUrl", comic.coverUrl)
                put("episodes", epArr)
                put("downloadDate", comic.downloadDate)
                put("totalImages", comic.totalImages)
            })
        }
        metadataFile.writeText(arr.toString())
    }
}
