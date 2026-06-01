package com.onthecrow.onthecrowvpn.firebase.di

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import com.onthecrow.onthecrowvpn.firebase.AndroidFirebaseEnvironment

internal actual fun createAnalyticsTracker(): AnalyticsTracker = AndroidAnalyticsTracker()

private class AndroidAnalyticsTracker : AnalyticsTracker {
    override fun logEvent(
        name: String,
        parameters: Map<String, String>,
    ) {
        val analytics = getAnalytics() ?: return
        analytics.logEvent(name, parameters.toBundle())
    }

    override fun setUserId(userId: String?) {
        getAnalytics()?.setUserId(userId)
    }

    override fun setUserProperty(
        name: String,
        value: String?,
    ) {
        getAnalytics()?.setUserProperty(name, value)
    }

    private fun getAnalytics(): FirebaseAnalytics? {
        val application = AndroidFirebaseEnvironment.getApplication() ?: return null
        if (!AndroidFirebaseEnvironment.isConfigured()) return null
        return FirebaseAnalytics.getInstance(application)
    }

    private fun Map<String, String>.toBundle(): Bundle =
        Bundle(size).also { bundle ->
            forEach { (key, value) -> bundle.putString(key, value) }
        }
}
