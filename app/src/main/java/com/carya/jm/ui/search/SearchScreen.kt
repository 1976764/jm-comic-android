package com.carya.jm.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carya.jm.ui.home.ComicQuery
import com.carya.jm.ui.home.HomeScreen
import com.carya.jm.ui.home.HomeViewModel

/**
 * 搜索页：复用 [HomeScreen] 的网格 / 无限滚动 / 回到顶部逻辑，
 * 顶部以搜索栏代替标题行，支持按关键词（main_tag=0）搜索。
 * 初始不加载任何数据，输入关键词提交后才触发搜索。
 */
@Composable
fun SearchScreen(
    onComicClick: (com.carya.jm.data.model.ComicItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: HomeViewModel = viewModel(
        key = "keyword_search",
        factory = HomeViewModel.factory(
            query = ComicQuery(kind = "search"),
            initialLoad = false,
        ),
    )

    HomeScreen(
        onComicClick = onComicClick,
        modifier = modifier,
        viewModel = vm,
        title = "搜索",
        showFilters = false,
        onBack = onBack,
        searchMode = true,
    )
}
