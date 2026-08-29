package com.carya.jm.ui.profile

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.cache.CacheManager
import com.carya.jm.data.model.ComicItem
import com.carya.jm.data.model.parseComicItems
import com.carya.jm.data.python.PythonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state for the full favorites page. */
data class FavoritesUiState(
    val items: List<ComicItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val page: Int = 1,
    val hasMore: Boolean = true,
    val total: Int = 0,
)

/**
 * 更多收藏页 ViewModel。
 *
 * 与 ProfileViewModel 一致：收藏列表需要登录鉴权。若 jmcomic 返回
 * "请先登入会员" 等鉴权错误（保存的 cookies 已失效），会自动用
 * SharedPreferences 里保存的凭据**静默重登录**一次并重试请求。
 */
class FavoritesViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val python = PythonService()
    private val prefs = application.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(FavoritesUiState(isLoading = true, hasMore = true))
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private var retryJob: Job? = null

    init {
        loadFavorites()
    }

    /** Initial load or full refresh — resets to page 1. */
    fun loadFavorites() {
        _state.value = FavoritesUiState(isLoading = true, hasMore = true)
        viewModelScope.launch { requestPage(1, reset = true) }
    }

    private suspend fun requestPage(page: Int, reset: Boolean, retriedAuth: Boolean = false) {
        try {
            val result = withContext(Dispatchers.IO) {
                python.favoriteFolder(page = page)
            }
            if (result.optBoolean("ok", false)) {
                if (reset) {
                    val items = parseComicItems(result).distinctBy { it.id }
                    val total = result.optInt("total", items.size)
                    // 首页数据同步写收藏缓存，让"我的"页与详情页的收藏状态保持一致
                    CacheManager.get().cacheFavorites(items)
                    _state.value = FavoritesUiState(
                        items = items,
                        isLoading = false,
                        hasMore = items.isNotEmpty() && items.size < total,
                        page = 1,
                        total = total,
                    )
                } else {
                    val current = _state.value
                    val existingIds = current.items.map { it.id }.toSet()
                    val newItems = parseComicItems(result)
                        .distinctBy { it.id }
                        .filter { it.id !in existingIds }
                    val total = result.optInt("total", current.total)
                    _state.value = current.copy(
                        items = current.items + newItems,
                        page = page,
                        isLoadingMore = false,
                        hasMore = newItems.isNotEmpty() && (current.items.size + newItems.size) < total,
                        total = total,
                    )
                }
            } else {
                val err = result.optString("error", "加载收藏失败")
                // 鉴权失败（保存的 cookies 失效）→ 静默重登录后重试
                if (!retriedAuth && isAuthError(err)) {
                    silentReloginAndRetryThenRun {
                        requestPage(page, reset, retriedAuth = true)
                    }
                } else if (reset) {
                    _state.value = _state.value.copy(isLoading = false, error = err)
                } else {
                    _state.value = _state.value.copy(isLoadingMore = false, hasMore = false)
                }
            }
        } catch (e: Exception) {
            if (reset) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "网络错误")
            } else {
                _state.value = _state.value.copy(isLoadingMore = false)
            }
        }
    }

    /** Load the next page and append (infinite scroll). */
    fun loadMore() {
        val current = _state.value
        if (current.isLoadingMore || !current.hasMore || current.isLoading) return

        _state.value = current.copy(isLoadingMore = true)
        viewModelScope.launch { requestPage(current.page + 1, reset = false) }
    }

    /** Refresh — same as loadFavorites, reloads from page 1. */
    fun refresh() {
        loadFavorites()
    }

    private fun isAuthError(err: String): Boolean =
        err.contains("请先登入") ||
            err.contains("登录") ||
            err.contains("401") ||
            err.contains("login") ||
            err.contains("cookie")

    /** 鉴权失败时的静默重登录：用保存的凭据重新 login，成功后重试 [block]。 */
    private fun silentReloginAndRetryThenRun(block: suspend () -> Unit) {
        if (retryJob?.isActive == true) return
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        if (savedUser.isNullOrBlank() || savedPass.isNullOrBlank()) return

        retryJob = viewModelScope.launch {
            try {
                val loginResult = withContext(Dispatchers.IO) {
                    python.login(savedUser, savedPass)
                }
                if (loginResult.optBoolean("ok", false)) {
                    prefs.edit().putString("session_json", loginResult.toString()).apply()
                    block()
                } else if (_state.value.isLoading) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = loginResult.optString("error", "自动重登失败"),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FavoritesViewModel(application) as T
            }
    }
}