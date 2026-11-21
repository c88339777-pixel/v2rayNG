package com.v2ray.ang.tools

import android.util.Base64
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.Utils
import org.json.JSONObject
import java.net.URLEncoder

/** Imports Outline access keys: plain `ss://` keys and dynamic `ssconf://` keys. */
object OutlineKeys {

    const val GROUP_NAME = "Outline"

    private val KEY = Regex("(?i)(ssconf|ss)://[^\\s\"'<>]+")

    data class Result(val imported: Int, val failed: Int)

    fun importFrom(text: String): Result {
        val keys = KEY.findAll(text)
            .map { it.value.trimEnd(',', '.', ')', ']') }
            .distinct()
            .toList()
        if (keys.isEmpty()) return Result(0, 0)

        val groupId = ServerGroups.ensure(GROUP_NAME) ?: return Result(0, keys.size)

        val links = ArrayList<String>()
        var failed = 0
        for (key in keys) {
            if (key.startsWith("ssconf://", ignoreCase = true)) {
                val link = resolveDynamicKey(key)
                if (link != null) links.add(link) else failed++
            } else {
                links.add(key)
            }
        }
        if (links.isEmpty()) return Result(0, failed)

        val imported = AngConfigManager.importBatchConfig(links.joinToString("\n"), groupId, true).first
        return Result(imported, failed + (links.size - imported).coerceAtLeast(0))
    }

    /** `ssconf://host/path#name` points at an https page that answers with the real key. */
    private fun resolveDynamicKey(key: String): String? {
        val rest = key.substring("ssconf://".length)
        val name = rest.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { Utils.decodeURIComponent(it) }
        val address = rest.substringBefore('#')
        if (address.isBlank()) return null

        val body = ToolNet.get("https://$address")?.trim() ?: return null

        if (body.startsWith("{")) {
            return try {
                val o = JSONObject(body)
                val method = o.optString("method")
                val password = o.optString("password")
                val host = o.optString("server").trim('[', ']')
                val port = o.optInt("server_port", o.optString("server_port").toIntOrNull() ?: 0)
                if (method.isEmpty() || password.isEmpty() || host.isEmpty() || port !in 1..65535) {
                    null
                } else {
                    buildLink(method, password, host, port, name ?: host)
                }
            } catch (e: Exception) {
                null
            }
        }

        return body.lines().map { it.trim() }.firstOrNull { it.startsWith("ss://", ignoreCase = true) }
    }

    private fun buildLink(method: String, password: String, host: String, port: Int, name: String): String {
        val userInfo = Base64.encodeToString(
            "$method:$password".toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val hostPart = if (host.contains(':')) "[$host]" else host
        val tag = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "ss://$userInfo@$hostPart:$port#$tag"
    }
}
