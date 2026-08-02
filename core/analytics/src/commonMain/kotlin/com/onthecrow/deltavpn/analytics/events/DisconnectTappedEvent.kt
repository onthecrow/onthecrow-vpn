package com.onthecrow.deltavpn.analytics.events

import com.onthecrow.deltavpn.analytics.DisconnectEntryPoint

/** `disconnect_tapped` — a deliberate stop, attributed to the surface it came from. */
internal data class DisconnectTappedEvent(
    val entryPoint: DisconnectEntryPoint,
) : AnalyticsEvent {
    override val name = "disconnect_tapped"
    override fun params() = mapOf("entry_point" to entryPoint.raw())
}
