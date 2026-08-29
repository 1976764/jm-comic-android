package com.carya.jm.ui.login

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.python.PythonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level UI state for the login flow. */
sealed interface LoginUiState {

    /** Initial / logged-out state — the login form is visible. */
    data object Idle : LoginUiState

    /** Network request in progress. */
    data object Loading : LoginUiState

    /** Login succeeded — show the logged-in view. */
    data class Success(val username: String) : LoginUiState

    /** Login failed — show the error and let the user retry. */
    data class Error(val message: String) : LoginUiState
}

/**
 * Manages login state and mediates between the Compose UI and [PythonService].
 *
 * All Python calls run on [Dispatchers.IO] via [viewModelScope].
 *
 * Credentials are persisted in SharedPreferences so the user stays logged in
 * across app restarts.  On startup the ViewModel checks for an active Python
 * session first; if none exists but saved credentials are found, an automatic
 * re-login is attempted.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val python = PythonService()
    private val prefs =
        application.getSharedPreferences("auth", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // On startup, check whether a session is already active.
        checkStatus()
    }

    /** Attempt to log in with the given credentials. */
    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("用户名和密码不能为空")
            return
        }

        _uiState.value = LoginUiState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.login(username, password)
                }
                if (result.optBoolean("ok", false)) {
                    // Persist credentials for auto-login on next app launch.
                    prefs.edit()
                        .putString("username", username)
                        .putString("password", password)
                        .apply()
                    _uiState.value = LoginUiState.Success(
                        result.optString("username", username)
                    )
                } else {
                    _uiState.value = LoginUiState.Error(
                        result.optString("error", "登录失败，请重试")
                    )
                }
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(
                    e.message ?: "发生未知错误"
                )
            }
        }
    }

    /** Log out and return to the login form. */
    fun logout() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { python.logout() }
            } catch (_: Exception) {
                // Even if the Python call fails, reset the UI state.
            }
            // Clear saved credentials so auto-login won't re-trigger.
            prefs.edit().clear().apply()
            _uiState.value = LoginUiState.Idle
        }
    }

    /** Reset to the idle state after an error. */
    fun resetError() {
        _uiState.value = LoginUiState.Idle
    }

    /**
     * Check the Python layer for an active session.  If no session is active
     * but credentials were saved from a previous login, attempt an automatic
     * re-login so the user doesn't have to type their credentials again.
     */
    private fun checkStatus() {
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { python.getLoginStatus() }
                if (status.optBoolean("loggedIn", false)) {
                    _uiState.value = LoginUiState.Success(
                        status.optString("username", "")
                    )
                    return@launch
                }
            } catch (_: Exception) {
                // Python might not be started yet; fall through to auto-login.
            }

            // No active session — try restoring from saved credentials.
            val savedUser = prefs.getString("username", null)
            val savedPass = prefs.getString("password", null)
            if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
                autoLogin(savedUser, savedPass)
            }
        }
    }

    /** Silently re-login with saved credentials.  Clears them on failure. */
    private fun autoLogin(username: String, password: String) {
        _uiState.value = LoginUiState.Loading
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    python.login(username, password)
                }
                if (result.optBoolean("ok", false)) {
                    _uiState.value = LoginUiState.Success(
                        result.optString("username", username)
                    )
                } else {
                    // Saved credentials no longer work — discard them.
                    prefs.edit().clear().apply()
                    _uiState.value = LoginUiState.Idle
                }
            } catch (_: Exception) {
                prefs.edit().clear().apply()
                _uiState.value = LoginUiState.Idle
            }
        }
    }
}
