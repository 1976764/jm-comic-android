package com.carya.jm.ui.category

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carya.jm.ui.home.ComicQuery
import com.carya.jm.ui.home.HomeScreen
import com.carya.jm.ui.home.HomeViewModel

/**
 * 分类详情页：根据分类页传来的 [label] 在 [CATEGORY_GROUPS] 中查找对应条目，
 * 复用 [HomeScreen] 的漫画网格 UI 和无限滚动加载逻辑。
 * 不显示时间/分类筛选行（筛选只在首页提供）。
 */
@Composable
fun CategoryDetailScreen(
    label: String,
    onComicClick: (com.carya.jm.data.model.ComicItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = findCategoryEntry(label)
    val query = if (entry != null) {
        ComicQuery(
            kind = entry.kind,
            category = entry.category,
            orderBy = entry.orderBy,
            time = entry.time,
            query = entry.query,
            // 标签类目走 search 接口 + main_tag=3（按标签搜索）
            mainTag = if (entry.kind == "search") 3 else 0,
        )
    } else {
        // Fallback: unknown label -> all comics, latest first
        ComicQuery(category = "0", orderBy = "mr")
    }

    val vm: HomeViewModel = viewModel(
        key = "category_detail_${entry?.kind ?: 'f'}_${entry?.query ?: entry?.category ?: label}",
        factory = HomeViewModel.factory(query, useCache = false),
    )

    HomeScreen(
        onComicClick = onComicClick,
        modifier = modifier,
        viewModel = vm,
        title = label,
        showFilters = false,
        onBack = onBack,
    )
}
