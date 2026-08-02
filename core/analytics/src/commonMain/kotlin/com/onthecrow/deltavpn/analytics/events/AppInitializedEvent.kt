package com.onthecrow.deltavpn.analytics.events

/**
 * `app_initialized` — one per main-process start; the funnel-entry denominator.
 *
 * [platform] is the coarse OS label (e.g. `"Android 34"`), the only non-enum value in the whole API —
 * it is an OS version, never a device name/model/serial.
 */
internal data class AppInitializedEvent(
    val platform: String,
) : AnalyticsEvent {
    override val name = "app_initialized"
    override fun params() = mapOf("platform" to platform)
}
