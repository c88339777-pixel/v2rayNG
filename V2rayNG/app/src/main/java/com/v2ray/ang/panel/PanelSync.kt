package com.v2ray.ang.panel

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Downloads the configs the admin assigned to the signed-in user and makes the
 * local list match them: new and changed configs are added, and configs that
 * were unassigned (or whose subscription ended) are removed.
 */
object PanelSync {

    sealed interface Outcome {
        data object Imported : Outcome
        data object NoConfigs : Outcome
        data class Expired(val msg: String) : Outcome
        data class Denied(val msg: String) : Outcome

        /** [offline] is true when the server could not be reached at all. */
        data class Failed(val msg: String, val offline: Boolean) : Outcome
    }

    // Owned by the process, so a sync that outlives the login screen still finishes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var running: Deferred<Outcome>? = null

    /** Starts a sync, or joins the one already running. */
    fun start(token: String): Deferred<Outcome> = synchronized(lock) {
        running?.takeIf { it.isActive } ?: scope.async { run(token) }.also { running = it }
    }

    private fun run(token: String): Outcome {
        return try {
            val result = PanelApi.configs(token)

            when {
                result.ok && result.links.isNotEmpty() -> {
                    // append = false replaces the group's old configs, so removed
                    // or edited assignments no longer linger or pile up as duplicates.
                    val imported = AngConfigManager.importBatchConfig(
                        result.links.joinToString("\n"),
                        "",
                        false
                    )

                    if (imported.first > 0) {
                        Outcome.Imported
                    } else {
                        Outcome.Failed(
                            "کانفیگ دریافت شد، اما وارد برنامه نشد. قالب کانفیگ‌ها را بررسی کنید.",
                            offline = false
                        )
                    }
                }

                // The server sent configs, but none can be imported: keep what is saved.
                result.ok && result.received > 0 -> Outcome.Failed(
                    "کانفیگ دریافت شد اما نوع آن پشتیبانی نمی‌شود: " +
                        result.skipped.joinToString("، "),
                    offline = false
                )

                result.ok -> {
                    MmkvManager.removeServerViaSubid("")
                    Outcome.NoConfigs
                }

                result.token == "expired" -> Outcome.Expired(result.msg)

                result.statusCode == 403 -> {
                    MmkvManager.removeServerViaSubid("")
                    Outcome.Denied(result.msg)
                }

                else -> Outcome.Failed(result.msg, offline = result.statusCode == 0)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Panel sync failed", e)
            Outcome.Failed("خطای غیرمنتظره هنگام دریافت کانفیگ‌ها", offline = false)
        }
    }
}
