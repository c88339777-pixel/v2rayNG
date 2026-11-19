package com.v2ray.ang.panel

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object PanelApi {

    const val BASE = "https://vpn-config-panel.vercel.app"

    private val jsonType =
        "application/json; charset=utf-8".toMediaType()

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    // Same schemes the importer (AngConfigManager) can parse.
    private val SUPPORTED_SCHEMES = setOf(
        "vmess", "vless", "ss", "trojan", "socks", "socks4", "socks5", "http",
        "wireguard", "tuic", "hysteria", "hysteria2", "hy2", "masque"
    )

    data class Res(
        val ok: Boolean,
        val msg: String = "",
        val token: String? = null,
        val links: List<String> = emptyList(),
        /** How many non-empty configs the server returned (before filtering). */
        val received: Int = 0,
        /** Schemes of returned configs the app cannot import. */
        val skipped: List<String> = emptyList(),
        val statusCode: Int = 0
    )

    private class SessionCookieJar : CookieJar {

        private val cookies = mutableMapOf<String, Cookie>()

        @Synchronized
        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>
        ) {
            cookies.forEach {
                if (it.name == "vpn_user_session") {
                    this.cookies[it.name] = it
                }
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookie = cookies["vpn_user_session"]
                ?: return emptyList()

            if (cookie.expiresAt <= System.currentTimeMillis()) {
                cookies.remove(cookie.name)
                return emptyList()
            }

            return listOf(cookie)
        }

        @Synchronized
        fun setToken(token: String) {
            cookies["vpn_user_session"] = Cookie.Builder()
                .name("vpn_user_session")
                .value(token)
                .domain("vpn-config-panel.vercel.app")
                .path("/")
                .secure()
                .httpOnly()
                .build()
        }

        @Synchronized
        fun clear() {
            cookies.clear()
        }
    }

    private val cookieJar = SessionCookieJar()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun errorMessage(
        text: String,
        fallback: String
    ): String {
        return try {
            JSONObject(text)
                .optString("error", fallback)
                .ifBlank { fallback }
        } catch (_: Exception) {
            fallback
        }
    }

    fun login(user: String, pass: String): Res {
        return try {
            cookieJar.clear()

            if (user.isBlank() || pass.isEmpty()) {
                return Res(
                    false,
                    "نام کاربری و رمز عبور الزامی است"
                )
            }

            val body = JSONObject()
                .put("username", user.trim())
                .put("password", pass)
                .toString()
                .toRequestBody(jsonType)

            val request = Request.Builder()
                .url("$BASE/api/user/login")
                .post(body)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->

                val text = response.body?.string().orEmpty()

                val token = response.headers
                    .values("Set-Cookie")
                    .firstOrNull {
                        it.startsWith("vpn_user_session=")
                    }
                    ?.substringAfter("vpn_user_session=")
                    ?.substringBefore(";")

                if (response.code == 200 &&
                    !token.isNullOrBlank()
                ) {
                    cookieJar.setToken(token)

                    Res(
                        ok = true,
                        token = token,
                        statusCode = response.code
                    )
                } else {
                    Res(
                        ok = false,
                        msg = errorMessage(
                            text,
                            "ورود ناموفق بود"
                        ),
                        statusCode = response.code
                    )
                }
            }

        } catch (_: IOException) {
            Res(false, "اتصال به اینترنت برقرار نیست")
        } catch (_: Exception) {
            Res(false, "خطا در پردازش پاسخ سرور")
        }
    }

    fun configs(token: String): Res {
        return try {
            cookieJar.setToken(token)

            val request = Request.Builder()
                .url("$BASE/api/user/configs")
                .get()
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->

                val text = response.body?.string().orEmpty()

                when (response.code) {

                    200 -> {
                        val json = JSONObject(text)
                        val arr = json.optJSONArray("configs")

                        val links = mutableListOf<String>()
                        val skipped = linkedSetOf<String>()
                        var received = 0

                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val raw = arr.optJSONObject(i)
                                    ?.optString("config")
                                    ?.trim()
                                    .orEmpty()

                                if (raw.isBlank()) continue
                                received++

                                // One config row may hold several links, one per line.
                                raw.lineSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .forEach { line ->
                                        val scheme = SCHEME.find(line)
                                            ?.groupValues?.get(1)
                                            ?.lowercase()

                                        if (scheme != null && scheme in SUPPORTED_SCHEMES) {
                                            // The importer only understands lower-case schemes.
                                            links.add(scheme + line.substring(scheme.length))
                                        } else {
                                            skipped.add(scheme ?: "unknown")
                                        }
                                    }
                            }
                        }

                        Res(
                            ok = true,
                            links = links.distinct(),
                            received = received,
                            skipped = skipped.toList(),
                            statusCode = 200
                        )
                    }

                    401 -> {
                        cookieJar.clear()
                        Res(
                            false,
                            errorMessage(text, "نشست شما منقضی شده است"),
                            token = "expired",
                            statusCode = 401
                        )
                    }

                    403 -> Res(
                        false,
                        errorMessage(text, "اشتراک شما فعال نیست"),
                        statusCode = 403
                    )

                    else -> Res(
                        false,
                        errorMessage(text, "خطا در دریافت کانفیگ‌ها"),
                        statusCode = response.code
                    )
                }
            }

        } catch (_: IOException) {
            Res(false, "اتصال به اینترنت برقرار نیست")
        } catch (_: Exception) {
            Res(false, "خطا در پردازش اطلاعات کانفیگ‌ها")
        }
    }

    fun logout(): Res {
        return try {
            val request = Request.Builder()
                .url("$BASE/api/user/logout")
                .post("".toRequestBody(jsonType))
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->

                val text = response.body?.string().orEmpty()

                cookieJar.clear()

                Res(
                    ok = response.isSuccessful,
                    msg = if (response.isSuccessful) {
                        "با موفقیت از حساب خارج شدید"
                    } else {
                        errorMessage(
                            text,
                            "خروج از حساب ناموفق بود"
                        )
                    },
                    statusCode = response.code
                )
            }

        } catch (_: IOException) {
            cookieJar.clear()
            Res(false, "ارتباط با سرور برقرار نشد؛ نشست محلی پاک شد")
        } catch (_: Exception) {
            cookieJar.clear()
            Res(false, "خطا هنگام خروج از حساب")
        }
    }

    fun clearSession() {
        cookieJar.clear()
    }
}
