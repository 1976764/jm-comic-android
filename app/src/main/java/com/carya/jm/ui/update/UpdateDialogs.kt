package com.carya.jm.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.carya.jm.data.update.ReleaseInfo
import com.carya.jm.data.update.UpdateManager
import com.carya.jm.data.update.UpdateUiState
import java.util.Locale

/**
 * 更新弹窗统一挂载点：在 MainActivity 根部按状态渲染对应弹窗。
 * 状态由 [UpdateManager] 单一持有，Activity 重建不会产生重复弹窗。
 */
@Composable
fun UpdateDialogHost(state: UpdateUiState) {
    when (state) {
        UpdateUiState.Idle -> Unit

        UpdateUiState.Checking -> CheckingUpdateDialog()

        is UpdateUiState.UpToDate -> UpToDateDialog(state.currentVersion)

        is UpdateUiState.CheckFailed -> CheckFailedDialog(state.message)

        is UpdateUiState.UpdateAvailable -> UpdateAvailableDialog(
            release = state.release,
            currentVersion = state.currentVersion,
        )

        is UpdateUiState.SelectMirror -> SelectMirrorDialog(
            release = state.release,
            currentVersion = state.currentVersion,
        )

        is UpdateUiState.NoApk -> NoApkDialog(
            release = state.release,
            currentVersion = state.currentVersion,
        )

        is UpdateUiState.Downloading -> DownloadingDialog(state)

        is UpdateUiState.DownloadFailed -> DownloadFailedDialog(state)

        UpdateUiState.InstallPermissionNeeded -> InstallPermissionDialog()
    }
}

// ---- 各状态弹窗 --------------------------------------------------------------

/** 手动检查更新中。 */
@Composable
private fun CheckingUpdateDialog() {
    AlertDialog(
        onDismissRequest = { UpdateManager.dismiss() },
        title = { Text("检查更新") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.width(24.dp).height(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("正在检查更新…")
            }
        },
        confirmButton = {},
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** 已是最新版本。 */
@Composable
private fun UpToDateDialog(currentVersion: String) {
    AlertDialog(
        onDismissRequest = { UpdateManager.dismiss() },
        title = { Text("当前已是最新版本") },
        text = { Text("当前版本：v$currentVersion") },
        confirmButton = {
            TextButton(onClick = { UpdateManager.dismiss() }) { Text("确定") }
        },
    )
}

/** 检查失败（手动检查时）。 */
@Composable
private fun CheckFailedDialog(message: String) {
    AlertDialog(
        onDismissRequest = { UpdateManager.dismiss() },
        title = { Text("检查更新失败") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { UpdateManager.dismiss() }) { Text("确定") }
        },
    )
}

/** 发现新版本：版本信息 + 更新说明 + 发布时间 + 「立即更新」按钮。 */
@Composable
private fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
) {
    AlertDialog(
        onDismissRequest = { UpdateManager.postponeUpdate() },
        title = { Text("发现新版本") },
        text = {
            Column {
                Text(
                    text = "当前版本：v$currentVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "最新版本：${release.tagName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (release.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "更新内容：",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = formatNotes(release.notes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                release.publishedAt?.let { published ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "发布时间：$published",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateManager.postponeUpdate() }) { Text("暂不更新") }
        },
        confirmButton = {
            TextButton(onClick = { UpdateManager.showMirrorSelection() }) {
                Text("立即更新")
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** 选择下载镜像源：列出所有可用镜像，点击即开始下载。 */
@Composable
private fun SelectMirrorDialog(
    release: ReleaseInfo,
    currentVersion: String,
) {
    AlertDialog(
        onDismissRequest = { UpdateManager.postponeUpdate() },
        title = { Text("选择下载源") },
        text = {
            Column {
                Text(
                    text = "v$currentVersion → ${release.tagName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请选择下载源，如果速度不理想可以取消后更换：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))

                val apkUrl = release.apkUrl
                UpdateManager.MIRRORS.forEach { mirror ->
                    MirrorOption(
                        name = mirror.name,
                        description = mirror.description,
                        onClick = {
                            if (apkUrl != null) {
                                UpdateManager.startDownload(mirror.urlBuilder(apkUrl))
                            }
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateManager.postponeUpdate() }) { Text("暂不更新") }
        },
        confirmButton = {},
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

/** 镜像源选择行。 */
@Composable
private fun MirrorOption(
    name: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 有新版本但没有可下载的 APK。 */
@Composable
private fun NoApkDialog(
    release: ReleaseInfo,
    currentVersion: String,
) {
    AlertDialog(
        onDismissRequest = { UpdateManager.dismiss() },
        title = { Text("发现新版本") },
        text = {
            Column {
                Text("当前版本：v$currentVersion")
                Spacer(modifier = Modifier.height(2.dp))
                Text("最新版本：${release.tagName}")
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "当前版本存在更新，但暂时没有可用的 APK 安装包。可以稍后再试，或前往项目发布页手动下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { UpdateManager.dismiss() }) { Text("确定") }
        },
    )
}

/** 下载中：可取消（取消后回到镜像选择）。 */
@Composable
private fun DownloadingDialog(state: UpdateUiState.Downloading) {
    AlertDialog(
        onDismissRequest = { /* 下载中点击外部不关闭 */ },
        title = { Text("正在更新") },
        text = {
            Column {
                Text(
                    text = "版本：${state.version}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (state.progress >= 0 && state.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatBytes(state.downloadedBytes) +
                            if (state.totalBytes > 0) " / ${formatBytes(state.totalBytes)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (state.progress >= 0) {
                        Text(
                            text = "${state.progress}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在下载，请稍候…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateManager.cancelDownload() }) { Text("取消") }
        },
        confirmButton = {},
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    )
}

/** 下载失败：提示原因，允许重新选择下载源。 */
@Composable
private fun DownloadFailedDialog(state: UpdateUiState.DownloadFailed) {
    AlertDialog(
        onDismissRequest = { UpdateManager.dismiss() },
        title = { Text("下载失败") },
        text = {
            Column {
                Text("新版本 ${state.version} 下载失败。")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "请检查网络连接后重试，或更换下载源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateManager.dismiss() }) { Text("取消") }
        },
        confirmButton = {
            TextButton(onClick = { UpdateManager.retrySelectMirror() }) {
                Text("重新选择下载源")
            }
        },
    )
}

/** 系统禁止安装未知来源应用：引导开启权限。 */
@Composable
private fun InstallPermissionDialog() {
    AlertDialog(
        onDismissRequest = { UpdateManager.dismiss() },
        title = { Text("需要安装权限") },
        text = {
            Column {
                Text("系统禁止本应用安装未知来源的应用。")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请在打开的设置页中，允许「JM」安装未知应用（允许来自此来源的应用），返回后将继续安装新版本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { UpdateManager.dismiss() }) { Text("取消") }
        },
        confirmButton = {
            TextButton(onClick = { UpdateManager.requestInstallPermission() }) { Text("去开启") }
        },
    )
}

// ---- 工具 -------------------------------------------------------------------

/** 字节数格式化为可读大小。 */
internal fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.CHINA, "%.2f GB", bytes / (1024f * 1024f * 1024f))
        bytes >= 1024L * 1024L ->
            String.format(Locale.CHINA, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024L ->
            String.format(Locale.CHINA, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}

/**
 * 更新说明格式化：非空行前统一加「• 」前缀（Release Notes 常为 markdown 列表，
 * 已有 - / * / • 开头的行去掉原符号后统一替换）。
 */
internal fun formatNotes(notes: String): String {
    return notes
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n") { line ->
            val stripped = line.trimStart('-', '*', '•', ' ', '\t')
            "• $stripped"
        }
}
