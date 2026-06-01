package com.onthecrow.onthecrowvpn.firebase

interface AnalyticsTracker {
    fun logEvent(
        name: String,
        parameters: Map<String, String> = emptyMap(),
    )

    fun setUserId(userId: String?)

    fun setUserProperty(
        name: String,
        value: String?,
    )
}
