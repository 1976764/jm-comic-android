package com.carya.jm.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.model.UserInfo
import com.carya.jm.data.model.parseUserInfo
import com.carya.jm.data.python.PythonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** UI state for authentication, observed by both LoginScreen and ProfileScreen. */
data class AuthUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val userInfo: UserInfo? = null,
    val error: String? = null,
)

/**
 * Shared authentication ViewModel.
 *
 * Session persistence strategy (fastest to slowest):
 *
 * 1. **In-process check** — if the Python client is still alive (same process),
 *    `login_status` returns instantly with no network I/O.
 * 2. **Cookie restore** — if saved cookies exist in SharedPreferences, create a
 *    client, inject the cookies, and verify with a lightweight API call. This
 *    avoids a full login POST and is near-instant when the session is alive.
 * 3. **Password re-login** — if cookies have expired, fall back to the saved
 *    username + password. This is the slowest path but only runs when necessary.
 *
 * Credentials and cookies are persisted in SharedPreferences so the user stays
 * logged in across app restarts without re-typing.
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val python = PythonService()
    private val prefs =
        application.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        checkStatus()
    }

    /** Attempt to log in with the given credentials (manual login from UI). */
    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(
                error = "用户名和密码不能为空",
                isLoading = false,
            )
            return
        }

        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.login(username, password)
                }
                if (result.optBoolean("ok", false)) {
                    val userInfo = parseUserInfo(result)
                    // Persist credentials + session cookies for next launch.
                    prefs.edit()
                        .putString("username", username)
                        .putString("password", password)
                        .putString("session_json", result.toString())
                        .apply()
                    _state.value = AuthUiState(
                        isLoggedIn = true,
                        userInfo = userInfo,
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = result.optString("error", "登录失败，请重试"),
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "发生未知错误",
                )
            }
        }
    }

    /** Log out and clear saved credentials + cookies. */
    fun logout() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { python.logout() }
            } catch (_: Exception) {
                // Even if the Python call fails, reset the UI state.
            }
            prefs.edit().clear().apply()
            _state.value = AuthUiState()
        }
    }

    /** Clear any error message (e.g. when user starts typing again). */
    fun resetError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Session restore flow — tries the fastest method first.
     *
     * 1. In-process Python session check (instant, no network).
     * 2. Cookie restore from SharedPreferences (1 lightweight API call).
     * 3. Password re-login (full login POST, only when cookies expired).
     */
    private fun checkStatus() {
        viewModelScope.launch {
            // Step 1: Check if the Python session is still active in-process.
            try {
                val status = withContext(Dispatchers.IO) { python.getLoginStatus() }
                if (status.optBoolean("loggedIn", false)) {
                    val userInfo = parseUserInfo(status)
                    _state.value = AuthUiState(
                        isLoggedIn = true,
                        userInfo = userInfo,
                    )
                    return@launch
                }
            } catch (_: Exception) {
                // Python might not be started yet; fall through.
            }

            // Step 2: Try restoring saved cookies (avoids full login POST).
            val savedSession = prefs.getString("session_json", null)
            if (!savedSession.isNullOrBlank()) {
                try {
                    val sessionJson = JSONObject(savedSession)
                    val result = withContext(Dispatchers.IO) {
                        python.restoreSession(sessionJson)
                    }
                    if (result.optBoolean("ok", false)) {
                        val userInfo = parseUserInfo(result)
                        _state.value = AuthUiState(
                            isLoggedIn = true,
                            userInfo = userInfo,
                        )
                        return@launch
                    }
                    // Cookies expired — fall through to password login.
                } catch (_: Exception) {
                    // Malformed JSON or Python error — fall through.
                }
            }

            // Step 3: Fall back to password re-login with saved credentials.
            val savedUser = prefs.getString("username", null)
            val savedPass = prefs.getString("password", null)
            if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
                autoLogin(savedUser, savedPass)
            }
        }
    }

    /** Silently re-login with saved credentials. Updates cookies on success. */
    private fun autoLogin(username: String, password: String) {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.login(username, password)
                }
                if (result.optBoolean("ok", false)) {
                    val userInfo = parseUserInfo(result)
                    // Refresh saved cookies from the new login.
                    prefs.edit()
                        .putString("session_json", result.toString())
                        .apply()
                    _state.value = AuthUiState(
                        isLoggedIn = true,
                        userInfo = userInfo,
                    )
                } else {
                    // Saved credentials no longer work -- discard everything.
                    prefs.edit().clear().apply()
                    _state.value = AuthUiState()
                }
            } catch (_: Exception) {
                prefs.edit().clear().apply()
                _state.value = AuthUiState()
            }
        }
    }
}
