package com.carya.jm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carya.jm.data.python.PythonService
import com.carya.jm.data.settings.AppSettings
import com.carya.jm.data.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 单个域名的测速结果。 */
private data class DomainTestResult(
    val domain: String,
    val packetLossPct: Double,
    val avgLatencyMs: Double?,
)

/** 解析 test_domains 返回的 results 数组（AppSettings.domainTestResults）。 */
private fun parseDomainResults(json: String?): List<DomainTestResult> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DomainTestResult(
                domain = o.optString("domain"),
                packetLossPct = o.optDouble("packet_loss_pct", 100.0),
                avgLatencyMs = if (o.isNull("avg_latency_ms")) null else o.optDouble("avg_latency_ms"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    var storeZip by remember { mutableStateOf(AppSettings.storeZipToDownloads) }
    var selectedDomain by remember { mutableStateOf(AppSettings.selectedDomain) }
    var domainManual by remember { mutableStateOf(AppSettings.domainManual) }
    var domainResults by remember { mutableStateOf(parseDomainResults(AppSettings.domainTestResults)) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun runSpeedTest(applyBest: Boolean) {
        if (testing) return
        testing = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PythonService().testDomains(PythonService.DOMAIN_CANDIDATES)
                }
                if (result.optBoolean("ok", false)) {
                    val resultsJson = result.optJSONArray("results")?.toString()
                    AppSettings.domainTestResults = resultsJson
                    domainResults = parseDomainResults(resultsJson)
                    if (applyBest) {
                        val best = result.optJSONObject("best")
                        if (best != null) {
                            AppSettings.selectedDomain = best.optString("domain")
                            selectedDomain = best.optString("domain")
                            domainManual = false
                        }
                    } else {
                        selectedDomain = AppSettings.selectedDomain
                        domainManual = AppSettings.domainManual
                    }
                }
            } catch (_: Exception) {
            } finally {
                testing = false
            }
        }
    }

    fun selectDomain(domain: String) {
        AppSettings.selectedDomain = domain
        AppSettings.domainManual = true
        selectedDomain = domain
        domainManual = true
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    PythonService().setDomain(domain)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun selectAuto() {
        AppSettings.domainManual = false
        runSpeedTest(applyBest = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item { SectionHeader("网络域名") }

            item {
                Text(
                    text = "当前使用：${selectedDomain ?: "未选择（jmcomic 默认域名）"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { runSpeedTest(applyBest = false) },
                        enabled = !testing,
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("测速中…")
                        } else {
                            Text("重新测速")
                        }
                    }
                }
            }

            item {
                DomainRow(
                    title = "自动选择（推荐）",
                    subtitle = if (domainManual) "当前为手动指定，点击恢复自动测速" else null,
                    latencyText = null,
                    lossText = null,
                    selected = !domainManual && selectedDomain != null,
                    onClick = { selectAuto() },
                )
            }

            if (domainResults.isEmpty()) {
                item {
                    Text(
                        text = "暂无测速数据，点击「重新测速」获取各域名延迟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            } else {
                domainResults.forEach { r ->
                    item(key = r.domain) {
                        DomainRow(
                            title = r.domain,
                            subtitle = null,
                            latencyText = r.avgLatencyMs?.let { "${it.toInt()} ms" },
                            lossText = "${r.packetLossPct}% 丢包",
                            selected = domainManual && selectedDomain == r.domain,
                            onClick = { selectDomain(r.domain) },
                        )
                    }
                }
            }

            item { SectionHeader("下载") }
            item {
                SwitchSettingItem(
                    title = "在下载目录保存副本",
                    subtitle = "下载完成后，在设备默认下载目录（Downloads/JM）额外保存一份 zip 文件。\n关闭时仅保存在应用内部，供离线阅读使用。",
                    checked = storeZip,
                    onCheckedChange = { checked ->
                        storeZip = checked
                        AppSettings.storeZipToDownloads = checked
                    },
                )
            }

            item { SectionHeader("关于") }
            item {
                val currentVersion = remember {
                    try {
                        "v" + (context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: "1.0")
                    } catch (_: Exception) {
                        "v1.0"
                    }
                }
                ActionSettingItem(
                    title = "检查更新",
                    subtitle = "当前版本 $currentVersion，点击检查 GitHub 上的最新版本",
                    onClick = {
                        UpdateManager.checkForUpdate()
                    },
                )
            }
        }
    }
}

@Composable
private fun DomainRow(
    title: String,
    subtitle: String?,
    latencyText: String?,
    lossText: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选中指示：圆形背景 + 勾选图标
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                },
                modifier = Modifier.size(20.dp),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (latencyText != null) {
                Text(
                    text = latencyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = lossText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchSettingItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                    checkedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    checkedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
    }
}

/** 可点击的设置项（右侧带箭头），样式与 SwitchSettingItem 一致。 */
@Composable
private fun ActionSettingItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SystemUpdateAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
