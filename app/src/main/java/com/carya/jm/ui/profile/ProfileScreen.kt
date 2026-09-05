package com.carya.jm.ui.profile

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carya.jm.data.model.UserInfo
import com.carya.jm.ui.about.AboutModelPreloader
import com.carya.jm.ui.auth.AuthViewModel
import com.carya.jm.ui.components.BackToTopButton
import com.carya.jm.ui.components.ComicRowItem
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onLoginClick: () -> Unit,
    onComicClick: (com.carya.jm.data.model.ComicItem) -> Unit,
    onMoreFavorites: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
    profileViewModel: ProfileViewModel = viewModel(),
) {
    val authState by authViewModel.state.collectAsState()
    val profileState by profileViewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 每次进入"我的"页时同步缓存，确保取消收藏后立即生效
    LaunchedEffect(Unit) {
        profileViewModel.syncFavoritesFromCache()
    }

    // 进入"我的"页：预加载关于页 3D 模型 WebView（INVISIBLE 挂在 content 上预热）
    // 离开"我的"页：销毁预加载（若关于页在延迟窗口内取走则自动取消销毁）
    androidx.compose.runtime.DisposableEffect(Unit) {
        AboutModelPreloader.preload(context)
        onDispose {
            AboutModelPreloader.release()
        }
    }

    if (authState.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在登录...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        ProfileContent(
            isLoggedIn = authState.isLoggedIn,
            userInfo = authState.userInfo,
            favorites = profileState.favorites,
            isFavoritesLoading = profileState.isFavoritesLoading,
            favoritesError = profileState.favoritesError,
            onComicClick = onComicClick,
            onMoreFavorites = onMoreFavorites,
            onHistoryClick = onHistoryClick,
            onDownloadClick = onDownloadClick,
            onSettingsClick = onSettingsClick,
            onAboutClick = onAboutClick,
            onLoginClick = onLoginClick,
            onLogout = { authViewModel.logout() },
            onRetryFavorites = { profileViewModel.loadFavorites() },
            modifier = modifier,
        )
    }
}

// ---- Profile content (logged in & not logged in) ---------------------------

@Composable
private fun ProfileContent(
    isLoggedIn: Boolean,
    userInfo: UserInfo?,
    favorites: List<com.carya.jm.data.model.ComicItem>,
    isFavoritesLoading: Boolean,
    favoritesError: String?,
    onComicClick: (com.carya.jm.data.model.ComicItem) -> Unit,
    onMoreFavorites: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onLoginClick: () -> Unit,
    onLogout: () -> Unit,
    onRetryFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showBackToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            // Header with user info
            item {
                UserHeader(userInfo = userInfo, isLoggedIn = isLoggedIn)
            }

            // Favorites section (only when logged in)
            if (isLoggedIn) {
                item {
                    SectionTitle(text = "我的收藏")
                }

                when {
                    isFavoritesLoading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }

                    favoritesError != null -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = favoritesError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(onClick = onRetryFavorites) {
                                    Text("重试")
                                }
                            }
                        }
                    }

                    favorites.isEmpty() -> {
                        item {
                            Text(
                                text = "暂无收藏",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            )
                        }
                    }

                    else -> {
                        // 默认只展示前 3 个收藏
                        val displayItems = favorites.take(3)
                        items(displayItems, key = { it.id }) { item ->
                            ComicRowItem(
                                title = item.title,
                                author = item.author,
                                coverUrl = item.coverUrl,
                                onClick = { onComicClick(item) },
                            )
                        }
                        // 收藏超过 3 个时显示"更多收藏"按钮
                        if (favorites.size > 3) {
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 4.dp)
                                        .clickable(onClick = onMoreFavorites),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = "更多收藏 (${favorites.size})",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Settings section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SectionTitle(text = "设置")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Settings,
                    title = "设置",
                    onClick = onSettingsClick,
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.History,
                    title = "观看历史",
                    onClick = onHistoryClick,
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Download,
                    title = "下载管理",
                    onClick = onDownloadClick,
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "关于",
                    onClick = onAboutClick,
                )
            }

            // Login / Logout button
            item {
                Spacer(modifier = Modifier.height(24.dp))
                if (isLoggedIn) {
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        Text("退出登录")
                    }
                } else {
                    Button(
                        onClick = onLoginClick,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        Text("登录", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        BackToTopButton(
            visible = showBackToTop,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
private fun UserHeader(userInfo: UserInfo?, isLoggedIn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "JM",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = if (isLoggedIn) {
                    userInfo?.username?.ifBlank { "未知用户" } ?: "未知用户"
                } else {
                    "未登录"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (isLoggedIn) {
                val info = userInfo
                if (info != null) {
                    val details = buildList {
                        if (info.levelName.isNotBlank()) {
                            add("Lv.${info.level} ${info.levelName}")
                        }
                        if (info.coin > 0) {
                            add("金币: ${info.coin}")
                        }
                        if (info.albumFavorites > 0) {
                            add("收藏: ${info.albumFavorites}")
                        }
                    }
                    if (details.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = details.joinToString("  |  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
