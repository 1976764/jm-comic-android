package com.carya.jm.data.update

import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * GitHub Release 信息（已从中选好要下载的 APK 资产）。
 *
 * 数据来源：`GET /repos/1976764/jm-comic-android/releases/latest`。
 */
data class ReleaseInfo(
    /** 原始 tag，例如 "1.4" / "v1.4" / "Main"（展示给用户时保留原始名称） */
    val tagName: String,
    /** 归一化后的版本号（v1.4 → 1.4），用于版本比较 */
    val version: String,
    /** Release 标题，例如 "JM_1.4" */
    val title: String,
    /** 更新说明（Release body 原文） */
    val notes: String,
    /** 发布时间（格式化为 yyyy-MM-dd）；解析失败为 null */
    val publishedAt: String?,
    /** 选中的 APK 下载地址；Release 没有 APK 资产时为 null */
    val apkUrl: String?,
    /** APK 文件大小（字节）；未知为 -1 */
    val apkSize: Long,
) {
    companion object {

        /** 从 GitHub Releases API 的 release JSON 解析（结构异常时抛出异常，由调用方兜底）。 */
        fun fromJson(json: JSONObject): ReleaseInfo {
            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isEmpty()) {
                throw IllegalArgumentException("Release 缺少 tag_name")
            }

            val publishedAt = try {
                OffsetDateTime
                    .parse(json.optString("published_at", ""))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            } catch (_: Exception) {
                null
            }

            return ReleaseInfo(
                tagName = tagName,
                version = VersionUtils.normalize(tagName),
                title = json.optString("name", "").ifBlank { tagName },
                notes = json.optString("body", "").trim(),
                publishedAt = publishedAt,
                apkUrl = selectApkAsset(json),
                apkSize = selectApkAssetSize(json),
            )
        }

        /** 选择适合当前设备的 APK 资产下载地址；没有 APK 时返回 null。 */
        private fun selectApkAsset(json: JSONObject): String? {
            val apks = apkAssets(json)
            val selected = selectApkAssetJson(apks) ?: return null
            return selected.optString("browser_download_url", "").ifBlank { null }
        }

        private fun selectApkAssetSize(json: JSONObject): Long {
            val apks = apkAssets(json)
            val selected = selectApkAssetJson(apks) ?: return -1L
            return selected.optLong("size", -1L)
        }

        private fun apkAssets(json: JSONObject): List<JSONObject> {
            val assets = json.optJSONArray("assets") ?: return emptyList()
            return (0 until assets.length())
                .mapNotNull { i -> assets.optJSONObject(i) }
                .filter { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
        }

        /**
         * APK 选择优先级：
         * 1. 文件名含 "arm64" 且非 debug（项目 abi 仅 arm64-v8a）
         * 2. 非 debug 的任意 APK
         * 3. 任意 APK
         */
        private fun selectApkAssetJson(apks: List<JSONObject>): JSONObject? {
            if (apks.isEmpty()) return null
            val isDebug = { a: JSONObject -> a.optString("name").contains("debug", ignoreCase = true) }
            val isArm64 = { a: JSONObject -> a.optString("name").contains("arm64", ignoreCase = true) }
            return apks.firstOrNull { isArm64(it) && !isDebug(it) }
                ?: apks.firstOrNull { !isDebug(it) }
                ?: apks.first()
        }
    }
}

/**
 * 版本号比较工具。
 *
 * 规范比较：`1.3 < 1.4 < 1.10`（逐段按数值比较，而不是字符串比较）。
 * 兼容 `v` 前缀（v1.4 → 1.4）；无法解析的段按字符串比较兜底。
 */
object VersionUtils {

    /** 去掉版本号前的 v/V 前缀。 */
    fun normalize(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    /**
     * 比较两个版本号（先各自归一化），返回负数 / 0 / 正数。
     * 缺失的段视为小于存在的段（1.4 < 1.4.1）。
     */
    fun compare(rawA: String, rawB: String): Int {
        val a = normalize(rawA).split(".")
        val b = normalize(rawB).split(".")

        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)
            val y = b.getOrNull(i)
            val xn = x?.toIntOrNull()
            val yn = y?.toIntOrNull()

            when {
                xn != null && yn != null -> if (xn != yn) return xn.compareTo(yn)
                xn != null -> return 1   // 数字段 > 非数字/缺失段（1.4.0 > 1.4）
                yn != null -> return -1  // 缺失段 < 数字段（1.4 < 1.4.1）
                else -> {
                    val c = (x ?: "").compareTo(y ?: "")
                    if (c != 0) return c
                }
            }
        }
        return 0
    }

    /** candidate 是否比 current 更新（严格大于）。 */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0
}
