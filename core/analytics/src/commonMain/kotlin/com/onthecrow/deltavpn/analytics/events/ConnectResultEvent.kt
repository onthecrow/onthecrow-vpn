package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.ConnectFailureCategory

/**
 * `connect_result` — the connect attempt's outcome at the controller boundary. [failureCategory] is
 * `null` for a started attempt; non-null adds `result=failed` plus the bounded category.
 */
internal data class ConnectResultEvent(
    val failureCategory: ConnectFailureCategory?,
) : AnalyticsEvent {
    override val name = "connect_result"
    override fun params(): Map<String, String> = buildMap {
        put("result", if (failureCategory == null) "started" else "failed")
        failureCategory?.let { put("failure_category", it.raw()) }
    }
}
