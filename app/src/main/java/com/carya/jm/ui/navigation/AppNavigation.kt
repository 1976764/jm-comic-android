package com.carya.jm.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.carya.jm.data.model.ComicItem
import com.carya.jm.data.model.Episode
import com.carya.jm.ui.auth.AuthViewModel
import com.carya.jm.ui.category.CategoryDetailScreen
import com.carya.jm.ui.category.CategoryScreen
import com.carya.jm.ui.detail.ComicDetailScreen
import com.carya.jm.ui.about.AboutScreen
import com.carya.jm.ui.home.HomeScreen
import com.carya.jm.ui.profile.ProfileScreen
import com.carya.jm.ui.profile.ProfileViewModel

// ---- Routes ---------------------------------------------------------------

/** 临时持有从列表页传给详情页的 ComicItem，避免序列化到导航参数中。 */
object ComicDetailNav {
    var pendingItem: ComicItem? = null
}

/** 临时持有从详情页传给阅读器的章节列表。 */
object ReaderNav {
    var pendingEpisodes: List<Episode>? = null
}

private object Routes {
    const val MAIN = "main"
    const val DETAIL = "detail/{albumId}"
    const val LOGIN = "login"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val DOWNLOAD = "download"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val CATEGORY_DETAIL = "category/{label}"
    const val READER = "reader/{albumId}/{photoId}"
    fun detail(albumId: String) = "detail/$albumId"
    fun categoryDetail(label: String) =
        "category/" + android.net.Uri.encode(label)
    fun reader(albumId: String, photoId: String) = "reader/$albumId/$photoId"
}

// ---- Bottom nav items -----------------------------------------------------

private data class BottomNavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem("首页", Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem("分类", Icons.Filled.Category, Icons.Outlined.Category),
    BottomNavItem("我的", Icons.Filled.Person, Icons.Outlined.Person),
)

// ---- App navigation root --------------------------------------------------

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.MAIN,
        // 前进：新页面从右侧滑入，当前页微向左移
        enterTransition = {
            slideInHorizontally(tween(250)) { fullWidth -> fullWidth }
        },
        exitTransition = {
            slideOutHorizontally(tween(250)) { fullWidth -> -fullWidth / 4 }
        },
        // 返回：当前页向右滑出 + 渐隐，滑动开始即暴露上一页并可交互；
        //       上一页同时淡入，视觉上是渐隐过渡效果
        popExitTransition = {
            slideOutHorizontally(tween(220)) { fullWidth -> fullWidth } +
                fadeOut(tween(220))
        },
        popEnterTransition = {
            fadeIn(tween(220))
        },
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                authViewModel = authViewModel,
                onComicClick = { item ->
                    ComicDetailNav.pendingItem = item
                    navController.navigate(Routes.detail(item.id))
                },
                onLoginClick = {
                    navController.navigate(Routes.LOGIN)
                },
                onSearchClick = {
                    navController.navigate(Routes.SEARCH)
                },
                onCategoryClick = { label ->
                    navController.navigate(Routes.categoryDetail(label))
                },
                onMoreFavorites = {
                    navController.navigate(Routes.FAVORITES)
                },
                onHistoryClick = {
                    navController.navigate(Routes.HISTORY)
                },
                onDownloadClick = {
                    navController.navigate(Routes.DOWNLOAD)
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                },
                onAboutClick = {
                    navController.navigate(Routes.ABOUT)
                },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("albumId") { type = NavType.StringType }
            ),
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getString("albumId").orEmpty()
            val comicItem = ComicDetailNav.pendingItem?.also {
                ComicDetailNav.pendingItem = null  // 消费后清除
            }
            ComicDetailScreen(
                albumId = albumId,
                comicItem = comicItem,
                onBack = { navController.popBackStack() },
                onReadChapter = { photoId, episodes ->
                    ReaderNav.pendingEpisodes = episodes
                    navController.navigate(Routes.reader(albumId, photoId))
                },
            )
        }
        composable(Routes.LOGIN) {
            com.carya.jm.ui.auth.LoginScreen(
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("albumId") { type = NavType.StringType },
                navArgument("photoId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getString("albumId").orEmpty()
            val photoId = backStackEntry.arguments?.getString("photoId").orEmpty()
            val episodes = ReaderNav.pendingEpisodes?.also {
                ReaderNav.pendingEpisodes = null  // 消费后清除
            }
            com.carya.jm.ui.reader.ReaderScreen(
                albumId = albumId,
                photoId = photoId,
                episodes = episodes,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH) {
            com.carya.jm.ui.search.SearchScreen(
                onComicClick = { item ->
                    ComicDetailNav.pendingItem = item
                    navController.navigate(Routes.detail(item.id))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FAVORITES) {
            com.carya.jm.ui.profile.FavoritesScreen(
                onComicClick = { item ->
                    ComicDetailNav.pendingItem = item
                    navController.navigate(Routes.detail(item.id))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HISTORY) {
            com.carya.jm.ui.profile.HistoryScreen(
                onComicClick = { item ->
                    ComicDetailNav.pendingItem = item
                    navController.navigate(Routes.detail(item.id))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DOWNLOAD) {
            com.carya.jm.ui.download.DownloadScreen(
                onBack = { navController.popBackStack() },
                onReadComic = { albumId, photoId, episodes ->
                    ReaderNav.pendingEpisodes = episodes
                    navController.navigate(Routes.reader(albumId, photoId))
                },
            )
        }
        composable(Routes.SETTINGS) {
            com.carya.jm.ui.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.CATEGORY_DETAIL,
            arguments = listOf(
                navArgument("label") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val label = backStackEntry.arguments?.getString("label").orEmpty()
            CategoryDetailScreen(
                label = label,
                onComicClick = { item ->
                    ComicDetailNav.pendingItem = item
                    navController.navigate(Routes.detail(item.id))
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

// ---- Main screen with bottom nav ------------------------------------------

@Composable
private fun MainScreen(
    authViewModel: AuthViewModel,
    onComicClick: (ComicItem) -> Unit,
    onLoginClick: () -> Unit,
    onSearchClick: () -> Unit,
    onCategoryClick: (label: String) -> Unit,
    onMoreFavorites: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val authState by authViewModel.state.collectAsState()
    val profileViewModel: ProfileViewModel = viewModel()

    // 登录后后台自动刷新收藏（用户无需切到"我的"页就会静默拉取）
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) {
            profileViewModel.refreshFavoritesInBackground()
        }
    }

    Scaffold(
        bottomBar = {
            CompactBottomBar(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (selectedTab) {
            0 -> HomeScreen(
                modifier = contentModifier,
                onComicClick = onComicClick,
                onSearchClick = onSearchClick,
            )
            1 -> CategoryScreen(
                modifier = contentModifier,
                onCategoryClick = onCategoryClick,
            )            2 -> ProfileScreen(
                modifier = contentModifier,
                authViewModel = authViewModel,
                profileViewModel = profileViewModel,
                onLoginClick = onLoginClick,
                onComicClick = onComicClick,
                onMoreFavorites = onMoreFavorites,
                onHistoryClick = onHistoryClick,
                onDownloadClick = onDownloadClick,
                onSettingsClick = onSettingsClick,
                onAboutClick = onAboutClick,
            )
        }
    }
}

// ---- Compact bottom navigation bar ----------------------------------------

/**
 * 紧凑型底部导航栏。
 *
 * 内容区高度 52dp（约为主流 App 的紧凑尺寸），加上系统导航栏的安全区域 padding。
 * 图标 22dp + 10sp 标签，选中态使用 primary 色 + 加粗。
 */
@Composable
private fun CompactBottomBar(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bottomNavItems.forEachIndexed { index, item ->
                val selected = selectedTab == index
                val tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = item.label,
                            color = tint,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
