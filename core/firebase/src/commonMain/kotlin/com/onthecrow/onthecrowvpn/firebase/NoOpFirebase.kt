package com.onthecrow.onthecrowvpn.firebase

internal object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun logEvent(
        name: String,
        parameters: Map<String, String>,
    ) = Unit

    override fun setUserId(userId: String?) = Unit

    override fun setUserProperty(
        name: String,
        value: String?,
    ) = Unit
}

internal object NoOpCrashReporter : CrashReporter {
    override fun log(message: String) = Unit

    override fun recordException(throwable: Throwable) = Unit

    override fun setUserId(userId: String?) = Unit

    override fun setKey(
        key: String,
        value: String,
    ) = Unit
}

internal object NoOpFirestoreClient : FirestoreClient {
    override val isAvailable: Boolean = false
}
