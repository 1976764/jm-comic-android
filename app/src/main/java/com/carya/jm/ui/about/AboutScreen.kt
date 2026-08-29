package com.carya.jm.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.random.Random

/**
 * 关于页面。
 *
 * - 上 1/3：3D 模型展示（GLB 格式，自动旋转，基于 WebView + model-viewer.js）
 * - 背景：高斯模糊多彩渐变色块缓慢漂移
 * - 下 2/3：应用介绍、项目地址、版本号、作者信息
 */


private val blobColors = listOf(
    Color(0xFF7C3AED), // 紫
    Color(0xFF8B5CF6),
    Color(0xFF6366F1), // 靛蓝
    Color(0xFF3B82F6), // 蓝
    Color(0xFF06B6D4), // 青
    Color(0xFF14B8A6), // 青绿
    Color(0xFF22C55E), // 绿
    Color(0xFFEAB308), // 黄
    Color(0xFFF97316), // 橙
    Color(0xFFEF4444), // 红
    Color(0xFFEC4899), // 粉
    Color(0xFFD946EF)  // 紫红
)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // 屏幕高度的 1/3 给 3D 模型区域（用固定高度避免 WebView 在 weight 布局中高度为 0）
    val modelHeight = (configuration.screenHeightDp / 3).dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- 动态模糊背景 ----
        AnimatedBlobBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // ---- 上 1/3：3D 模型 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(modelHeight),
                contentAlignment = Alignment.Center,
            ) {
                Model3DViewer(heightDp = configuration.screenHeightDp / 3)
            }

            // ---- 下 2/3：信息卡片 ----
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(((configuration.screenHeightDp * 2) / 3).dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // App 名称
                    Text(
                        text = "JM Comic",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 一句话介绍
                    Text(
                        text = "一款基于 Android 原生开发的 JM Comic 漫画客户端",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // 分割线
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 信息列表
                    InfoItem(
                        icon = Icons.Default.Code,
                        label = "开源项目",
                        value = "github.com/1976764/jm_c",
                        onClick = {
                            openUrl(context, "https://github.com/1976764/jm_c")
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    InfoItem(
                        icon = Icons.Default.Person,
                        label = "作者",
                        value = "Cary_A",
                        subValue = "github.com/1976764",
                        onClick = {
                            openUrl(context, "https://github.com/1976764/")
                        },
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val versionName = remember {
                        try {
                            context.packageManager
                                .getPackageInfo(context.packageName, 0)
                                .versionName ?: "1.0"
                        } catch (_: Exception) {
                            "1.0"
                        }
                    }

                    InfoItem(
                        icon = Icons.Default.Code,
                        label = "版本",
                        value = "v$versionName",
                        onClick = null,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 底部版权
                    Text(
                        text = "© 2026 Cary_A · Made with ♥",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }

        // ---- 顶部返回按钮（置于 Column 之后，确保在 WebView 上层、可点击） ----
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ---- 3D 模型查看器（WebView + model-viewer.js） ----------------------------

@Composable
private fun Model3DViewer(heightDp: Int) {
    AndroidView(
        factory = { context ->
            val density = context.resources.displayMetrics.density
            val heightPx = (heightDp * density).toInt()

            // 优先复用"我的"页预加载的 WebView（模型已加载/加载中，秒开）
            AboutModelPreloader.obtain(context, heightDp)
                ?: AboutModelPreloader.createAboutWebView(context, heightPx)
        },
        onRelease = { view ->
            // 离开关于页时销毁 WebView，避免泄漏
            AboutModelPreloader.destroyWebView(view)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---- 动态模糊背景 ---------------------------------------------------------

@Composable
private fun AnimatedBlobBackground() {
    val transition = rememberInfiniteTransition(label = "blobTransition")
    val colors = remember {
        blobColors.shuffled().take(3)
    }

    val color1 = colors[0]
    val color2 = colors[1]
    val color3 = colors[2]

    // 三个色块独立的位置动画，不同周期形成缓慢流动效果
    val offsetX1 by transition.animateFloat(
        initialValue = -80f,
        targetValue = 80f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offsetX1",
    )
    val offsetY1 by transition.animateFloat(
        initialValue = -60f,
        targetValue = 70f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offsetY1",
    )
    val offsetX2 by transition.animateFloat(
        initialValue = 100f,
        targetValue = -100f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offsetX2",
    )
    val offsetY2 by transition.animateFloat(
        initialValue = 50f,
        targetValue = -80f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offsetY2",
    )
    val offsetX3 by transition.animateFloat(
        initialValue = 40f,
        targetValue = -70f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offsetX3",
    )
    val offsetY3 by transition.animateFloat(
        initialValue = 120f,
        targetValue = -100f,
        animationSpec = infiniteRepeatable(
            animation = tween(13000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "offsetY3",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .blur(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 色块 1：紫色
        Box(
            modifier = Modifier
                .size(1020.dp)
                .offset(x = offsetX1.dp, y = offsetY1.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color1,
                            color1.copy(alpha = 0.3f),
                            Color.Transparent,
                        ),
                    ),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        )
        // 色块 2：青色
        Box(
            modifier = Modifier
                .size(880.dp)
                .offset(x = offsetX2.dp, y = offsetY2.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color2,
                            color2.copy(alpha = 0.3f),
                            Color.Transparent,
                        ),
                    ),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        )
        // 色块 3：粉色
        Box(
            modifier = Modifier
                .size(500.dp)
                .offset(x = offsetX3.dp, y = offsetY3.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            color3,
                            color3.copy(alpha = 0.3f),
                            Color.Transparent,
                        ),
                    ),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        )
    }
}

// ---- 信息条目 -------------------------------------------------------------

@Composable
private fun InfoItem(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String? = null,
    onClick: (() -> Unit)?,
) {
    val hasAction = onClick != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasAction) Modifier.clickable(onClick = onClick!!) else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Transparent,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(8.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 文字
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (hasAction) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (subValue != null) {
                    Text(
                        text = subValue,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // 箭头（可点击时显示）
            if (hasAction) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---- 工具函数 -------------------------------------------------------------

private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
