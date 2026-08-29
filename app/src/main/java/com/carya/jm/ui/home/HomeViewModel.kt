package com.carya.jm.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.cache.CacheManager
import com.carya.jm.data.model.ComicItem
import com.carya.jm.data.model.parseComicItems
import com.carya.jm.data.python.PythonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filter option for the home page. */
data class FilterOption(val key: String, val label: String)

/** Time filter options. */
val TIME_FILTERS = listOf(
    FilterOption("a", "全部"),
    FilterOption("t", "今日"),
    FilterOption("w", "本周"),
    FilterOption("m", "本月"),
)

/** Category filter options (home page chips). */
val CATEGORY_FILTERS = listOf(
    FilterOption("0", "全部"),
    FilterOption("doujin", "同人"),
    FilterOption("single", "单行本"),
    FilterOption("short", "短篇"),
    FilterOption("hanman", "韩漫"),
    FilterOption("meiman", "美漫"),
    FilterOption("another", "其他"),
)

/**
 * Describes how a comic list page loads its data.
 *
 * @param kind     "filter" -> categories_filter; "search" -> search 接口
 * @param category categories_filter 的分类参数 ("0"=全部, "doujin", "single", ...)
 * @param orderBy  排序: "mr"=最新, "mv"=最多浏览, "tf"=最多爱心
 * @param time     时间段: "a"=全部, "t"=今日, "w"=本周, "m"=本月
 * @param query    kind="search" 时的搜索词（关键词或标签名）
 * @param mainTag  0=按关键词搜索, 3=按标签搜索
 */
data class ComicQuery(
    val kind: String = "filter",
    val category: String = "0",
    val orderBy: String = "mv",
    val time: String = "a",
    val query: String = "",
    val mainTag: Int = 0,
)

/** UI state for the home page. */
data class HomeUiState(
    val items: List<ComicItem> = emptyList(),
    val isLoading: Boolean = false,        // initial / refresh loading
    val isLoadingMore: Boolean = false,    // infinite scroll loading
    val error: String? = null,
    val time: String = "a",
    val category: String = "0",
    val page: Int = 1,
    val hasMore: Boolean = true,           // whether more pages are available
    val hasSearched: Boolean = false,      // 搜索页：是否已执行过一次搜索
)

class HomeViewModel(
    initialQuery: ComicQuery = ComicQuery(),
    private val initialLoad: Boolean = true,
    private val useCache: Boolean = true,
) : ViewModel() {

    private val python = PythonService()

    /** 当前查询条件（搜索页可通过 [searchByKeyword] 动态修改）。 */
    private var query: ComicQuery = initialQuery

    private val _state = MutableStateFlow(
        HomeUiState(
            isLoading = initialLoad,
            category = initialQuery.category,
            time = initialQuery.time,
            hasMore = initialLoad,
        )
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    companion object {
        /** Factory for creating a HomeViewModel bound to a specific query. */
        fun factory(
            query: ComicQuery,
            initialLoad: Boolean = true,
            useCache: Boolean = true,
        ): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(
                    modelClass: Class<T>,
                ): T = HomeViewModel(query, initialLoad, useCache) as T
            }
    }

    init {
        if (initialLoad) {
            // 仅首页默认查询（全部时间+全部分类+最多浏览）读缓存。
            // 分类详情页（useCache=false）每次都从网络获取。
            if (useCache && query.kind == "filter" && query.time == "a" &&
                query.category == "0" && query.orderBy == "mv"
            ) {
                val cached = CacheManager.get().getCachedHomeComics("a", "0", "mv")
                if (cached != null && cached.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        items = cached,
                        isLoading = false,
                        hasMore = true,
                    )
                } else {
                    loadComics()
                }
            } else {
                loadComics()
            }
        }
    }

    /** Fetch a single page according to the query kind. */
    private suspend fun fetchPage(page: Int, time: String, category: String) =
        withContext(Dispatchers.IO) {
            if (query.kind == "search") {
                python.search(
                    query = query.query,
                    page = page,
                    orderBy = query.orderBy,
                    time = query.time,
                    mainTag = query.mainTag,
                )
            } else {
                python.categoriesFilter(
                    page = page,
                    time = time,
                    category = category,
                    orderBy = query.orderBy,
                )
            }
        }

    /** Initial load or full refresh — resets to page 1 and clears existing items. */
    fun loadComics() {
        _state.value = _state.value.copy(
            isLoading = true,
            error = null,
            items = emptyList(),
            page = 1,
            hasMore = true,
        )

        viewModelScope.launch {
            try {
                val s = _state.value
                val result = fetchPage(1, s.time, s.category)
                if (result.optBoolean("ok", false)) {
                    val items = parseComicItems(result).distinctBy { it.id }
                // 仅首页缓存第一页结果，分类详情页不写缓存
                if (useCache && query.kind == "filter") {
                    CacheManager.get().cacheHomeComics(items, s.time, s.category, query.orderBy)
                }
                _state.value = _state.value.copy(
                    items = items,
                    isLoading = false,
                    // 车号直达（单本包装）结果没有下一页
                    hasMore = items.isNotEmpty() && !result.optBoolean("single", false),
                )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = result.optString("error", "加载失败"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "网络错误",
                )
            }
        }
    }

    /** Load the next page and append to existing items (infinite scroll). */
    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasMore || current.isLoading) return

        _state.value = current.copy(isLoadingMore = true)

        viewModelScope.launch {
            try {
                val nextPage = current.page + 1
                val result = fetchPage(nextPage, current.time, current.category)
                if (result.optBoolean("ok", false)) {
                    // 按id去重：翻页可能返回重复条目（例如车号直达结果恒为同一本子），
                    // 重复 key 会导致 LazyVerticalGrid 崩溃。
                    val existingIds = current.items.map { it.id }.toSet()
                    val newItems = parseComicItems(result)
                        .distinctBy { it.id }
                        .filter { it.id !in existingIds }
                    _state.value = current.copy(
                        items = current.items + newItems,
                        page = nextPage,
                        isLoadingMore = false,
                        // 全部是重复条目说明没有新内容了，停止翻页
                        hasMore = newItems.isNotEmpty() && !result.optBoolean("single", false),
                    )
                } else {
                    _state.value = current.copy(
                        isLoadingMore = false,
                        hasMore = false,
                    )
                }
            } catch (e: Exception) {
                _state.value = current.copy(
                    isLoadingMore = false,
                )
            }
        }
    }

    /** Refresh — same as loadComics, reloads from page 1. */
    fun refresh() {
        loadComics()
    }

    /** 搜索页：按关键词搜索（main_tag=0），替换当前查询并从第 1 页重新加载。 */
    fun searchByKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        query = ComicQuery(kind = "search", query = trimmed, mainTag = 0)
        _state.value = _state.value.copy(hasSearched = true)
        loadComics()
    }

    /** Change the time filter and reload from page 1. */
    fun setTime(time: String) {
        if (query.kind == "search") return
        if (_state.value.time == time) return
        _state.value = _state.value.copy(time = time)
        loadComics()
    }

    /** Change the category filter and reload from page 1. */
    fun setCategory(category: String) {
        if (query.kind == "search") return
        if (_state.value.category == category) return
        _state.value = _state.value.copy(category = category)
        loadComics()
    }
}
