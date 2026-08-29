package com.carya.jm.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.carya.jm.data.download.DownloadProgress
import com.carya.jm.data.model.ComicDetail
import com.carya.jm.data.model.ComicItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    onReadChapter: (photoId: String, episodes: List<com.carya.jm.data.model.Episode>) -> Unit,
    comicItem: ComicItem? = null,
) {
    val context = LocalContext.current
    val viewModel: ComicDetailViewModel = viewModel(
        key = "detail_$albumId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext as android.app.Application
                return ComicDetailViewModel(app, albumId, comicItem) as T
            }
        },
    )
    val state by viewModel.state.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isFavorited by viewModel.isFavorited.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("漫画详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    val detail = state.detail
                    // 收藏/取消收藏按钮（已收藏=实心红心，点击取消；未收藏=空心，点击收藏）
                    if (detail != null) {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (isFavorited) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = if (isFavorited) "取消收藏" else "收藏",
                                tint = if (isFavorited) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
                    }
                    if (detail != null && detail.episodes.isNotEmpty()) {
                        if (downloadProgress.isDownloading) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.downloadAlbum() },
                            ) {
                                Icon(
                                    imageVector = if (downloadProgress.completed || viewModel.isDownloaded())
                                        Icons.Default.Check else Icons.Default.Download,
                                    contentDescription = if (downloadProgress.completed || viewModel.isDownloaded())
                                        "已下载" else "下载",
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val detail = state.detail

        when {
            // 有详情数据（来自列表页或缓存）→ 立即展示，后台补全缺失字段
            detail != null -> {
                DetailContent(
                    detail = detail,
                    onReadChapter = onReadChapter,
                    isFetchingDetail = state.isFetchingDetail,
                    onRetry = { viewModel.loadDetail() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            // 无详情数据但正在加载 → 全屏 loading
            state.isFetchingDetail -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // 加载失败 → 显示错误 + 重试
            state.error != null -> {
                val errorMsg = state.error!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadDetail() }) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContent(
    detail: ComicDetail,
    onReadChapter: (photoId: String, episodes: List<com.carya.jm.data.model.Episode>) -> Unit,
    isFetchingDetail: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
    ) {
        // Cover + info section
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                // Cover image
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (detail.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(detail.coverUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = detail.title,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (detail.author.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "作者: ${detail.author}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // JM号：点击复制
                    if (detail.id.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        JmIdChip(jmId = detail.id)
                    }

                    if (detail.pageCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "頁數: ${detail.pageCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (detail.episodes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "章節: ${detail.episodes.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Tags
        if (detail.tags.isNotEmpty()) {
            item(key = "tags") {
                LazyTagRow(tags = detail.tags)
            }
        }

        // "开始阅读" button
        if (detail.episodes.isNotEmpty()) {
            item(key = "readButton") {
                Button(
                    onClick = { onReadChapter(detail.episodes.first().id, detail.episodes) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("开始阅读")
                }
            }
        }

        // Description
        if (detail.description.isNotBlank()) {
            item(key = "description") {
                SectionTitle(text = "簡介", modifier = Modifier.padding(start = 20.dp, top = 16.dp))
                Text(
                    text = detail.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        // Episode list — lazy: only visible items are composed
        if (detail.episodes.isNotEmpty()) {
            item(key = "episodeHeader") {
                SectionTitle(
                    text = "章節列表 (${detail.episodes.size})",
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                )
            }
            items(
                items = detail.episodes,
                key = { it.id },
            ) { ep ->
                EpisodeItem(
                    ep = ep,
                    onClick = { onReadChapter(ep.id, detail.episodes) },
                )
            }
        }

        // 后台加载缺失字段时的提示
        if (isFetchingDetail && (detail.episodes.isEmpty() || detail.description.isBlank())) {
            item(key = "loading") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "加载详情中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "bottomSpacer") {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EpisodeItem(
    ep: com.carya.jm.data.model.Episode,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ep.index,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(32.dp),
            )
            Text(
                text = ep.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** JM号标签：显示漫画 JMid，点击自动复制到剪贴板并弹出提示。 */
@Composable
private fun JmIdChip(jmId: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                clipboard.setText(AnnotatedString(jmId))
                android.widget.Toast.makeText(
                    context,
                    "已复制 JM号: $jmId",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "JM$jmId",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "复制JM号",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun LazyTagRow(tags: List<String>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags) { tag ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
