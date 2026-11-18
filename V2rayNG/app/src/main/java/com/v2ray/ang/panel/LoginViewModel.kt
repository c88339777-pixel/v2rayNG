package com.v2ray.ang.panel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.AngConfigManager
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

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    private val prefs by lazy {
        application.getSharedPreferences("panel", Context.MODE_PRIVATE)
    }

    init {
        AdminApi.attach(application)

        if (AdminApi.hasSession()) {
            _events.trySend(LoginEvent.OpenAdmin)
        } else {
            loadSavedUserToken()?.let { syncUser(it) }
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

                    syncUser(result.token)
                } catch (_: Exception) {
                    setState(false, "خطا در ذخیره امن اطلاعات ورود", true)
                }
            } else {
                setState(false, result.msg, true)
            }
        }
    }

    private fun syncUser(token: String) {
        viewModelScope.launch {
            setState(true, "در حال دریافت کانفیگ‌ها...")

            try {
                val result = withContext(Dispatchers.IO) { PanelApi.configs(token) }

                when {
                    result.ok && result.links.isNotEmpty() -> {
                        val imported = withContext(Dispatchers.IO) {
                            AngConfigManager.importBatchConfig(
                                result.links.joinToString("\n"),
                                "",
                                true
                            )
                        }

                        if (imported.first <= 0) {
                            setState(
                                false,
                                "کانفیگ دریافت شد، اما وارد برنامه نشد. قالب کانفیگ‌ها را بررسی کنید.",
                                true
                            )
                        } else {
                            _events.send(LoginEvent.OpenMain)
                        }
                    }

                    result.ok -> setState(false, "هنوز کانفیگی به حساب شما اختصاص داده نشده است.", true)

                    result.token == "expired" -> {
                        SecureTokenStore.clear(getApplication<Application>())
                        PanelApi.clearSession()
                        prefs.edit().remove("token").apply()
                        setState(false, "نشست شما تمام شده است؛ دوباره وارد شوید.", true)
                    }

                    else -> setState(false, result.msg, true)
                }
            } catch (_: Exception) {
                setState(false, "خطای غیرمنتظره هنگام دریافت کانفیگ‌ها", true)
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
