package com.carya.jm.data.update

import android.content.Context
import com.carya.jm.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** 更新功能的 UI 状态（由 [UpdateManager] 单一持有，UI 只读渲染）。 */
sealed interface UpdateUiState {

    /** 无事发生（弹窗全部关闭） */
    data object Idle : UpdateUiState

    /** 手动检查中（自动检查静默，不进入此状态） */
    data object Checking : UpdateUiState

    /** 当前已是最新版本（仅手动检查展示） */
    data class UpToDate(val currentVersion: String) : UpdateUiState

    /** 检查失败（仅手动检查展示；自动检查静默失败） */
    data class CheckFailed(val message: String) : UpdateUiState

    /** 发现新版本 */
    data class UpdateAvailable(
        val release: ReleaseInfo,
        val currentVersion: String,
    ) : UpdateUiState

    /** 选择下载镜像源（用户点击「立即更新」后进入） */
    data class SelectMirror(
        val release: ReleaseInfo,
        val currentVersion: String,
    ) : UpdateUiState

    /** 有新版本但 Release 没有可下载的 APK */
    data class NoApk(
        val release: ReleaseInfo,
        val currentVersion: String,
    ) : UpdateUiState

    /** 正在下载（弹窗不可关闭） */
    data class Downloading(
        val version: String,
        /** 下载百分比；总大小未知时为 -1 */
        val progress: Int,
        val downloadedBytes: Long,
        /** 总字节；未知为 -1 */
        val totalBytes: Long,
    ) : UpdateUiState

    /** 下载失败（允许重试） */
    data class DownloadFailed(
        val version: String,
        val message: String,
    ) : UpdateUiState

    /** 系统禁止安装未知来源应用，引导用户开启权限 */
    data object InstallPermissionNeeded : UpdateUiState
}

/**
 * 应用版本更新管理器（进程内单例）。
 *
 * 职责：
 * - 版本检查（自动 / 手动，进程生命周期内自动检查仅一次，请求去重复用）
 * - 「暂不更新」忽略版本记录（出现更高版本时重新提示）
 * - APK 下载状态管理（防重复下载）
 * - 下载完成后校验并调起系统安装器
 * - 更新残留文件清理
 *
 * 生命周期：[MainActivity] 调用 [init] 后常驻；Activity 重建不影响（单例 + 状态在
 * StateFlow 中，弹窗只挂载一份，不会重复触发检查或下载）。
 */
object UpdateManager {

    /** 下载镜像源定义。 */
    data class DownloadMirror(
        val name: String,
        val description: String,
        val urlBuilder: (String) -> String,
    )

    /** 可用的下载镜像源列表（UI 渲染 + 下载用）。 */
    val MIRRORS = listOf(
        DownloadMirror(
            name = "GitHub 直连",
            description = "原始链接，可能较慢",
        ) { it },
        DownloadMirror(
            name = "GitHubCDN 加速",
            description = "download.githubcdn.com",
        ) { url -> "https://download.githubcdn.com/?url=" + URLEncoder.encode(url, "UTF-8") },
        DownloadMirror(
            name = "Yushu加速",
            description = "yushu.de5.net",
        ) { url -> "https://yushu.de5.net/" + URLEncoder.encode(url, "UTF-8") },
        DownloadMirror(
            name = "gh-proxy 加速",
            description = "v4.gh-proxy.org",
        ) { url -> "https://v4.gh-proxy.org/" + URLEncoder.encode(url, "UTF-8") },
    )

    /** 启动后延迟多久做自动检查（避开冷启动高峰，与域名测速等后台任务错开）。 */
    private const val AUTO_CHECK_DELAY_MS = 4000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var appContext: Context? = null
    private var currentVersion: String = ""
    private var downloader: ApkDownloader? = null

    /** 进程生命周期内自动检查只允许发起一次（Activity 重建不重复触发）。 */
    @Volatile
    private var autoCheckLaunched = false

    /** 进行中的检查请求（自动 / 手动共享，避免重复请求 GitHub）。 */
    @Volatile
    private var fetchInFlight: Deferred<ReleaseInfo>? = null

    /** 最近一次发现的新版本（供「立即更新」/ 下载失败重试使用）。 */
    private var currentRelease: ReleaseInfo? = null

    /** 已下载完成、等待安装（或等待权限授权后继续安装）的 APK 文件。 */
    private var pendingApkFile: File? = null

    /** 进行中的下载协程（用于取消下载）。 */
    private var downloadJob: Job? = null

    /** 用户主动取消下载标志（区分取消与异常失败）。 */
    @Volatile
    private var downloadCancelledByUser = false

    // ---- 生命周期 ----------------------------------------------------------

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        downloader = ApkDownloader(context)
        currentVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    private fun ctx(): Context =
        appContext ?: throw IllegalStateException("UpdateManager 未初始化")

    /**
     * 清理更新残留文件（只动 `cache/update/` 专用目录，不碰其他缓存）。
     * 下载临时 `.download` 文件、旧版本 APK 一并删除。
     * 在 MainActivity 启动时后台调用。
     */
    fun cleanupUpdateFiles() {
        val downloader = downloader ?: return
        scope.launch(Dispatchers.IO) {
            try {
                downloader.updateDir().listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 启动时自动检查（进程内仅一次）。
     * 静默执行：失败不打扰用户；有新版本且未被忽略时才弹窗。
     */
    fun maybeAutoCheck() {
        if (autoCheckLaunched) return
        autoCheckLaunched = true

        scope.launch {
            delay(AUTO_CHECK_DELAY_MS)
            runCheck(manual = false)
        }
    }

    /** 手动检查（设置页「检查更新」按钮；忽略版本也会正常提示）。 */
    fun checkForUpdate() {
        scope.launch {
            runCheck(manual = true)
        }
    }

    /**
     * Activity onResume 回调：用户从「允许安装未知应用」设置页返回后，
     * 若权限已开启且 APK 已就绪，自动继续调起安装器。
     */
    fun onAppResumed() {
        val file = pendingApkFile ?: return
        val state = _uiState.value
        if (state is UpdateUiState.InstallPermissionNeeded &&
            file.exists() &&
            ApkInstaller.canRequestInstall(ctx())
        ) {
            launchInstall(file)
        }
    }

    // ---- 用户操作（由弹窗回调） --------------------------------------------

    /** 「暂不更新」：记录忽略的版本号，后续该版本不再自动提示。 */
    fun postponeUpdate() {
        currentRelease?.let { release ->
            AppSettings.ignoredUpdateVersion = release.version
        }
        _uiState.value = UpdateUiState.Idle
    }

    /** 「立即更新」：进入镜像源选择界面。 */
    fun showMirrorSelection() {
        val release = currentRelease ?: return
        _uiState.value = UpdateUiState.SelectMirror(release, currentVersion)
    }

    /**
     * 选择镜像后开始下载 APK。
     * [downloadUrl] 为 null 时使用 Release 原始链接，非 null 时使用镜像 URL。
     * 下载进行中重复调用会被忽略（防重复下载）。
     */
    fun startDownload(downloadUrl: String? = null) {
        val release = currentRelease ?: return
        val url = downloadUrl ?: release.apkUrl ?: return
        if (_uiState.value is UpdateUiState.Downloading) return
        val downloader = downloader ?: return
        downloadCancelledByUser = false

        downloadJob = scope.launch {
            _uiState.value = UpdateUiState.Downloading(release.version, 0, 0, release.apkSize)

            try {
                val apkFile = downloader.download(url, release.version) { downloaded, total ->
                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                    _uiState.value = UpdateUiState.Downloading(release.version, pct, downloaded, total)
                }

                // APK 合法性校验：能被 PackageManager 解析且包名一致
                val pkgInfo = try {
                    ctx().packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                } catch (_: Exception) {
                    null
                }
                if (pkgInfo == null || pkgInfo.packageName != ctx().packageName) {
                    apkFile.delete()
                    throw IOException("安装包校验失败")
                }

                pendingApkFile = apkFile

                if (ApkInstaller.canRequestInstall(ctx())) {
                    launchInstall(apkFile)
                } else {
                    // 系统禁止安装未知来源应用，引导开启权限
                    _uiState.value = UpdateUiState.InstallPermissionNeeded
                }
            } catch (e: Exception) {
                // 用户主动取消：状态已由 cancelDownload() 设为 SelectMirror，不覆盖
                if (downloadCancelledByUser) return@launch
                _uiState.value = UpdateUiState.DownloadFailed(
                    version = release.version,
                    message = e.message ?: "下载失败，请稍后重试",
                )
            }
        }
    }

    /** 下载中用户取消：停止下载并回到镜像选择界面。 */
    fun cancelDownload() {
        downloadCancelledByUser = true
        downloadJob?.cancel()
        downloadJob = null
        val release = currentRelease ?: return
        _uiState.value = UpdateUiState.SelectMirror(release, currentVersion)
    }

    /** 「去开启」安装权限。 */
    fun requestInstallPermission() {
        ApkInstaller.requestInstallPermission(ctx())
    }

    /** 关闭当前弹窗（仅用于可关闭的结果类弹窗）。 */
    fun dismiss() {
        if (_uiState.value !is UpdateUiState.Downloading) {
            _uiState.value = UpdateUiState.Idle
        }
    }

    /** 下载失败后重新选择下载源：回到镜像选择界面。 */
    fun retrySelectMirror() {
        val release = currentRelease ?: return
        _uiState.value = UpdateUiState.SelectMirror(release, currentVersion)
    }

    // ---- 内部流程 ----------------------------------------------------------

    private suspend fun runCheck(manual: Boolean) {
        if (manual) {
            _uiState.value = UpdateUiState.Checking
        }

        try {
            val release = fetchLatest()

            if (VersionUtils.isNewer(release.version, currentVersion)) {
                currentRelease = release
                val ignored = AppSettings.ignoredUpdateVersion
                // 手动检查总是提示；自动检查时，用户忽略过的版本不再弹窗
                if (manual || ignored == null || VersionUtils.isNewer(release.version, ignored)) {
                    _uiState.value = if (release.apkUrl != null) {
                        UpdateUiState.UpdateAvailable(release, currentVersion)
                    } else {
                        UpdateUiState.NoApk(release, currentVersion)
                    }
                }
            } else {
                if (manual) {
                    _uiState.value = UpdateUiState.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            // 自动检查静默失败；手动检查提示网络问题
            if (manual) {
                _uiState.value = UpdateUiState.CheckFailed("检查更新失败，请检查网络连接")
            }
        } finally {
            // 安全网：手动检查中用户关闭了弹窗且无结果时复位
            if (_uiState.value is UpdateUiState.Checking) {
                _uiState.value = UpdateUiState.Idle
            }
        }
    }

    /**
     * 获取最新 Release。自动 / 手动检查共享同一个进行中的请求
     * （自动检查进行中时用户点「检查更新」会等待复用同一结果，不重复发请求）。
     */
    private suspend fun fetchLatest(): ReleaseInfo {
        fetchInFlight?.let { inFlight ->
            if (inFlight.isActive) return inFlight.await()
        }

        val deferred = scope.async(Dispatchers.IO) {
            UpdateChecker.fetchLatestRelease()
        }
        fetchInFlight = deferred
        deferred.invokeOnCompletion {
            if (fetchInFlight === deferred) {
                fetchInFlight = null
            }
        }
        return deferred.await()
    }

    private fun launchInstall(file: File) {
        val installed = ApkInstaller.install(ctx(), file)
        // 安装器已启动（或启动失败）：更新弹窗不再停留在应用界面
        _uiState.value = if (installed) {
            UpdateUiState.Idle
        } else {
            UpdateUiState.DownloadFailed(
                version = currentRelease?.version ?: "",
                message = "无法启动系统安装器",
            )
        }
    }
}
