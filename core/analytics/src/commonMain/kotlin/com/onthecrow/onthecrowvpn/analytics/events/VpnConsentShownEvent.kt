package com.onthecrow.onthecrowvpn.analytics.events

/** `vpn_consent_shown` — the prominent VPN disclosure was shown (first connect). No parameters. */
internal class VpnConsentShownEvent : AnalyticsEvent {
    override val name = "vpn_consent_shown"
    override fun params(): Map<String, String> = emptyMap()
}
