package com.onthecrow.onthecrowvpn.analytics.events

/**
 * One analytics event: a Firebase event [name] plus its already-privacy-rendered [params].
 *
 * These are `internal` data classes, one per file in this package. Callers never construct them — they
 * call the corresponding [com.onthecrow.onthecrowvpn.analytics.AnalyticsManager] method, which builds
 * the event and hands it to the implementation. Each event owns its own wire rendering in [params], so
 * the bounded, privacy-safe shape of every event lives in exactly one place.
 */
internal interface AnalyticsEvent {
    /** Firebase event name (snake_case, <=40 chars, stable once shipped). */
    val name: String

    /** Rendered parameters (String-only, per Firebase's Bundle contract). Bounded values only. */
    fun params(): Map<String, String>
}
