package com.v2ray.ang.tools

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager

/** Finds or creates a named group in the server list (a group without a url is never auto-updated). */
object ServerGroups {

    fun ensure(name: String): String? {
        find(name)?.let { return it }
        MmkvManager.encodeSubscription("", SubscriptionItem(remarks = name))
        return find(name)
    }

    private fun find(name: String): String? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == name }?.guid
}

/** Set by the tools screen when it changed the server list, so the main screen reloads its groups. */
object ToolsSignal {
    @Volatile
    var changed: Boolean = false
}
