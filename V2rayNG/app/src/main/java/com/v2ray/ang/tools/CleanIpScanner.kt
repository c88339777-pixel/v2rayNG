package com.v2ray.ang.tools

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/** Finds Cloudflare addresses that answer quickly on this network. */
object CleanIpScanner {

    data class Result(val ip: String, val latencyMs: Long, val colo: String)

    // Cloudflare's published IPv4 ranges.
    private val RANGES = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    private const val TEST_HOST = "speed.cloudflare.com"
    private const val CONCURRENCY = 40
    private val COLO = Regex("colo=([A-Z0-9]+)")

    suspend fun scan(count: Int, onProgress: (done: Int, total: Int) -> Unit): List<Result> = coroutineScope {
        val ips = randomIps(count)
        val permits = Semaphore(CONCURRENCY)
        val done = AtomicInteger(0)
        ips.map { ip ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    val result = probe(ip)
                    onProgress(done.incrementAndGet(), ips.size)
                    result
                }
            }
        }.awaitAll().filterNotNull().sortedBy { it.latencyMs }
    }

    private fun randomIps(count: Int): List<String> {
        val parsed = RANGES.map { cidr ->
            val base = cidr.substringBefore('/')
            val prefix = cidr.substringAfter('/').toInt()
            val octets = base.split('.').map { it.toLong() }
            val start = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
            start to (1L shl (32 - prefix))
        }
        val total = parsed.sumOf { it.second }
        val out = LinkedHashSet<String>()
        var guard = 0
        while (out.size < count && guard < count * 4) {
            guard++
            var pick = Random.nextLong(total)
            for ((start, size) in parsed) {
                if (pick < size) {
                    val offset = 1 + Random.nextLong(size - 2)
                    val ip = start + offset
                    out.add("${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}")
                    break
                }
                pick -= size
            }
        }
        return out.toList()
    }

    private fun probe(ip: String): Result? {
        val begin = System.nanoTime()
        var plain: Socket? = null
        var secure: SSLSocket? = null
        return try {
            plain = Socket()
            plain.connect(InetSocketAddress(ip, 443), 2000)
            plain.soTimeout = 3000

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            secure = factory.createSocket(plain, TEST_HOST, 443, true) as SSLSocket
            secure.soTimeout = 3000
            secure.startHandshake()

            val request = "GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TEST_HOST\r\n" +
                "User-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"
            secure.outputStream.write(request.toByteArray())
            secure.outputStream.flush()

            val buffer = ByteArray(2048)
            val text = StringBuilder()
            while (text.length < 4096) {
                val n = secure.inputStream.read(buffer)
                if (n <= 0) break
                text.append(String(buffer, 0, n))
            }

            val body = text.toString()
            val colo = COLO.find(body)?.groupValues?.get(1)
            if (body.startsWith("HTTP/1.1 200") && colo != null) {
                Result(ip, (System.nanoTime() - begin) / 1_000_000, colo)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try { secure?.close() } catch (_: Exception) {}
            try { plain?.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Copies the user's own TLS configs into a separate group, each one pointed at a clean address.
 * The original domain stays in the SNI and Host fields, so the server still recognises the config.
 */
object CleanIpApplier {

    const val GROUP_NAME = "آی‌پی تمیز"
    private const val IPS_USED = 3

    private val SUPPORTED = setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)

    /** Returns how many configs were created. */
    fun build(ips: List<CleanIpScanner.Result>): Int {
        if (ips.isEmpty()) return 0

        val sources = MmkvManager.decodeServerList("")
            .mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter {
                it.configType in SUPPORTED &&
                    it.security.equals("tls", ignoreCase = true) &&
                    !it.server.isNullOrBlank()
            }
        if (sources.isEmpty()) return 0

        val groupId = ServerGroups.ensure(GROUP_NAME) ?: return 0
        MmkvManager.removeServerViaSubid(groupId)

        var created = 0
        for (ip in ips.take(IPS_USED)) {
            for (source in sources) {
                val original = source.server.orEmpty()
                val copy: ProfileItem = source.copy(subscriptionId = groupId, addedTime = System.currentTimeMillis())
                if (copy.sni.isNullOrBlank()) copy.sni = original
                if (copy.host.isNullOrBlank()) copy.host = original
                copy.server = ip.ip
                copy.remarks = "${source.remarks} · ${ip.ip}"
                MmkvManager.encodeServerConfig("", copy)
                created++
            }
        }
        return created
    }
}
