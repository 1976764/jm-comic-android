package com.carya.jm.data.cache

import android.content.Context
import com.carya.jm.data.model.ComicDetail
import com.carya.jm.data.model.ComicItem
import com.carya.jm.data.model.Episode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CacheManager private constructor(private val cacheDir: File) {

    private val albumsDir = File(cacheDir, "albums").apply { mkdirs() }
    private val photosDir = File(cacheDir, "photos").apply { mkdirs() }

    companion object {
        @Volatile
        private var instance: CacheManager? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val dir = File(context.cacheDir, "jm_cache")
                        if (!dir.exists()) dir.mkdirs()
                        instance = CacheManager(dir)
                    }
                }
            }
        }

        fun get(): CacheManager =
            instance ?: throw IllegalStateException("CacheManager not initialized")
    }

    // ---- Home comics ----

    fun cacheHomeComics(items: List<ComicItem>, time: String, category: String, orderBy: String) {
        val file = File(cacheDir, "home_${time}_${category}_${orderBy}.json")
        val arr = JSONArray()
        items.forEach { it.toJson().let(arr::put) }
        JSONObject().apply {
            put("items", arr)
            put("cached_at", System.currentTimeMillis())
        }.toString().let { file.writeText(it) }
    }

    fun getCachedHomeComics(time: String, category: String, orderBy: String): List<ComicItem>? {
        val file = File(cacheDir, "home_${time}_${category}_${orderBy}.json")
        if (!file.exists()) return null
        return readItemList(file)
    }

    // ---- Reading history ----

    /** 历史记录版本号：每次写入 +1，供同会话 UI 监听变化后重新读缓存。 */
    private val _historyVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val historyVersion: kotlinx.coroutines.flow.StateFlow<Int> = _historyVersion

    /** 最多保留的历史记录条数。 */
    private val maxHistorySize = 100

    /**
     * 记录一次观看：点击开始阅读或选择章节进入阅读器时调用。
     * 若该漫画已在历史中，则更新到最前面并更新章节信息和时间。
     */
    fun addHistory(
        albumId: String,
        title: String,
        author: String,
        coverUrl: String,
        chapterId: String,
        chapterTitle: String,
    ) {
        val current = getHistory()
        val now = System.currentTimeMillis()

        val newItem = JSONObject().apply {
            put("album_id", albumId)
            put("title", title)
            put("author", author)
            put("cover_url", coverUrl)
            put("chapter_id", chapterId)
            put("chapter_title", chapterTitle)
            put("last_read_at", now)
        }

        val filtered = current.filter { it.optString("album_id") != albumId }
        val result = listOf(newItem) + filtered
        val trimmed = result.take(maxHistorySize)

        writeHistoryList(trimmed)
        _historyVersion.value += 1
    }

    /** 获取全部历史记录，按阅读时间倒序（最新在前）。 */
    fun getHistory(): List<JSONObject> {
        val file = File(cacheDir, "history.json")
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 清空全部观看历史。 */
    fun clearHistory() {
        val file = File(cacheDir, "history.json")
        if (file.exists()) file.delete()
        _historyVersion.value += 1
    }

    private fun writeHistoryList(items: List<JSONObject>) {
        val file = File(cacheDir, "history.json")
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        JSONObject().apply {
            put("items", arr)
            put("cached_at", System.currentTimeMillis())
        }.toString().let { file.writeText(it) }
    }

    // ---- Favorites ----

    /** 收藏缓存版本号：每次写缓存 +1，供同会话 UI（我的页等）监听到变化后重新读缓存。 */
    private val _favoritesVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val favoritesVersion: kotlinx.coroutines.flow.StateFlow<Int> = _favoritesVersion

    fun cacheFavorites(items: List<ComicItem>) {
        val file = File(cacheDir, "favorites.json")
        val arr = JSONArray()
        items.forEach { it.toJson().let(arr::put) }
        JSONObject().apply {
            put("items", arr)
            put("cached_at", System.currentTimeMillis())
        }.toString().let { file.writeText(it) }
        _favoritesVersion.value += 1
    }

    fun getCachedFavorites(): List<ComicItem>? {
        val file = File(cacheDir, "favorites.json")
        if (!file.exists()) return null
        return readItemList(file)
    }

    // ---- Album detail ----

    fun cacheAlbumDetail(detail: ComicDetail) {
        val file = File(albumsDir, "${detail.id}.json")
        val episodes = JSONArray()
        detail.episodes.forEach { ep ->
            episodes.put(JSONObject().apply {
                put("id", ep.id)
                put("index", ep.index)
                put("title", ep.title)
            })
        }
        val tags = JSONArray()
        detail.tags.forEach { tags.put(it) }
        JSONObject().apply {
            put("id", detail.id)
            put("title", detail.title)
            put("author", detail.author)
            put("description", detail.description)
            put("tags", tags)
            put("page_count", detail.pageCount)
            put("pub_date", detail.pubDate)
            put("update_date", detail.updateDate)
            put("cover_url", detail.coverUrl)
            put("episodes", episodes)
            put("cached_at", System.currentTimeMillis())
        }.toString().let { file.writeText(it) }
    }

    fun getCachedAlbumDetail(albumId: String): ComicDetail? {
        val file = File(albumsDir, "$albumId.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val epArr = json.optJSONArray("episodes")
            val episodes = if (epArr != null) {
                (0 until epArr.length()).map { i ->
                    val ep = epArr.getJSONObject(i)
                    Episode(
                        id = ep.optString("id"),
                        index = ep.optString("index"),
                        title = ep.optString("title"),
                    )
                }
            } else emptyList()

            val tagArr = json.optJSONArray("tags")
            val tags = if (tagArr != null) {
                (0 until tagArr.length()).map { tagArr.getString(it) }
            } else emptyList()

            ComicDetail(
                id = json.optString("id"),
                title = json.optString("title"),
                author = json.optString("author"),
                description = json.optString("description"),
                tags = tags,
                pageCount = json.optInt("page_count", 0),
                pubDate = json.optString("pub_date"),
                updateDate = json.optString("update_date"),
                coverUrl = json.optString("cover_url"),
                episodes = episodes,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---- Photo info (chapter page metadata) ----

    fun cachePhotoInfo(photoId: String, jsonString: String) {
        File(photosDir, "$photoId.json").writeText(jsonString)
    }

    fun getCachedPhotoInfo(photoId: String): String? {
        val file = File(photosDir, "$photoId.json")
        return if (file.exists()) file.readText() else null
    }

    // ---- Clear reading cache (called on app startup) ----

    /**
     * 清理上次阅读的缓存，每次冷启动执行。
     * - photos_raw/ 和 photos_decoded/（图片文件，占空间最大）
     * - albums/（漫画详情页信息）
     * - photos/（章节信息）
     * 保留 home_*.json（首页列表）、favorites.json（收藏）。
     */
    fun clearReadingCache() {
        // 清理图片缓存（在 cacheDir 的父目录下）
        val parent = cacheDir.parentFile
        if (parent != null) {
            File(parent, "photos_raw").deleteRecursively()
            File(parent, "photos_decoded").deleteRecursively()
        }
        // 清理漫画详情页信息和章节信息
        albumsDir.listFiles()?.forEach { it.delete() }
        photosDir.listFiles()?.forEach { it.delete() }
    }

    // ---- Helpers ----

    private fun readItemList(file: File): List<ComicItem>? {
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("items") ?: return null
            (0 until arr.length()).map { i ->
                val item = arr.getJSONObject(i)
                ComicItem(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    author = item.optString("author"),
                    coverUrl = item.optString("cover_url"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

private fun ComicItem.toJson() = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("author", author)
    put("cover_url", coverUrl)
}
