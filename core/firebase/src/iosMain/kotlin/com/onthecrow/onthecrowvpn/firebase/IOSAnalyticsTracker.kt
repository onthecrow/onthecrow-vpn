package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Firebase.FIRAnalytics
import platform.Firebase.FIRApp

internal actual fun createAnalyticsTracker(): AnalyticsTracker = IOSAnalyticsTracker()

@OptIn(ExperimentalForeignApi::class)
private class IOSAnalyticsTracker : AnalyticsTracker {
    override fun logEvent(
        name: String,
        parameters: Map<String, String>,
    ) {
        if (!isFirebaseConfigured()) return
        FIRAnalytics.logEventWithName(
            name = name,
            parameters = parameters.toFirebaseParameters(),
        )
    }

    override fun setUserId(userId: String?) {
        if (!isFirebaseConfigured()) return
        FIRAnalytics.setUserID(userId)
    }

    override fun setUserProperty(
        name: String,
        value: String?,
    ) {
        if (!isFirebaseConfigured()) return
        FIRAnalytics.setUserPropertyString(value, forName = name)
    }

    private fun isFirebaseConfigured(): Boolean = FIRApp.defaultApp() != null

    private fun Map<String, String>.toFirebaseParameters(): Map<Any?, *>? {
        if (isEmpty()) return null
        return mapKeys { (key, _) -> key }
    }
}
