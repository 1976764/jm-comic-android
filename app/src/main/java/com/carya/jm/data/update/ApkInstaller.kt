package com.carya.jm.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装器：负责调起 Android 系统安装流程与"允许安装未知应用"权限。
 *
 * - 统一通过 [FileProvider] 以 `content://` URI 提供安装包（兼容 Android 7.0+，
 *   避免 `file://` 导致的 FileUriExposedException）。
 * - Android 8.0+ 需要 REQUEST_INSTALL_PACKAGES 权限（已在 Manifest 声明），
 *   未授权时引导用户去「允许安装未知应用」设置页。
 */
object ApkInstaller {

    private const val APK_MIME = "application/vnd.android-package-archive"

    /** 当前是否已获得「安装未知应用」权限（Android 8.0 以下恒为 true）。 */
    fun canRequestInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * 打开本应用的「允许安装未知应用」系统设置页。
     * 用户授权后返回 App，由 [UpdateManager.onAppResumed] 自动继续安装。
     */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        try {
            val uri = Uri.parse("package:${context.packageName}")
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                uri,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // 极少数 ROM 不支持带包名的深链，回退到通用列表页
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 通过系统安装器安装 APK。
     *
     * @return 是否成功启动安装器（安装器启动后，更新弹窗由调用方关闭）。
     */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
