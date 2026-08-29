package com.carya.jm.data.model

import org.json.JSONObject

/** A comic displayed in grid lists (home, favorites). */
data class ComicItem(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String,
)

/** A single chapter/episode in an album. */
data class Episode(
    val id: String,
    val index: String,
    val title: String,
)

/** Full detail for a comic album. */
data class ComicDetail(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val pageCount: Int,
    val pubDate: String,
    val updateDate: String,
    val coverUrl: String,
    val episodes: List<Episode>,
)

/** A single downloaded image with its scramble strip count. */
data class PhotoImage(
    val path: String,
    val scrambleNum: Int,  // 0 = no scramble; >0 = number of strips to reorder
)

/** Metadata for a single page image (before download). */
data class PhotoImageInfo(
    val index: Int,
    val filename: String,
    val scrambleNum: Int,
    val cached: Boolean,
)

/** Chapter metadata returned by get_photo_info (no images downloaded yet). */
data class PhotoInfo(
    val photoId: String,
    val title: String,
    val pageCount: Int,
    val images: List<PhotoImageInfo>,
    val defaultWidth: Int = 0,
    val defaultHeight: Int = 0,
)

/** User profile data captured from the login response. */
data class UserInfo(
    val username: String,
    val uid: String = "",
    val levelName: String = "",
    val level: Int = 0,
    val coin: Int = 0,
    val albumFavorites: Int = 0,
    val albumFavoritesMax: Int = 0,
    val email: String = "",
    val gender: String = "",
    val exp: String = "",
    val expPercent: Int = 0,
)

// ---- Parsing helpers ----------------------------------------------------

/** Parse a JSON item from categories_filter / search / favorite_folder results. */
fun parseComicItem(json: JSONObject): ComicItem {
    return ComicItem(
        id = json.optString("id", ""),
        title = json.optString("title", ""),
        author = json.optString("author", ""),
        coverUrl = json.optString("cover_url", ""),
    )
}

/** Parse a list of comic items from a response JSON object. */
fun parseComicItems(response: JSONObject): List<ComicItem> {
    val arr = response.optJSONArray("items") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        parseComicItem(arr.getJSONObject(i))
    }
}

/** Parse album detail from a get_album_detail response. */
fun parseComicDetail(response: JSONObject): ComicDetail? {
    val album = response.optJSONObject("album") ?: return null
    val epArr = album.optJSONArray("episodes")
    val episodes = if (epArr != null) {
        (0 until epArr.length()).map { i ->
            val ep = epArr.getJSONObject(i)
            Episode(
                id = ep.optString("id", ""),
                index = ep.optString("index", ""),
                title = ep.optString("title", ""),
            )
        }
    } else {
        emptyList()
    }

    val tagArr = album.optJSONArray("tags")
    val tags = if (tagArr != null) {
        (0 until tagArr.length()).map { it -> tagArr.getString(it) }
    } else {
        emptyList()
    }

    return ComicDetail(
        id = album.optString("id", ""),
        title = album.optString("title", ""),
        author = album.optString("author", ""),
        description = album.optString("description", ""),
        tags = tags,
        pageCount = album.optInt("page_count", 0),
        pubDate = album.optString("pub_date", ""),
        updateDate = album.optString("update_date", ""),
        coverUrl = album.optString("cover_url", ""),
        episodes = episodes,
    )
}

/** Parse a get_photo_info response into a [PhotoInfo]. */
fun parsePhotoInfo(response: JSONObject): PhotoInfo? {
    if (!response.optBoolean("ok", false)) return null
    val arr = response.optJSONArray("images") ?: return null
    val images = (0 until arr.length()).map { i ->
        val img = arr.getJSONObject(i)
        PhotoImageInfo(
            index = img.optInt("index", i),
            filename = img.optString("filename", ""),
            scrambleNum = img.optInt("num", 0),
            cached = img.optBoolean("cached", false),
        )
    }
    return PhotoInfo(
        photoId = response.optString("photo_id", ""),
        title = response.optString("title", ""),
        pageCount = response.optInt("page_count", images.size),
        images = images,
        defaultWidth = response.optInt("default_width", 0),
        defaultHeight = response.optInt("default_height", 0),
    )
}

/** Result of a single image download. */
data class DownloadedImage(
    val path: String,
    val index: Int,
    val filename: String,
    val scrambleNum: Int,
    val width: Int = 0,
    val height: Int = 0,
)

/** Parse a download_image response. */
fun parseDownloadedImage(response: JSONObject): DownloadedImage? {
    if (!response.optBoolean("ok", false)) return null
    return DownloadedImage(
        path = response.optString("path", ""),
        index = response.optInt("index", 0),
        filename = response.optString("filename", ""),
        scrambleNum = response.optInt("num", 0),
        width = response.optInt("width", 0),
        height = response.optInt("height", 0),
    )
}

/** Parse user info from a login response or login_status response. */
fun parseUserInfo(json: JSONObject): UserInfo {
    val info = json.optJSONObject("user_info")
    val username = json.optString("username", "")
    return if (info != null) {
        UserInfo(
            username = info.optString("username", username),
            uid = info.optString("uid", ""),
            levelName = info.optString("level_name", ""),
            level = info.optInt("level", 0),
            coin = info.optInt("coin", 0),
            albumFavorites = info.optInt("album_favorites", 0),
            albumFavoritesMax = info.optInt("album_favorites_max", 0),
            email = info.optString("email", ""),
            gender = info.optString("gender", ""),
            exp = info.optString("exp", ""),
            expPercent = info.optInt("expPercent", 0),
        )
    } else {
        UserInfo(username = username)
    }
}
