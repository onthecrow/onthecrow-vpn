package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.VpnPermissionOutcome

/** `vpn_permission_result` — outcome of the OS `VpnService.prepare()` dialog. */
internal data class VpnPermissionResultEvent(
    val result: VpnPermissionOutcome,
) : AnalyticsEvent {
    override val name = "vpn_permission_result"
    override fun params() = mapOf("result" to result.raw())
}
