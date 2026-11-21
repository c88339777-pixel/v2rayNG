package com.v2ray.ang.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.freeservers.FreeServerSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolsState(
    val outlineInput: String = "",
    /** Which tool is running right now: free, outline, warp, scan or apply. */
    val busy: String? = null,
    val scanProgress: Pair<Int, Int>? = null,
    val scanResults: List<CleanIpScanner.Result> = emptyList(),
    val message: String? = null,
)

class ToolsViewModel : ViewModel() {

    private val _state = MutableStateFlow(ToolsState())
    val state: StateFlow<ToolsState> = _state.asStateFlow()

    fun setOutlineInput(text: String) = _state.update { it.copy(outlineInput = text) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun run(tag: String, block: suspend () -> String) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = tag) }
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                "خطا: ${e.message ?: "نامشخص"}"
            }
            _state.update { it.copy(busy = null, message = message) }
        }
    }

    fun refreshFreeServers() = run("free") {
        val outcome = FreeServerSync.refresh(force = true, onGroupChanged = { ToolsSignal.changed = true })
        ToolsSignal.changed = true
        when (outcome) {
            is FreeServerSync.Outcome.Imported ->
                "${outcome.count} سرور روی این اینترنت جواب داد؛ سرورهایی که روی هیچ‌کدام از اینترنت‌های شما کار نکنند پاک می‌شوند"
            is FreeServerSync.Outcome.Failed -> outcome.msg
            FreeServerSync.Outcome.Skipped -> "به‌روزرسانی همین حالا در حال انجام است"
        }
    }

    fun importOutline() {
        val text = _state.value.outlineInput
        if (text.isBlank()) return
        run("outline") {
            val result = withContext(Dispatchers.IO) { OutlineKeys.importFrom(text) }
            if (result.imported > 0) {
                ToolsSignal.changed = true
                _state.update { it.copy(outlineInput = "") }
                "${result.imported} کلید به گروه «${OutlineKeys.GROUP_NAME}» اضافه شد"
            } else {
                "کلید معتبری وارد نشد؛ لینک ss:// یا ssconf:// را بررسی کنید"
            }
        }
    }

    fun createWarp() = run("warp") {
        when (val outcome = withContext(Dispatchers.IO) { WarpRegistrar.create() }) {
            is WarpRegistrar.Outcome.Created -> {
                ToolsSignal.changed = true
                "${outcome.kept} اتصال WARP سالم (بهترین: ${outcome.bestMs} ms) در گروه «${WarpRegistrar.GROUP_NAME}» اضافه شد"
            }
            is WarpRegistrar.Outcome.Failed -> outcome.message
        }
    }

    fun scanCleanIps() = run("scan") {
        _state.update { it.copy(scanResults = emptyList(), scanProgress = 0 to SCAN_COUNT) }
        val results = CleanIpScanner.scan(SCAN_COUNT) { done, total ->
            _state.update { s -> s.copy(scanProgress = done to total) }
        }
        _state.update { it.copy(scanResults = results.take(10), scanProgress = null) }
        if (results.isEmpty()) {
            "آی‌پی سالمی پیدا نشد؛ اتصال اینترنت را بررسی کنید"
        } else {
            "${results.size} آی‌پی سالم پیدا شد"
        }
    }

    fun applyCleanIps() = run("apply") {
        val ips = _state.value.scanResults
        val created = withContext(Dispatchers.IO) { CleanIpApplier.build(ips) }
        if (created > 0) {
            ToolsSignal.changed = true
            "$created کانفیگ در گروه «${CleanIpApplier.GROUP_NAME}» ساخته شد"
        } else {
            "کانفیگ مناسبی پیدا نشد؛ فقط کانفیگ‌های TLS پنل (VLESS، VMess، Trojan) پشتیبانی می‌شوند"
        }
    }

    private companion object {
        const val SCAN_COUNT = 300
    }
}
