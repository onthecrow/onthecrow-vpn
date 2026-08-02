package com.onthecrow.deltavpn.analytics.events

/**
 * `subscription_revoked_remote` — a source was removed remotely. [wasActive] = it held the active
 * selection (an involuntary disconnect). Boolean only — never the source title/id/host.
 */
internal data class SubscriptionRevokedRemoteEvent(
    val wasActive: Boolean,
) : AnalyticsEvent {
    override val name = "subscription_revoked_remote"
    override fun params() = mapOf("was_active" to wasActive.toString())
}
