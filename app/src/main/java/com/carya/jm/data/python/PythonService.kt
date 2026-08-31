package com.carya.jm.data.python

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject

/** The sole Kotlin entry point for embedded Python. */
class PythonService {

    /** Call this from a background dispatcher if an operation can do I/O. */
    fun health(): PythonRuntimeStatus {
        val result = JSONObject(module().callAttr("health_check").toString())
        return PythonRuntimeStatus(
            pythonVersion = result.getString("python"),
            jmcomicInstalled = result.getBoolean("jmcomicInstalled"),
        )
    }

    /** Loads curl-cffi so ABI and native-library failures are returned as JSON. */
    fun testCurlCffi(): JSONObject =
        JSONObject(module().callAttr("test_curl_cffi").toString())

    // ---- Login / logout / status ----------------------------------------

    /**
     * Authenticate against the JM server via jmcomic's JmApiClient.login().
     * Must be called on a background thread (network I/O).
     *
     * Returns: {"ok": true, "username": "...", "user_info": {...}} on success,
     *          {"ok": false, "error": "..."} on failure.
     */
    fun login(username: String, password: String): JSONObject {
        val payload = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        return invoke("login", payload)
    }

    /** Clear the stored jmcomic client and session cookies. */
    fun logout(): JSONObject = invoke("logout")

    /**
     * Restore a saved session by injecting cookies into a fresh client.
     * Verifies the session is still valid via an API call.
     *
     * Returns: {"ok": true, "username": "...", "user_info": {...}} on success,
     *          {"ok": false, "expired": true, ...} if cookies expired,
     *          {"ok": false, "error": "..."} on other failures.
     */
    fun restoreSession(sessionJson: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("cookies", sessionJson.opt("cookies") ?: JSONObject())
            put("username", sessionJson.optString("username", ""))
            val userInfo = sessionJson.opt("user_info")
            if (userInfo != null) put("user_info", userInfo)
        }
        return invoke("restore_session", payload)
    }

    /**
     * Check whether a jmcomic client is active.
     * Returns: {"loggedIn": true/false, "username": "...", "user_info": {...}}
     */
    fun getLoginStatus(): JSONObject = invoke("login_status")

    /** Get stored user info captured during login. */
    fun getUserInfo(): JSONObject = invoke("get_user_info")

    // ---- Browsing (no login required) -----------------------------------

    /**
     * Fetch comics filtered by category / time / order.
     * Must be called on a background thread.
     *
     * Returns: {"ok": true, "items": [...], "total": N, "page": P}
     */
    fun categoriesFilter(
        page: Int = 1,
        time: String = "a",
        category: String = "0",
        orderBy: String = "mv",
    ): JSONObject {
        val payload = JSONObject().apply {
            put("page", page)
            put("time", time)
            put("category", category)
            put("order_by", orderBy)
        }
        return invoke("categories_filter", payload)
    }

    /**
     * Fetch full detail for a single album.
     * Must be called on a background thread.
     *
     * Returns: {"ok": true, "album": {...}}
     */
    fun getAlbumDetail(albumId: String): JSONObject {
        val payload = JSONObject().apply {
            put("album_id", albumId)
        }
        return invoke("get_album_detail", payload)
    }

    /**
     * Get chapter metadata (page list, scramble info) without downloading images.
     * Fast — returns instantly after fetching photo detail.
     * Must be called on a background thread.
     *
     * Returns: {"ok": true, "photo_id": "...", "title": "...",
     *           "page_count": N, "images": [{"index": 0, "filename": "00001.webp", "num": 10, "cached": false}, ...]}
     */
    fun getPhotoInfo(photoId: String, cacheDir: String): JSONObject {
        val payload = JSONObject().apply {
            put("photo_id", photoId)
            put("cache_dir", cacheDir)
        }
        return invoke("get_photo_info", payload)
    }

    /**
     * Download a single image by index (raw bytes, no PIL).
     * Must be called on a background thread.
     *
     * Returns: {"ok": true, "path": "...", "index": 0, "filename": "...", "num": 10}
     */
    fun downloadImage(photoId: String, index: Int, cacheDir: String): JSONObject {
        val payload = JSONObject().apply {
            put("photo_id", photoId)
            put("cache_dir", cacheDir)
            put("index", index)
        }
        return invoke("download_image", payload)
    }

    /**
     * Download multiple images concurrently (8 workers in Python ThreadPoolExecutor).
     * Must be called on a background thread.
     *
     * Returns: {"ok": true, "images": [{"ok": true, "index": 0, "path": "...", ...}, ...]}
     */
    fun downloadImagesBatch(photoId: String, indices: List<Int>, cacheDir: String): JSONObject {
        val payload = JSONObject().apply {
            put("photo_id", photoId)
            put("cache_dir", cacheDir)
            put("indices", JSONArray(indices))
        }
        return invoke("download_images_batch", payload)
    }

    /**
     * Download ALL images for a single chapter (photo) with decode_image=False.
     * Uses ThreadPoolExecutor (8 workers) for concurrent downloads.
     * Returns raw (scrambled) images — Kotlin must descramble via ImageDescrambler.
     *
     * Returns: {"ok": true, "photo_id": "...", "title": "...",
     *           "page_count": N, "images": [{"ok": true, "index": 0, "filename": "...",
     *           "path": "...", "num": 10, "width": W, "height": H}, ...]}
     */
    fun downloadChapter(photoId: String, outputDir: String): JSONObject {
        val payload = JSONObject().apply {
            put("photo_id", photoId)
            put("output_dir", outputDir)
        }
        return invoke("download_chapter", payload)
    }

    /**
     * Download an entire album using jmcomic's official download_album API.
     * The library downloads all chapters with multithreading (imageThreads
     * image workers per chapter, photoThreads concurrent chapters), saving
     * raw (scrambled) images to {outputDir}/{photoId}/{filename}. This call
     * BLOCKS until the whole album is done — call it on its own IO coroutine
     * and poll [progressFile] for real-time progress.
     *
     * After download, a descramble map is written to [descrambleFile]:
     * {photoId: {title, files: [{filename, num, path}]}} so the caller can
     * descramble every image in place via ImageDescrambler.
     *
     * Returns: {"ok": true, "album_id": "...", "output_dir": "...",
     *           "total_images": N, "total_files": N, "chapter_dirs": [...],
     *           "failed_images": N, "failed_photos": N}
     */
    fun downloadAlbumApi(
        albumId: String,
        outputDir: String,
        progressFile: String = "",
        descrambleFile: String = "",
        imageThreads: Int = 10,
        photoThreads: Int = 2,
    ): JSONObject {
        val payload = JSONObject().apply {
            put("album_id", albumId)
            put("output_dir", outputDir)
            put("progress_file", progressFile)
            put("descramble_file", descrambleFile)
            put("image_threads", imageThreads)
            put("photo_threads", photoThreads)
        }
        return invoke("download_album_api", payload)
    }

    /**
     * Search for comics by keyword.
     * [mainTag]: 0=站内, 1=作品, 2=作者, 3=标签, 4=演员.
     * Must be called on a background thread.
     */
    fun search(
        query: String,
        page: Int = 1,
        orderBy: String = "mr",
        time: String = "a",
        category: String = "all",
        mainTag: Int = 0,
    ): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("page", page)
            put("order_by", orderBy)
            put("time", time)
            put("category", category)
            put("main_tag", mainTag)
        }
        return invoke("search", payload)
    }

    // ---- Favorites (requires login) -------------------------------------

    /**
     * Fetch the user's favorite albums.
     * Must be called on a background thread.
     */
    fun favoriteFolder(
        page: Int = 1,
        orderBy: String = "mr",
        folderId: String = "0",
    ): JSONObject {
        val payload = JSONObject().apply {
            put("page", page)
            put("order_by", orderBy)
            put("folder_id", folderId)
        }
        return invoke("favorite_folder", payload)
    }

    // ---- Domain speed test ----------------------------------------------

    /**
     * 探测候选 API 域名（TCP 443）的延迟，返回按优劣排序的结果，
     * 并把最优域名应用到 jmcomic client（当前 + 后续创建的）。
     * 必须放在后台线程执行（网络 I/O，最长约 probes × timeout 秒）。
     *
     * Returns: {"ok": true, "best": {"domain": "...",
     *           "avg_latency_ms": N, "success": true}, "results": [...],
     *           "applied": "..." | null}
     */
    fun testDomains(domains: List<String>, probes: Int = 3): JSONObject {
        val payload = JSONObject().apply {
            put("domains", JSONArray(domains))
            put("probes", probes)
        }
        return invoke("test_domains", payload)
    }

    /**
     * 强制让 jmcomic client 使用指定域名（设置页手动选择时调用）。
     * 必须放在后台线程执行。
     *
     * Returns: {"ok": true, "applied": "..."} 或 {"ok": false, "error": "..."}
     */
    /**
     * 收藏 / 取消收藏（移动端 /favorite 切换语义）。调用后应当后台刷新
     * favoriteFolder 校正状态（见 ComicDetailViewModel）。
     * 必须放在后台线程执行（网络 I/O）。
     *
     * Returns: {"ok": true} 或 {"ok": false, "error": "..."}
     */
    fun toggleFavorite(albumId: String): JSONObject {
        val payload = JSONObject().apply {
            put("album_id", albumId)
        }
        return invoke("toggle_favorite", payload)
    }

    fun setDomain(domain: String): JSONObject {
        val payload = JSONObject().apply {
            put("domain", domain)
        }
        return invoke("set_domain", payload)
    }

    // ---- Generic dispatch -----------------------------------------------

    /** Generic dispatch -- exposed for future operations. */
    fun invoke(operation: String, payload: JSONObject = JSONObject()): JSONObject =
        JSONObject(module().callAttr("invoke", operation, payload.toString()).toString())

    private fun module() = Python.getInstance().getModule("jm_bridge")

    companion object {
        /** 候选 API 域名：启动/设置页测速时使用。 */
        val DOMAIN_CANDIDATES = listOf(
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club",
            "www.cdnutc.me",
        )

        fun initialize(platform: AndroidPlatform) {
            if (!Python.isStarted()) Python.start(platform)
        }
    }
}

data class PythonRuntimeStatus(
    val pythonVersion: String,
    val jmcomicInstalled: Boolean,
)
