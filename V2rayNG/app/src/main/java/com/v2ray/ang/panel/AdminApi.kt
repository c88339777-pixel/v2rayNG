package com.v2ray.ang.panel

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AdminConfig(
    val id: String,
    val name: String,
    val type: String,
    val config: String,
    val active: Boolean,
)

data class AdminUser(
    val id: String,
    val username: String,
    val active: Boolean,
    val planStart: String?,
    val planExpiresAt: String?,
    val totalGb: Double?,
    val usedGb: Double,
)

private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }

private fun JSONObject.numOrNull(key: String): Double? =
    if (isNull(key)) null else try {
        getDouble(key)
    } catch (_: Exception) {
        null
    }

fun JSONObject.toAdminConfig() = AdminConfig(
    id = optString("id"),
    name = optString("name"),
    type = optString("type"),
    config = optString("config"),
    active = optBoolean("active", true),
)

fun JSONObject.toAdminUser() = AdminUser(
    id = optString("id"),
    username = optString("username"),
    active = optBoolean("active", true),
    planStart = strOrNull("plan_start"),
    planExpiresAt = strOrNull("plan_expires_at"),
    totalGb = numOrNull("total_gb"),
    usedGb = numOrNull("used_gb") ?: 0.0,
)

/**
 * Admin calls to the VPN Hub website. The admin signs in through the site
 * (/api/admin/login), so the app never contains any Supabase key. Every
 * request carries the admin access token; it is renewed automatically.
 */
object AdminApi {

    data class Tokens(
        val access: String,
        val refresh: String,
        val expiresAtSec: Long,
    )

    data class Result(
        val ok: Boolean,
        val code: Int = 0,
        val msg: String = "",
        val json: JSONObject? = null,
    ) {
        /** The session is no longer accepted and the admin must sign in again. */
        val unauthorized: Boolean get() = code == 401 || code == 403
    }

    private const val SLOT = "_admin"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var tokens: Tokens? = null

    @Volatile
    private var appContext: Context? = null

    /** Loads a saved admin session (if any) from encrypted storage. */
    @Synchronized
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (tokens != null) return

        val raw = SecureTokenStore.get(context.applicationContext, SLOT) ?: return
        tokens = try {
            val json = JSONObject(raw)
            Tokens(
                access = json.getString("access"),
                refresh = json.getString("refresh"),
                expiresAtSec = json.optLong("exp", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun hasSession(): Boolean = tokens != null

    @Synchronized
    private fun setTokens(value: Tokens?) {
        tokens = value
        val ctx = appContext ?: return

        if (value == null) {
            SecureTokenStore.clear(ctx, SLOT)
        } else {
            SecureTokenStore.save(
                ctx,
                JSONObject()
                    .put("access", value.access)
                    .put("refresh", value.refresh)
                    .put("exp", value.expiresAtSec)
                    .toString(),
                SLOT,
            )
        }
    }

    fun clearSession() = setTokens(null)

    private fun parseTokens(json: JSONObject?): Tokens? {
        json ?: return null

        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")

        if (access.isBlank() || refresh.isBlank()) return null

        return Tokens(
            access = access,
            refresh = refresh,
            expiresAtSec = json.optLong(
                "expires_at",
                System.currentTimeMillis() / 1000 + 3000
            ),
        )
    }

    private fun errorOf(json: JSONObject?, fallback: String): String =
        json?.optString("error").orEmpty().ifBlank { fallback }

    private fun send(
        method: String,
        path: String,
        body: JSONObject?,
        bearer: String?,
    ): Result {
        return try {
            val requestBody = when {
                body != null -> body.toString().toRequestBody(jsonType)
                method == "GET" -> null
                else -> "{}".toRequestBody(jsonType)
            }

            val builder = Request.Builder()
                .url(PanelApi.BASE + path)
                .header("Accept", "application/json")
                .method(method, requestBody)

            if (bearer != null) {
                builder.header("Authorization", "Bearer $bearer")
            }

            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    null
                }

                Result(
                    ok = response.isSuccessful,
                    code = response.code,
                    msg = if (response.isSuccessful) "" else errorOf(json, "خطا در ارتباط با سرور"),
                    json = json,
                )
            }
        } catch (_: IOException) {
            Result(false, 0, "اتصال به اینترنت برقرار نیست")
        } catch (_: Exception) {
            Result(false, 0, "خطا در پردازش پاسخ سرور")
        }
    }

    fun login(email: String, password: String): Result {
        if (email.isBlank() || password.isEmpty()) {
            return Result(false, 400, "ایمیل و رمز مدیر الزامی است")
        }

        val result = send(
            "POST",
            "/api/admin/login",
            JSONObject().put("email", email.trim()).put("password", password),
            null,
        )

        if (!result.ok) return result

        val parsed = parseTokens(result.json)
            ?: return Result(false, 500, "پاسخ ورود مدیر نامعتبر است")

        setTokens(parsed)
        return result
    }

    @Synchronized
    private fun refresh(): Boolean {
        val current = tokens ?: return false

        val result = send(
            "POST",
            "/api/admin/refresh",
            JSONObject().put("refresh_token", current.refresh),
            null,
        )

        val parsed = if (result.ok) parseTokens(result.json) else null

        if (parsed == null) {
            // A rejected refresh token means the session is really over.
            if (result.unauthorized) setTokens(null)
            return false
        }

        setTokens(parsed)
        return true
    }

    /** Authenticated call; renews the token first when it is about to expire. */
    fun call(method: String, path: String, body: JSONObject? = null): Result {
        val current = tokens
            ?: return Result(false, 401, "نشست مدیر معتبر نیست. دوباره وارد شوید.")

        if (current.expiresAtSec in 1..(System.currentTimeMillis() / 1000 + 60)) {
            refresh()
        }

        var result = send(method, path, body, tokens?.access)

        if (result.unauthorized && refresh()) {
            result = send(method, path, body, tokens?.access)
        }

        return result
    }

    // ---------- configs ----------

    fun listConfigs(): Pair<Result, List<AdminConfig>> {
        val result = call("GET", "/api/admin/configs")
        val array: JSONArray = result.json?.optJSONArray("configs") ?: JSONArray()
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toAdminConfig() }
        return result to list
    }

    fun addConfig(name: String, type: String, config: String): Result =
        call(
            "POST", "/api/admin/configs",
            JSONObject().put("name", name).put("type", type).put("config", config)
        )

    fun updateConfig(id: String, fields: JSONObject): Result =
        call("PATCH", "/api/admin/configs", fields.put("id", id))

    fun deleteConfig(id: String): Result =
        call("DELETE", "/api/admin/configs", JSONObject().put("id", id))

    // ---------- users ----------

    fun listUsers(): Pair<Result, List<AdminUser>> {
        val result = call("GET", "/api/admin/users")
        val array: JSONArray = result.json?.optJSONArray("users") ?: JSONArray()
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toAdminUser() }
        return result to list
    }

    fun createUser(username: String, password: String): Result =
        call(
            "POST", "/api/admin/users",
            JSONObject().put("username", username).put("password", password)
        )

    fun updateUser(id: String, fields: JSONObject): Result =
        call("PATCH", "/api/admin/users", fields.put("id", id))

    fun deleteUser(id: String): Result =
        call("DELETE", "/api/admin/users", JSONObject().put("id", id))

    // ---------- config assignment ----------

    fun userConfigIds(userId: String): Pair<Result, Set<String>> {
        val result = call("GET", "/api/admin/users/$userId/configs")
        val array = result.json?.optJSONArray("configIds") ?: JSONArray()
        val ids = (0 until array.length()).map { array.optString(it) }.toSet()
        return result to ids
    }

    fun assignConfig(userId: String, configId: String): Result =
        call(
            "POST", "/api/admin/users/$userId/configs",
            JSONObject().put("configId", configId)
        )

    fun unassignConfig(userId: String, configId: String): Result =
        call(
            "DELETE", "/api/admin/users/$userId/configs",
            JSONObject().put("configId", configId)
        )
}
