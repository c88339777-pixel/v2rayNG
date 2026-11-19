package com.v2ray.ang.panel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AdminUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val configs: List<AdminConfig> = emptyList(),
    val users: List<AdminUser> = emptyList(),
    /** The user whose assignments are held below (the one open in the editor). */
    val assignedUserId: String? = null,
    /** Config IDs assigned to [assignedUserId]. */
    val assignedConfigIds: Set<String> = emptySet(),
    /** True while the assignment list of [assignedUserId] is being fetched. */
    val assignedLoading: Boolean = false,
    /** True once the list was fetched, so toggles are safe to apply. */
    val assignedReady: Boolean = false,
    /** Config IDs whose assign/unassign request is still in flight. */
    val assigningConfigIds: Set<String> = emptySet(),
    val sessionExpired: Boolean = false,
)

class AdminViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    private var assignedJob: Job? = null

    init {
        viewModelScope.launch {
            // Reading the saved session touches the Keystore: keep it off the main thread.
            val hasSession = withContext(Dispatchers.IO) {
                AdminApi.attach(application)
                AdminApi.hasSession()
            }

            if (hasSession) {
                _uiState.update { it.copy(loading = true) }
                loadLists()
                _uiState.update { it.copy(loading = false) }
            } else {
                _uiState.update { it.copy(sessionExpired = true) }
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun handleFailure(result: AdminApi.Result) {
        if (result.unauthorized && !AdminApi.hasSession()) {
            _uiState.update { it.copy(sessionExpired = true) }
        } else {
            _uiState.update { it.copy(message = result.msg.ifBlank { "خطا در انجام عملیات" }) }
        }
    }

    /**
     * Runs [block] on IO. It returns the failed result, or null on success.
     * A successful operation reloads the lists afterwards when [reload] is set.
     */
    private fun operation(
        successMessage: String? = null,
        reload: Boolean = true,
        block: () -> AdminApi.Result,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }

            val result = withContext(Dispatchers.IO) { block() }

            if (!result.ok) {
                _uiState.update { it.copy(loading = false) }
                handleFailure(result)
                return@launch
            }

            if (reload) {
                loadLists()
            }

            _uiState.update {
                it.copy(loading = false, message = successMessage ?: it.message)
            }
        }
    }

    private suspend fun loadLists() {
        val (configResult, configs) = withContext(Dispatchers.IO) { AdminApi.listConfigs() }
        val (userResult, users) = withContext(Dispatchers.IO) { AdminApi.listUsers() }

        if (!configResult.ok) {
            handleFailure(configResult)
        } else if (!userResult.ok) {
            handleFailure(userResult)
        }

        _uiState.update {
            it.copy(
                configs = if (configResult.ok) configs else it.configs,
                users = if (userResult.ok) users else it.users,
            )
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            loadLists()
            _uiState.update { it.copy(loading = false) }
        }
    }

    // ---------- configs ----------

    fun saveConfig(id: String?, name: String, type: String, config: String) {
        if (name.isBlank() || config.isBlank()) {
            _uiState.update { it.copy(message = "نام و متن کانفیگ را وارد کنید") }
            return
        }

        if (id == null) {
            operation("کانفیگ اضافه شد") {
                AdminApi.addConfig(name.trim(), type, config.trim())
            }
        } else {
            operation("کانفیگ ذخیره شد") {
                AdminApi.updateConfig(
                    id,
                    JSONObject()
                        .put("name", name.trim())
                        .put("type", type)
                        .put("config", config.trim())
                )
            }
        }
    }

    fun setConfigActive(id: String, active: Boolean) {
        operation {
            AdminApi.updateConfig(id, JSONObject().put("active", active))
        }
    }

    fun deleteConfig(id: String) {
        operation("کانفیگ حذف شد") { AdminApi.deleteConfig(id) }
    }

    // ---------- users ----------

    fun createUser(username: String, password: String) {
        val name = username.trim().lowercase()

        if (!Regex("^[a-z0-9_-]{3,30}$").matches(name)) {
            _uiState.update { it.copy(message = "نام کاربری باید ۳ تا ۳۰ حرف انگلیسی کوچک یا عدد باشد") }
            return
        }

        if (password.length < 8) {
            _uiState.update { it.copy(message = "رمز باید حداقل ۸ کاراکتر باشد") }
            return
        }

        operation("کاربر ساخته شد") { AdminApi.createUser(name, password) }
    }

    fun setUserActive(id: String, active: Boolean) {
        operation { AdminApi.updateUser(id, JSONObject().put("active", active)) }
    }

    fun deleteUser(id: String) {
        operation("کاربر حذف شد") { AdminApi.deleteUser(id) }
    }

    /**
     * Saves everything edited in the user dialog in one request.
     * [startDate]/[expiryDate] are YYYY-MM-DD or blank (no limit);
     * [totalGb] blank means unlimited.
     */
    fun saveUser(
        id: String,
        newPassword: String,
        startDate: String,
        expiryDate: String,
        totalGb: String,
        usedGb: String,
    ) {
        val fields = JSONObject()

        if (newPassword.isNotEmpty()) {
            if (newPassword.length < 8) {
                _uiState.update { it.copy(message = "رمز جدید باید حداقل ۸ کاراکتر باشد") }
                return
            }
            fields.put("password", newPassword)
        }

        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        if (startDate.isNotBlank() && !dateRegex.matches(startDate.trim())) {
            _uiState.update { it.copy(message = "تاریخ شروع را به شکل 2026-12-31 بنویسید") }
            return
        }

        if (expiryDate.isNotBlank() && !dateRegex.matches(expiryDate.trim())) {
            _uiState.update { it.copy(message = "تاریخ انقضا را به شکل 2026-12-31 بنویسید") }
            return
        }

        fields.put(
            "plan_start",
            if (startDate.isBlank()) JSONObject.NULL else "${startDate.trim()}T00:00:00Z"
        )
        fields.put(
            "plan_expires_at",
            if (expiryDate.isBlank()) JSONObject.NULL else "${expiryDate.trim()}T23:59:59Z"
        )

        val total = if (totalGb.isBlank()) null else totalGb.trim().toDoubleOrNull()
        val used = if (usedGb.isBlank()) 0.0 else usedGb.trim().toDoubleOrNull()

        if ((totalGb.isNotBlank() && (total == null || total < 0)) ||
            used == null || used < 0
        ) {
            _uiState.update { it.copy(message = "حجم را به‌صورت عدد مثبت وارد کنید") }
            return
        }

        fields.put("total_gb", total ?: JSONObject.NULL)
        fields.put("used_gb", used)

        operation("اطلاعات کاربر ذخیره شد") { AdminApi.updateUser(id, fields) }
    }

    // ---------- config assignment ----------

    fun loadAssigned(userId: String) {
        // A newer request supersedes the previous one, so a slow answer for an
        // earlier user can never overwrite the list of the user now on screen.
        assignedJob?.cancel()

        assignedJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    assignedUserId = userId,
                    assignedConfigIds = emptySet(),
                    assignedLoading = true,
                    assignedReady = false,
                    assigningConfigIds = emptySet(),
                )
            }

            val (result, ids) = withContext(Dispatchers.IO) { AdminApi.userConfigIds(userId) }

            _uiState.update {
                if (it.assignedUserId != userId) {
                    it
                } else {
                    it.copy(
                        assignedConfigIds = if (result.ok) ids else emptySet(),
                        assignedLoading = false,
                        assignedReady = result.ok,
                    )
                }
            }

            if (!result.ok) handleFailure(result)
        }
    }

    fun setAssigned(userId: String, configId: String, assigned: Boolean) {
        val current = _uiState.value

        // Ignore taps until the list is loaded, or while this same config is still being saved.
        if (current.assignedUserId != userId ||
            !current.assignedReady ||
            configId in current.assigningConfigIds
        ) return

        // Optimistic update: the checkbox reacts at once and is rolled back on failure.
        _uiState.update {
            it.copy(
                assignedConfigIds = it.assignedConfigIds.withAssignment(configId, assigned),
                assigningConfigIds = it.assigningConfigIds + configId,
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (assigned) AdminApi.assignConfig(userId, configId)
                else AdminApi.unassignConfig(userId, configId)
            }

            // 409 = already assigned, 404 = already unassigned: the screen was simply out of date.
            val done = result.ok ||
                (assigned && result.code == 409) ||
                (!assigned && result.code == 404)

            _uiState.update {
                if (it.assignedUserId != userId) {
                    it
                } else {
                    it.copy(
                        assignedConfigIds = if (done) {
                            it.assignedConfigIds
                        } else {
                            it.assignedConfigIds.withAssignment(configId, !assigned)
                        },
                        assigningConfigIds = it.assigningConfigIds - configId,
                    )
                }
            }

            if (!done) handleFailure(result)
        }
    }

    private fun Set<String>.withAssignment(configId: String, assigned: Boolean): Set<String> =
        if (assigned) this + configId else this - configId

    fun logout() {
        AdminApi.clearSession()
    }
}
