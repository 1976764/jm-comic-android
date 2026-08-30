package com.carya.jm.data.settings

import android.content.Context
import android.content.SharedPreferences

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
}
