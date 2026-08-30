package com.carya.jm

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.android.AndroidPlatform
import com.carya.jm.data.cache.CacheManager
import com.carya.jm.data.download.DownloadManager
import com.carya.jm.data.python.PythonService
import com.carya.jm.data.settings.AppSettings
import com.carya.jm.data.update.UpdateManager
import com.carya.jm.ui.navigation.AppNavigation
import com.carya.jm.ui.theme.JMTheme
import com.carya.jm.ui.update.UpdateDialogHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 自动请求当前设备支持的最高刷新率
        setHighestRefreshRate()

        PythonService.initialize(AndroidPlatform(this))
        CacheManager.init(this)
        DownloadManager.init(this)
        AppSettings.init(this)
        UpdateManager.init(this)

        // 后台清理上次阅读的漫画图片和元数据缓存，不阻塞 UI
        lifecycleScope.launch(Dispatchers.IO) {
            CacheManager.get().clearReadingCache()
        }

        // 后台清理上次更新残留的 APK / 临时下载文件（只动 cache/update/ 专用目录）
        UpdateManager.cleanupUpdateFiles()

        // 启动自动检查更新（进程内仅一次；失败静默，不打扰用户）
        UpdateManager.maybeAutoCheck()

        // 后台测速
        lifecycleScope.launch(Dispatchers.IO) {
            delay(1500)

            try {
                val result = PythonService()
                    .testDomains(PythonService.DOMAIN_CANDIDATES)

                if (result.optBoolean("ok", false)) {

                    // 保存测速结果供设置页展示
                    AppSettings.domainTestResults =
                        result.optJSONArray("results")?.toString()

                    // 手动选择过域名时不覆盖；
                    // 否则自动使用最优域名
                    if (!AppSettings.domainManual) {
                        val best = result.optJSONObject("best")

                        if (best != null) {
                            AppSettings.selectedDomain =
                                best.optString("domain")
                        }
                    }
                }

            } catch (_: Exception) {
                // 测速失败不影响使用
            }
        }

        enableEdgeToEdge()

        setContent {
            JMTheme {
                AppNavigation()

                // 更新弹窗统一挂载在根部：状态由 UpdateManager 单一持有，
                // Activity 重建 / 屏幕旋转不会产生重复弹窗或重复下载
                val updateState by UpdateManager.uiState.collectAsState()
                UpdateDialogHost(updateState)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // 用户从「允许安装未知应用」设置页返回且权限已开启时，继续完成安装
        UpdateManager.onAppResumed()
    }

    /**
     * 请求当前设备支持的最高刷新率。
     *
     * 例如：
     * 60Hz 设备 → 60Hz
     * 90Hz 设备 → 90Hz
     * 120Hz 设备 → 120Hz
     * 144Hz 设备 → 144Hz
     */
    private fun setHighestRefreshRate() {

        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        val highestMode = display
            ?.supportedModes
            ?.maxByOrNull { it.refreshRate }
            ?: return

        window.attributes = window.attributes.apply {

            // 优先使用最高刷新率对应的 Display Mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                preferredDisplayModeId = highestMode.modeId
            }

            // 同时设置目标刷新率
            preferredRefreshRate = highestMode.refreshRate
        }
    }
}