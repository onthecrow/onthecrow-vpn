package com.onthecrow.deltavpn.analytics.events

/**
 * `connect_tapped` — the Connect control was tapped. [alreadyRunning] = the tap acted as Stop, so it
 * must be excluded from connect-success rate.
 */
internal data class ConnectTappedEvent(
    val hadSelection: Boolean,
    val alreadyRunning: Boolean,
) : AnalyticsEvent {
    override val name = "connect_tapped"
    override fun params() = mapOf(
        "had_selection" to hadSelection.toString(),
        "already_running" to alreadyRunning.toString(),
    )
}
