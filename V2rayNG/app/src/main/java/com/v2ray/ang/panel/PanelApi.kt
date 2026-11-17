package com.v2ray.ang.panel

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PanelApi {
    const val BASE = "https://vpn-config-panel.vercel.app"

    data class Res(val ok: Boolean, val msg: String = "", val token: String? = null, val links: List<String> = emptyList())

    private fun call(path: String, method: String, body: String?, token: String?): Triple<Int, String, String?> {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        token?.let { c.setRequestProperty("Cookie", "vpn_user_session=$it") }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        val set = c.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith("vpn_user_session=") }
            ?.substringAfter("=")?.substringBefore(";")
        return Triple(code, text, set)
    }

    private fun err(text: String) = try { JSONObject(text).optString("error", "خطا") } catch (e: Exception) { "خطا در ارتباط با سرور" }

    fun login(user: String, pass: String): Res = try {
        val body = JSONObject().put("username", user).put("password", pass).toString()
        val (code, text, tok) = call("/api/user/login", "POST", body, null)
        if (code == 200 && tok != null) Res(true, token = tok) else Res(false, err(text))
    } catch (e: Exception) { Res(false, "اتصال به اینترنت برقرار نیست") }

    fun configs(token: String): Res = try {
        val (code, text, _) = call("/api/user/configs", "GET", null, token)
        if (code != 200) Res(false, err(text), if (code == 401) "expired" else null)
        else {
            val arr = JSONObject(text).getJSONArray("configs")
            val links = (0 until arr.length()).map { arr.getJSONObject(it).optString("config").trim() }
                .filter { Regex("^(vless|ss|vmess|trojan)://").containsMatchIn(it) }
            Res(true, links = links)
        }
    } catch (e: Exception) { Res(false, "اتصال به اینترنت برقرار نیست") }
}
