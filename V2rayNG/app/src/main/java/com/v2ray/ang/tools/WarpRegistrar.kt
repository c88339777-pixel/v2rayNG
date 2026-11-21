package com.v2ray.ang.tools

import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Makes a free Cloudflare WARP account (the same registration the official app does), builds a
 * WireGuard profile from it and stores it in the "WARP" group.
 */
object WarpRegistrar {

    const val GROUP_NAME = "WARP"

    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val ENDPOINT_HOST = "162.159.192.1"
    private const val ENDPOINT_PORT = "2408"

    sealed interface Outcome {
        data class Created(val name: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun create(): Outcome {
        return try {
            val privateKey = X25519.generatePrivateKey()
            val publicKey = X25519.publicKey(privateKey)

            val body = JSONObject()
                .put("key", b64(publicKey))
                .put("install_id", "")
                .put("fcm_token", "")
                .put("tos", isoNow())
                .put("type", "Android")
                .put("model", "PC")
                .put("locale", "en_US")

            val response = ToolNet.postJson(
                REGISTER_URL,
                body.toString(),
                mapOf("User-Agent" to "okhttp/3.12.1", "CF-Client-Version" to "a-6.3-2158")
            ) ?: return Outcome.Failed("اتصال به سرویس WARP برقرار نشد. اگر این سرویس روی اینترنت شما بسته است، با یک اتصال فعال دوباره امتحان کنید.")

            val config = JSONObject(response).getJSONObject("config")
            val peerKey = config.getJSONArray("peers").getJSONObject(0).getString("public_key")
            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            val v4 = addresses.getString("v4")
            val v6 = addresses.optString("v6")
            val clientId = config.optString("client_id")

            val reservedCsv = if (clientId.isNotEmpty()) {
                Base64.decode(clientId, Base64.DEFAULT).joinToString(",") { (it.toInt() and 0xFF).toString() }
            } else {
                "0,0,0"
            }

            val groupId = ServerGroups.ensure(GROUP_NAME)
                ?: return Outcome.Failed("ساخت گروه WARP انجام نشد")

            val number = MmkvManager.decodeServerList(groupId).size + 1
            val profile = ProfileItem.create(EConfigType.WIREGUARD)
            profile.subscriptionId = groupId
            profile.remarks = "WARP #$number"
            profile.server = ENDPOINT_HOST
            profile.serverPort = ENDPOINT_PORT
            profile.secretKey = b64(privateKey)
            profile.publicKey = peerKey
            profile.localAddress = if (v6.isNotEmpty()) "$v4/32,$v6/128" else "$v4/32"
            profile.reserved = reservedCsv
            profile.mtu = 1280

            MmkvManager.encodeServerConfig("", profile)
            Outcome.Created(profile.remarks)
        } catch (e: Exception) {
            Outcome.Failed("پاسخ سرویس WARP قابل استفاده نبود")
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
