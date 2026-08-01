package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.analytics.analytics

internal actual fun createAnalyticsTracker(): AnalyticsTracker = MacosAnalyticsTracker

/**
 * macOS Analytics via the GitLive KMP SDK. NOTE: Google does not officially support Firebase Analytics
 * on macOS, so the underlying Apple SDK may no-op even though this compiles and calls it. Kept for
 * parity and in case that changes. Guarded like the crash reporter (no Firebase app → no-op).
 */
private object MacosAnalyticsTracker : AnalyticsTracker {
    override fun logEvent(name: String, parameters: Map<String, String>) {
        runCatching { Firebase.analytics.logEvent(name, parameters) }
    }

    override fun setUserId(userId: String?) {
        runCatching { Firebase.analytics.setUserId(userId) }
    }

    override fun setUserProperty(name: String, value: String?) {
        runCatching { Firebase.analytics.setUserProperty(name, value.orEmpty()) }
    }
}
