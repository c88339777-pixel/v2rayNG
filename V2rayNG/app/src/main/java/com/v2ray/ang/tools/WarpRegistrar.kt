package com.v2ray.ang.tools

import android.util.Base64
import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Makes a free Cloudflare WARP account (the same registration the official app does), builds a
 * WireGuard profile from it and stores it in the "WARP" group.
 */
object WarpRegistrar {

    const val GROUP_NAME = "WARP"

    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val DEFAULT_HOST = "162.159.192.1"
    private const val DEFAULT_PORT = 2408

    /** How many different endpoints are tried for each new account, and how many are kept. */
    private const val CANDIDATES = 40
    private const val KEEP = 3
    private const val TEST_TIMEOUT_MS = 120_000L

    // Cloudflare's WARP endpoint address blocks and the UDP ports they answer on.
    private val ENDPOINT_BLOCKS = listOf(
        "162.159.192", "162.159.193", "162.159.195",
        "188.114.96", "188.114.97", "188.114.98", "188.114.99",
    )
    private val ENDPOINT_PORTS = intArrayOf(
        2408, 500, 1701, 4500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934, 939,
        942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014, 1018, 1070, 1074, 1180, 1387,
        1843, 2371, 2506, 3138, 3476, 3581, 3854, 4177, 4198, 4233, 5279, 5956, 7103, 7152,
        7156, 7281, 7559, 8319, 8742, 8854, 8886,
    )

    sealed interface Outcome {
        data class Created(val kept: Int, val bestMs: Long) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Makes the account, then tries many endpoints with it and keeps only the ones that really
     * connect on this internet line (the default WARP endpoint is blocked on some providers).
     */
    suspend fun create(): Outcome {
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
            val localAddress = if (v6.isNotEmpty()) "$v4/32,$v6/128" else "$v4/32"

            // One profile per candidate endpoint, all sharing the same account.
            val guidToEndpoint = LinkedHashMap<String, String>()
            for ((host, port) in endpointCandidates()) {
                val profile = ProfileItem.create(EConfigType.WIREGUARD)
                profile.subscriptionId = groupId
                profile.remarks = "WARP #$number"
                profile.server = host
                profile.serverPort = port.toString()
                profile.secretKey = b64(privateKey)
                profile.publicKey = peerKey
                profile.localAddress = localAddress
                profile.reserved = reservedCsv
                profile.mtu = 1280
                guidToEndpoint[MmkvManager.encodeServerConfig("", profile)] = "$host:$port"
            }

            val results = testServers(guidToEndpoint.keys.toList())
            val passed = guidToEndpoint.keys
                .filter { (results[it] ?: -1L) > 0L }
                .sortedBy { results[it] }

            val keep = passed.take(KEEP)
            val discard = guidToEndpoint.keys.filter { it !in keep }
            MmkvManager.removeServers(discard, groupId)

            if (keep.isEmpty()) {
                return Outcome.Failed(
                    "حساب WARP ساخته شد، اما هیچ‌کدام از ${guidToEndpoint.size} آدرس آزمایش‌شده روی این اینترنت وصل نشد. " +
                        "احتمالاً WARP روی این اینترنت بسته است؛ روی اینترنت دیگر (مثلاً سیم‌کارت) امتحان کنید."
                )
            }

            keep.forEach { guid ->
                MmkvManager.decodeServerConfig(guid)?.let { profile ->
                    profile.remarks = "WARP #$number · ${guidToEndpoint[guid]} · ${results[guid]} ms"
                    MmkvManager.encodeServerConfig(guid, profile)
                }
            }
            Outcome.Created(keep.size, results[keep.first()] ?: 0L)
        } catch (e: Exception) {
            Outcome.Failed("پاسخ سرویس WARP قابل استفاده نبود")
        }
    }

    /** The usual endpoint first, then random ones from the known WARP address blocks and ports. */
    private fun endpointCandidates(): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        out.add(DEFAULT_HOST to DEFAULT_PORT)
        var guard = 0
        while (out.size < CANDIDATES && guard < CANDIDATES * 10) {
            guard++
            val host = "${ENDPOINT_BLOCKS.random()}.${Random.nextInt(1, 255)}"
            out.add(host to ENDPOINT_PORTS.random())
        }
        return out.toList()
    }

    /** Real-tests the profiles with the app's own core; returns guid -> delay (negative = failed). */
    private suspend fun testServers(guids: List<String>): Map<String, Long> {
        val results = ConcurrentHashMap<String, Long>()
        val finished = CompletableDeferred<Unit>()
        val worker = RealPingWorkerService(
            context = AngApplication.application,
            guids = guids,
            onEvent = { event ->
                when (event) {
                    is RealPingEvent.Result -> {
                        results[event.guid] = event.delayMillis
                        MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                    }

                    is RealPingEvent.Finish -> finished.complete(Unit)
                    else -> Unit
                }
            }
        )
        worker.start()
        if (withTimeoutOrNull(TEST_TIMEOUT_MS) { finished.await() } == null) {
            worker.cancel()
        }
        return results
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
