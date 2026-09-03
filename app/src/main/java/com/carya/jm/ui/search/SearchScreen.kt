package com.carya.jm.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carya.jm.data.settings.AppSettings
import com.carya.jm.ui.home.ComicQuery
import com.carya.jm.ui.home.HomeScreen
import com.carya.jm.ui.home.HomeViewModel

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

    var searchHistory by remember { mutableStateOf(AppSettings.getSearchHistory()) }

    HomeScreen(
        onComicClick = onComicClick,
        modifier = modifier,
        viewModel = vm,
        title = "搜索",
        showFilters = false,
        onBack = onBack,
        searchMode = true,
        searchHistory = searchHistory,
        onClearHistory = {
            AppSettings.clearSearchHistory()
            searchHistory = emptyList()
        },
        onSearchSubmit = { keyword ->
            AppSettings.addSearchHistory(keyword)
            searchHistory = AppSettings.getSearchHistory()
        },
    )
}
