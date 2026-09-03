package com.carya.jm.data.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 应用设置（SharedPreferences 存储，进程内单例）。
 *
 * 在 [MainActivity] 里调用 [init] 一次，之后任何地方通过属性读写。
 */
object AppSettings {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_STORE_ZIP_TO_DOWNLOADS = "store_zip_to_downloads"
    private const val KEY_SELECTED_DOMAIN = "selected_domain"
    private const val KEY_DOMAIN_MANUAL = "domain_manual"
    private const val KEY_DOMAIN_TEST_RESULTS = "domain_test_results"
    private const val KEY_IGNORED_UPDATE_VERSION = "ignored_update_version"
    private const val KEY_SEARCH_HISTORY = "search_history"
    private const val SEARCH_HISTORY_MAX = 15
    private const val KEY_LAST_VISIT_DATE = "last_visit_date"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun prefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("AppSettings 未初始化，请先在 Application/MainActivity 调用 init()")

    /** 是否在设备的默认下载目录（Downloads/JM）额外存一份 zip。默认关闭（不存储）。 */
    var storeZipToDownloads: Boolean
        get() = prefs().getBoolean(KEY_STORE_ZIP_TO_DOWNLOADS, false)
        set(value) {
            prefs().edit().putBoolean(KEY_STORE_ZIP_TO_DOWNLOADS, value).apply()
        }

    // ---- 域名 ----

    /** 当前正在使用的 API 域名；null 表示还没测速/选择（用 jmcomic 默认）。 */
    var selectedDomain: String?
        get() = prefs().getString(KEY_SELECTED_DOMAIN, null)
        set(value) {
            prefs().edit().putString(KEY_SELECTED_DOMAIN, value).apply()
        }

    /** 是否手动指定了域名（手动选择后，启动自动测速不再覆盖）。 */
    var domainManual: Boolean
        get() = prefs().getBoolean(KEY_DOMAIN_MANUAL, false)
        set(value) {
            prefs().edit().putBoolean(KEY_DOMAIN_MANUAL, value).apply()
        }

    /** 最近一次测速结果（test_domains 返回的 results 数组 JSON 字符串）。 */
    var domainTestResults: String?
        get() = prefs().getString(KEY_DOMAIN_TEST_RESULTS, null)
        set(value) {
            prefs().edit().putString(KEY_DOMAIN_TEST_RESULTS, value).apply()
        }

    // ---- 版本更新 ----

    /**
     * 用户点击「暂不更新」时主动忽略的版本号（归一化后，如 "1.4"）。
     * 后续启动时该版本不再自动弹窗；出现更高版本时重新提示。
     * 手动「检查更新」不受此限制。
     */
    var ignoredUpdateVersion: String?
        get() = prefs().getString(KEY_IGNORED_UPDATE_VERSION, null)
        set(value) {
            prefs().edit().putString(KEY_IGNORED_UPDATE_VERSION, value).apply()
        }

    // ---- 每日访问统计 ----

    /** 获取上次访问日期（yyyy-MM-dd），用于判断今天是否已发送过访问请求。 */
    var lastVisitDate: String?
        get() = prefs().getString(KEY_LAST_VISIT_DATE, null)
        set(value) {
            prefs().edit().putString(KEY_LAST_VISIT_DATE, value).apply()
        }

    // ---- 搜索历史 ----

    /** 获取搜索历史列表（最新在前）。 */
    fun getSearchHistory(): List<String> {
        val raw = prefs().getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 添加一条搜索历史：去重后插入最前，超过上限则删除最旧的。 */
    fun addSearchHistory(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val current = getSearchHistory().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        if (current.size > SEARCH_HISTORY_MAX) {
            current.subList(SEARCH_HISTORY_MAX, current.size).clear()
        }
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        prefs().edit().putString(KEY_SEARCH_HISTORY, arr.toString()).apply()
    }

    /** 清空搜索历史。 */
    fun clearSearchHistory() {
        prefs().edit().remove(KEY_SEARCH_HISTORY).apply()
    }
}
