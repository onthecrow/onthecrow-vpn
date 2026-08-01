package com.onthecrow.onthecrowvpn.analytics.events

import com.onthecrow.onthecrowvpn.analytics.ConsentResult

/** `vpn_consent_result` — the user accepted or dismissed the VPN disclosure. */
internal data class VpnConsentResultEvent(
    val result: ConsentResult,
) : AnalyticsEvent {
    override val name = "vpn_consent_result"
    override fun params() = mapOf("result" to result.raw())
}
