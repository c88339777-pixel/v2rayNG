package com.v2ray.ang.panel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class LoginUiState(
    val loading: Boolean = false,
    val message: String = "",
    val isError: Boolean = false,
)

sealed interface LoginEvent {
    data object OpenMain : LoginEvent
    data object OpenAdmin : LoginEvent
}

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** How long a normal launch waits for the server before using the saved configs. */
        const val SYNC_WAIT_MS = 8_000L
    }

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    private val prefs by lazy {
        application.getSharedPreferences("panel", Context.MODE_PRIVATE)
    }

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val app = getApplication<Application>()

        // The saved sessions live in the Keystore: read them off the main thread.
        val (adminSession, token) = withContext(Dispatchers.IO) {
            AdminApi.attach(app)

            if (AdminApi.hasSession()) true to null else false to loadSavedUserToken()
        }

        when {
            adminSession -> _events.send(LoginEvent.OpenAdmin)
            token != null -> syncUser(token, requireFresh = false)
        }
    }

    private fun loadSavedUserToken(): String? {
        val app = getApplication<Application>()
        val secureToken = SecureTokenStore.get(app)

        if (secureToken != null) return secureToken

        val oldToken = prefs.getString("token", null)

        if (!oldToken.isNullOrBlank()) {
            return try {
                SecureTokenStore.save(app, oldToken)
                prefs.edit().remove("token").apply()
                oldToken
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    /** False only when the phone has no network at all (airplane mode, no Wi-Fi/data). */
    private fun hasAnyNetwork(): Boolean {
        return try {
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false)
                ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun setState(loading: Boolean, message: String = "", isError: Boolean = false) {
        _uiState.update { LoginUiState(loading, message, isError) }
    }

    // ---------- user ----------

    fun userLogin(username: String, password: String) {
        if (_uiState.value.loading) return

        viewModelScope.launch {
            setState(true, "در حال ورود...")

            val result = withContext(Dispatchers.IO) {
                PanelApi.login(username.trim(), password)
            }

            if (result.ok && !result.token.isNullOrBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        SecureTokenStore.save(getApplication<Application>(), result.token)
                    }

                    syncUser(result.token, requireFresh = true)
                } catch (_: Exception) {
                    setState(false, "خطا در ذخیره امن اطلاعات ورود", true)
                }
            } else {
                setState(false, result.msg, true)
            }
        }
    }

    /**
     * [requireFresh] is true right after a login: the screen then waits for the
     * server, because configs left over from another account must not be used.
     * On a normal launch the saved configs are used if the server is slow or
     * unreachable, and the sync keeps running in the background.
     */
    private suspend fun syncUser(token: String, requireFresh: Boolean) {
        val hasLocal = !requireFresh && withContext(Dispatchers.IO) {
            MmkvManager.decodeAllServerList().isNotEmpty()
        }

        // Already signed in and configs are saved: with no network there is nothing
        // to wait for, so open the app right away (a sync still starts in the background).
        if (hasLocal && !hasAnyNetwork()) {
            PanelSync.start(token)
            _events.send(LoginEvent.OpenMain)
            return
        }

        setState(true, "در حال دریافت کانفیگ‌ها...")

        val sync = PanelSync.start(token)
        val outcome = if (hasLocal) {
            withTimeoutOrNull(SYNC_WAIT_MS) { sync.await() }
        } else {
            sync.await()
        }

        when (outcome) {
            // Slow network: open with the saved configs; the sync finishes by itself.
            null, PanelSync.Outcome.Imported -> _events.send(LoginEvent.OpenMain)

            PanelSync.Outcome.NoConfigs ->
                setState(false, "کانفیگ فعالی به حساب شما اختصاص داده نشده است.", true)

            is PanelSync.Outcome.Expired -> {
                withContext(Dispatchers.IO) {
                    SecureTokenStore.clear(getApplication<Application>())
                    PanelApi.clearSession()
                    prefs.edit().remove("token").apply()
                }
                setState(false, "نشست شما تمام شده است؛ دوباره وارد شوید.", true)
            }

            is PanelSync.Outcome.Denied -> setState(false, outcome.msg, true)

            is PanelSync.Outcome.Failed ->
                // hasLocal is only true on a normal launch (never right after a fresh login),
                // so any failure here -- offline, timeout or a server error -- keeps the user in.
                if (hasLocal) {
                    _events.send(LoginEvent.OpenMain)
                } else {
                    setState(false, outcome.msg, true)
                }
        }
    }

    // ---------- admin ----------

    fun adminLogin(email: String, password: String) {
        if (_uiState.value.loading) return

        viewModelScope.launch {
            setState(true, "در حال ورود مدیر...")

            val result = withContext(Dispatchers.IO) { AdminApi.login(email, password) }

            if (result.ok) {
                setState(false)
                _events.send(LoginEvent.OpenAdmin)
            } else {
                setState(false, result.msg, true)
            }
        }
    }
}
