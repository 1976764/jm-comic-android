package com.carya.jm.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases API 检查器。
 *
 * 只负责网络请求与 JSON 解析，不持有任何 UI 状态。
 * 任何网络 / HTTP / 格式异常都通过抛出 [IOException] / [IllegalArgumentException]
 * 交给调用方（[UpdateManager]）兜底。
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/1976764/jm-comic-android/releases/latest"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 拉取最新 Release 信息（IO 线程）。失败抛异常。 */
    suspend fun fetchLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub API HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("GitHub API 返回为空")
            if (body.isBlank()) {
                throw IOException("GitHub API 返回为空")
            }
            // JSONObject 解析失败（格式异常）同样抛出，由调用方统一兜底
            ReleaseInfo.fromJson(JSONObject(body))
        }
    }
}
