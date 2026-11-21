package com.v2ray.ang.tools

import com.v2ray.ang.handler.SettingsManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Small HTTP helper for the tools: tries directly, then through the app's own local proxy if it is up. */
object ToolNet {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun get(url: String): String? = withFallback { c -> execute(c, Request.Builder().url(url).build()) }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? =
        withFallback { c ->
            val builder = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
            headers.forEach { (k, v) -> builder.header(k, v) }
            execute(c, builder.build())
        }

    private fun withFallback(call: (OkHttpClient) -> String?): String? {
        call(client)?.let { return it }
        val port = SettingsManager.getHttpPort()
        if (port <= 0) return null
        val proxied = client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .build()
        return call(proxied)
    }

    private fun execute(c: OkHttpClient, request: Request): String? = try {
        c.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
