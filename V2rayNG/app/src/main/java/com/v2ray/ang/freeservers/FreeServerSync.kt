package com.v2ray.ang.freeservers

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects free public Hysteria2 / WireGuard servers from open GitHub lists and keeps them in
 * their own group ([GROUP_NAME]) of the server list, so users get working servers without
 * the admin adding anything by hand.
 *
 * The group is separate from the panel's configs: the panel sync replaces only the default
 * group, and this sync replaces only this one.
 */
object FreeServerSync {

    const val GROUP_NAME = "سرورهای رایگان"

    /** Servers per source list, and in total, so the server list stays light. */
    private const val PER_SOURCE = 25
    private const val MAX_TOTAL = 80
    private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val FETCH_TIMEOUT_MS = 8000
    private const val MAX_LINES_PER_SOURCE = 5000

    private val LINK = Regex("^(hysteria2|hy2|wireguard)://\\S+$", RegexOption.IGNORE_CASE)
    private val HOST_PORT = Regex("://(?:[^@/?#]*@)?([^/?#]+)")

    private val running = AtomicBoolean(false)

    sealed interface Outcome {
        data class Imported(val count: Int) : Outcome
        data object Skipped : Outcome
        data class Failed(val msg: String) : Outcome
    }

    /**
     * Refreshes the group. Without [force] it does nothing while the last refresh is
     * recent. Safe to call often; only one refresh runs at a time.
     */
    suspend fun refresh(force: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext Outcome.Skipped
        try {
            val existing = findGroup()
            if (!force && existing != null) {
                val item = existing.subscription
                val hasServers = MmkvManager.decodeServerList(existing.guid).isNotEmpty()
                val recent = System.currentTimeMillis() - item.lastUpdated < REFRESH_INTERVAL_MS
                if (hasServers && recent) return@withContext Outcome.Skipped
            }

            var lists = fetchAll(useProxy = false)
            if (lists.all { it.isEmpty() }) lists = fetchAll(useProxy = true)

            val links = pick(lists)
            if (links.isEmpty()) return@withContext Outcome.Failed("سرور رایگانی دریافت نشد")

            val groupId = existing?.guid ?: createGroup()
                ?: return@withContext Outcome.Failed("ساخت گروه سرورهای رایگان انجام نشد")

            // append = false replaces only this group's old servers.
            val imported = AngConfigManager.importBatchConfig(links.joinToString("\n"), groupId, false)
            if (imported.first <= 0) {
                return@withContext Outcome.Failed("سرورهای دریافت‌شده قابل استفاده نبودند")
            }

            MmkvManager.decodeSubscription(groupId)?.let { item ->
                item.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(groupId, item)
            }

            Outcome.Imported(imported.first)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Free server sync failed", e)
            Outcome.Failed("خطا در دریافت سرورهای رایگان")
        } finally {
            running.set(false)
        }
    }

    private fun findGroup() =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == GROUP_NAME }

    private fun createGroup(): String? {
        // An empty url keeps the normal "update subscriptions" from touching this group.
        MmkvManager.encodeSubscription("", SubscriptionItem(remarks = GROUP_NAME))
        return findGroup()?.guid
    }

    /** Downloads every source in parallel; a source that fails gives an empty list. */
    private suspend fun fetchAll(useProxy: Boolean): List<List<String>> = coroutineScope {
        FreeServerSources.ALL.map { source ->
            async(Dispatchers.IO) { fetchSource(source, useProxy) }
        }.awaitAll()
    }

    private fun fetchSource(source: FreeServerSource, useProxy: Boolean): List<String> {
        for (url in listOf(source.rawUrl, source.mirrorUrl)) {
            val text = download(url, useProxy)
            if (text.isNotBlank()) {
                val links = extractLinks(text)
                if (links.isNotEmpty()) return links
            }
        }
        return emptyList()
    }

    private fun download(url: String, useProxy: Boolean): String {
        return try {
            val request = if (useProxy) {
                UrlContentRequest(
                    url = url,
                    timeout = FETCH_TIMEOUT_MS,
                    httpPort = SettingsManager.getHttpPort(),
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword(),
                )
            } else {
                UrlContentRequest(url = url, timeout = FETCH_TIMEOUT_MS)
            }
            HttpUtil.getUrlContentWithUserAgent(request)
        } catch (e: Exception) {
            LogUtil.i(AppConfig.TAG, "Free list not reachable: $url")
            ""
        }
    }

    private fun extractLinks(text: String): List<String> {
        fun scan(body: String) = body.lineSequence()
            .take(MAX_LINES_PER_SOURCE)
            .map { it.trim() }
            .filter { LINK.matches(it) }
            .toList()

        val plain = scan(text)
        if (plain.isNotEmpty()) return plain
        // Some lists are published base64-encoded.
        return scan(Utils.decode(text))
    }

    /** Takes a few random servers from each list, removes duplicates and caps the total. */
    private fun pick(lists: List<List<String>>): List<String> {
        val seen = HashSet<String>()
        val picked = ArrayList<String>()

        // Round-robin over the lists so one big list cannot crowd out the others.
        val queues = lists.map { it.shuffled().take(PER_SOURCE).toMutableList() }
        var added = true
        while (picked.size < MAX_TOTAL && added) {
            added = false
            for (queue in queues) {
                if (picked.size >= MAX_TOTAL) break
                while (queue.isNotEmpty()) {
                    val link = queue.removeAt(0)
                    val key = HOST_PORT.find(link)?.groupValues?.get(1)?.lowercase() ?: link
                    if (seen.add(key)) {
                        picked.add(link)
                        added = true
                        break
                    }
                }
            }
        }
        return picked
    }
}
