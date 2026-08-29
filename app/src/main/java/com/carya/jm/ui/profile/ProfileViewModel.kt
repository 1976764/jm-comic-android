package com.carya.jm.ui.profile

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
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

data class ProfileUiState(
    val favorites: List<ComicItem> = emptyList(),
    val isFavoritesLoading: Boolean = false,
    val favoritesError: String? = null,
    val favoritesFromCache: Boolean = false,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val python = PythonService()
    private val prefs =
        application.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        val cached = CacheManager.get().getCachedFavorites()
        if (cached != null && cached.isNotEmpty()) {
            _state.value = ProfileUiState(
                favorites = cached,
                favoritesFromCache = true,
            )
        }
        // 收藏缓存变化（详情页收藏/取消收藏后刷新缓存）→ 重新读缓存同步本页，
        // 不触发任何网络请求。
        viewModelScope.launch {
            CacheManager.get().favoritesVersion.collect {
                val fresh = CacheManager.get().getCachedFavorites()
                if (fresh != null) {
                    _state.value = _state.value.copy(
                        favorites = fresh,
                        favoritesFromCache = true,
                        favoritesError = null,
                    )
                }
            }
        }
    }

    /**
     * 轻量级缓存同步：直接读 favorites.json 并更新 UI 状态，不做任何网络请求。
     * 在 ProfileScreen 每次进入组合时调用，确保收藏列表与最新缓存一致
     * （取消收藏后立即生效，无需进入"更多收藏"页触发刷新）。
     */
    fun syncFavoritesFromCache() {
        val cached = CacheManager.get().getCachedFavorites()
        _state.value = _state.value.copy(
            favorites = cached ?: emptyList(),
            isFavoritesLoading = false,
            favoritesError = null,
            favoritesFromCache = cached != null,
        )
    }

    fun loadFavorites() {
        if (_state.value.isFavoritesLoading) return

        // 收藏缓存优先：有缓存就直接展示，不再发请求
        val cached = CacheManager.get().getCachedFavorites()
        if (cached != null && cached.isNotEmpty()) {
            _state.value = _state.value.copy(
                favorites = cached,
                isFavoritesLoading = false,
                favoritesError = null,
                favoritesFromCache = true,
            )
            return
        }

        _state.value = _state.value.copy(
            isFavoritesLoading = true,
            favoritesError = null,
        )

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.favoriteFolder(page = 1)
                }
                if (result.optBoolean("ok", false)) {
                    val items = parseComicItems(result)
                    CacheManager.get().cacheFavorites(items)
                    _state.value = ProfileUiState(favorites = items)
                } else {
                    silentReloginAndRetry()
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isFavoritesLoading = false,
                    favoritesError = e.message ?: "网络错误",
                )
            }
        }
    }

    fun refreshFavoritesInBackground() {
        // 已有缓存就不再请求（用户要求：缓存后不额外请求，只在收藏/取消收藏后刷新）
        val cached = CacheManager.get().getCachedFavorites()
        if (cached != null && cached.isNotEmpty()) {
            if (_state.value.favorites.isEmpty()) {
                _state.value = _state.value.copy(
                    favorites = cached,
                    favoritesFromCache = true,
                )
            }
            return
        }
        if (_state.value.favorites.isEmpty()) {
            _state.value = _state.value.copy(isFavoritesLoading = true)
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.favoriteFolder(page = 1)
                }
                if (result.optBoolean("ok", false)) {
                    val items = parseComicItems(result)
                    CacheManager.get().cacheFavorites(items)
                    _state.value = _state.value.copy(
                        favorites = items,
                        isFavoritesLoading = false,
                        favoritesError = null,
                        favoritesFromCache = false,
                    )
                } else {
                    silentReloginAndRetry()
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(isFavoritesLoading = false)
            }
        }
    }

    /**
     * Cookie 过期时静默重新登录并重试收藏加载。
     * 使用 SharedPreferences 中保存的凭据，无 UI 提示。
     */
    private fun silentReloginAndRetry() {
        val savedUser = prefs.getString("username", null)
        val savedPass = prefs.getString("password", null)
        if (savedUser.isNullOrBlank() || savedPass.isNullOrBlank()) {
            _state.value = _state.value.copy(isFavoritesLoading = false)
            return
        }
        viewModelScope.launch {
            try {
                val loginResult = withContext(Dispatchers.IO) {
                    python.login(savedUser, savedPass)
                }
                if (loginResult.optBoolean("ok", false)) {
                    prefs.edit()
                        .putString("session_json", loginResult.toString())
                        .apply()

                    val retryResult = withContext(Dispatchers.IO) {
                        python.favoriteFolder(page = 1)
                    }
                    if (retryResult.optBoolean("ok", false)) {
                        val items = parseComicItems(retryResult)
                        CacheManager.get().cacheFavorites(items)
                        _state.value = _state.value.copy(
                            favorites = items,
                            isFavoritesLoading = false,
                            favoritesError = null,
                            favoritesFromCache = false,
                        )
                    } else {
                        _state.value = _state.value.copy(isFavoritesLoading = false)
                    }
                } else {
                    _state.value = _state.value.copy(isFavoritesLoading = false)
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(isFavoritesLoading = false)
            }
        }
    }
}
