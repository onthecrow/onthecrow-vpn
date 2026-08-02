package com.onthecrow.deltavpn.analytics.events

/**
 * `diagnostics_log_shared` — the diagnostic-log share sheet was opened (a support/frustration proxy).
 * No parameters: never the log path/URI/size/contents or the chosen share-target package.
 */
internal class DiagnosticsLogSharedEvent : AnalyticsEvent {
    override val name = "diagnostics_log_shared"
    override fun params(): Map<String, String> = emptyMap()
}
