package com.v2ray.ang.freeservers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Collects free public Hysteria2 / WireGuard / VLESS / VMess / Trojan / Shadowsocks servers from
 * open GitHub lists and keeps them in their own group ([GROUP_NAME]) of the server list.
 *
 * The group is separate from the panel's configs: the panel sync replaces only the default
 * group, and this sync only touches this one.
 *
 * Servers are tested on the user's own line and remembered per kind of network (Wi-Fi or mobile
 * data). A server is removed only when it failed on EVERY kind of network this user has used, so
 * someone who switches between a home Wi-Fi and a SIM card keeps what works on either. When the
 * network changes, the group is tested again and re-sorted for the new line.
 */
object FreeServerSync {

    const val GROUP_NAME = "سرورهای رایگان"

    /** New servers taken per source list, and per refresh. */
    private const val PER_SOURCE = 40
    private const val MAX_TOTAL = 200

    /** The group never grows beyond this; the slowest and failed ones go first. */
    private const val MAX_KEEP = 300

    /** A server is removed only after failing this many separate tests. */
    private const val MIN_FAILED_TESTS = 3

    private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val RETEST_MIN_GAP_MS = 10L * 60 * 1000
    private const val ATTEMPT_MIN_GAP_MS = 15L * 60 * 1000
    private const val TEST_TIMEOUT_MS = 8L * 60 * 1000
    private const val FETCH_TIMEOUT_MS = 8000
    private const val MAX_LINES_PER_SOURCE = 5000

    private const val KIND_WIFI = 1
    private const val KIND_MOBILE = 2

    private val LINK = Regex("^(hysteria2|hy2|wireguard|vless|vmess|trojan|ss)://\\S+$", RegexOption.IGNORE_CASE)
    private val HOST_PORT = Regex("://(?:[^@/?#]*@)?([^/?#]+)")

    private val running = AtomicBoolean(false)

    sealed interface Outcome {
        /** [count] is how many servers answered on the current network. */
        data class Imported(val count: Int) : Outcome
        data object Skipped : Outcome
        data class Failed(val msg: String) : Outcome
    }

    /**
     * Refreshes the group when it is due, or re-tests it when the network changed. Without
     * [force] it does nothing otherwise. Safe to call often; only one run happens at a time.
     */
    suspend fun refresh(
        force: Boolean = false,
        onGroupChanged: () -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext Outcome.Skipped
        try {
            val now = System.currentTimeMillis()
            val existing = findGroup()
            val kind = currentKind()
            val hasServers = existing != null && MmkvManager.decodeServerList(existing.guid).isNotEmpty()
            val stale = existing == null || now - existing.subscription.lastUpdated >= REFRESH_INTERVAL_MS

            val needsFetch = force || !hasServers || stale
            val networkChanged = kind != 0 && kind != State.lastKind && now - State.lastTestAt >= RETEST_MIN_GAP_MS

            if (!force) {
                if (!needsFetch && !networkChanged) return@withContext Outcome.Skipped
                // Do not hammer the network (or the battery) while it keeps failing.
                if (now - State.lastAttemptAt < ATTEMPT_MIN_GAP_MS) return@withContext Outcome.Skipped
            }
            State.lastAttemptAt = now

            var groupId = existing?.guid
            if (needsFetch) {
                var lists = fetchAll(useProxy = false)
                if (lists.all { it.isEmpty() }) lists = fetchAll(useProxy = true)

                val links = pick(lists)
                if (links.isEmpty() && !hasServers) {
                    return@withContext Outcome.Failed("سرور رایگانی دریافت نشد")
                }
                if (links.isNotEmpty()) {
                    val id = groupId ?: createGroup()
                        ?: return@withContext Outcome.Failed("ساخت گروه سرورهای رایگان انجام نشد")
                    groupId = id
                    // append = true keeps what is already there and skips servers it already has.
                    AngConfigManager.importBatchConfig(links.joinToString("\n"), id, true)
                    onGroupChanged()
                }
            }

            val id = groupId ?: return@withContext Outcome.Skipped
            val alive = testAndMaintain(id, kind)

            if (needsFetch && alive > 0) {
                MmkvManager.decodeSubscription(id)?.let { item ->
                    item.lastUpdated = System.currentTimeMillis()
                    MmkvManager.encodeSubscription(id, item)
                }
            }

            Outcome.Imported(alive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Free server sync failed", e)
            Outcome.Failed("خطا در دریافت سرورهای رایگان")
        } finally {
            running.set(false)
        }
    }

    /** Wi-Fi, mobile data, or 0 when it cannot be told (for example while a VPN is on). */
    private fun currentKind(): Int {
        return try {
            val cm = AngApplication.application
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return 0) ?: return 0
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> KIND_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> KIND_MOBILE
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Tests every server of the group on the current line, remembers who passed on which kind of
     * network, removes the servers that failed on all kinds this user has used, then sorts the
     * group for the current line. If nothing passes, nothing is removed (the test itself
     * probably failed). Returns how many servers answered.
     */
    private suspend fun testAndMaintain(groupId: String, kind: Int): Int {
        val guids = MmkvManager.decodeServerList(groupId)
        if (guids.isEmpty()) return 0

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

        val alive = results.values.count { it > 0L }
        if (results.isEmpty()) return 0

        val masks = State.loadMasks()
        if (kind != 0) {
            State.seenKinds = State.seenKinds or kind
            results.forEach { (guid, delay) ->
                val (tested, passed, fails) = masks[guid] ?: Triple(0, 0, 0)
                masks[guid] = if (delay > 0L) {
                    Triple(tested or kind, passed or kind, 0)
                } else {
                    Triple(tested or kind, passed and kind.inv(), fails + 1)
                }
            }
        }

        val seen = State.seenKinds
        if (kind != 0 && alive > 0 && seen != 0) {
            // Only a server that keeps failing, on every kind of network this user has used,
            // across several separate tests, is removed.
            val doomed = MmkvManager.decodeServerList(groupId).filter { guid ->
                val (tested, passed, fails) = masks[guid] ?: return@filter false
                (tested and seen) == seen && (passed and seen) == 0 && fails >= MIN_FAILED_TESTS
            }
            if (doomed.isNotEmpty()) MmkvManager.removeServers(doomed, groupId)
        }

        AngConfigManager.sortByTestResultsForSub(groupId)

        val sorted = MmkvManager.decodeServerList(groupId)
        if (sorted.size > MAX_KEEP) {
            MmkvManager.removeServers(sorted.drop(MAX_KEEP), groupId)
        }

        val remaining = MmkvManager.decodeServerList(groupId).toHashSet()
        masks.keys.retainAll(remaining)
        State.saveMasks(masks)

        if (kind != 0) State.lastKind = kind
        State.lastTestAt = System.currentTimeMillis()
        return alive
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

    /** What the sync remembers between runs. */
    private object State {
        private val prefs
            get() = AngApplication.application.getSharedPreferences("free_servers_state", Context.MODE_PRIVATE)

        /** Kinds of network (bit mask) this user has been on while the app tested servers. */
        var seenKinds: Int
            get() = prefs.getInt("seen", 0)
            set(value) = prefs.edit().putInt("seen", value).apply()

        var lastKind: Int
            get() = prefs.getInt("last_kind", 0)
            set(value) = prefs.edit().putInt("last_kind", value).apply()

        var lastTestAt: Long
            get() = prefs.getLong("last_test_at", 0L)
            set(value) = prefs.edit().putLong("last_test_at", value).apply()

        var lastAttemptAt: Long
            get() = prefs.getLong("last_attempt_at", 0L)
            set(value) = prefs.edit().putLong("last_attempt_at", value).apply()

        /** server guid -> (kinds it was tested on, kinds it passed on, failed tests in a row). */
        fun loadMasks(): MutableMap<String, Triple<Int, Int, Int>> {
            val out = HashMap<String, Triple<Int, Int, Int>>()
            val text = prefs.getString("masks", null) ?: return out
            try {
                val json = JSONObject(text)
                json.keys().forEach { key ->
                    val parts = json.getString(key).split(':')
                    if (parts.size == 3) {
                        out[key] = Triple(
                            parts[0].toIntOrNull() ?: 0,
                            parts[1].toIntOrNull() ?: 0,
                            parts[2].toIntOrNull() ?: 0
                        )
                    }
                }
            } catch (e: Exception) {
                // A damaged note just means servers get tested again.
            }
            return out
        }

        fun saveMasks(masks: Map<String, Triple<Int, Int, Int>>) {
            val json = JSONObject()
            masks.forEach { (guid, v) -> json.put(guid, "${v.first}:${v.second}:${v.third}") }
            prefs.edit().putString("masks", json.toString()).apply()
        }
    }
}
