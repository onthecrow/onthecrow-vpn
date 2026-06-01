package com.onthecrow.onthecrowvpn.firebase.di

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.onthecrow.onthecrowvpn.firebase.AndroidFirebaseEnvironment
import com.onthecrow.onthecrowvpn.firebase.CrashReporter

internal actual fun createCrashReporter(): CrashReporter = AndroidCrashReporter()

private class AndroidCrashReporter : CrashReporter {
    override fun log(message: String) {
        getCrashlytics()?.log(message)
    }

    override fun recordException(throwable: Throwable) {
        getCrashlytics()?.recordException(throwable)
    }

    override fun setUserId(userId: String?) {
        getCrashlytics()?.setUserId(userId.orEmpty())
    }

    override fun setKey(
        key: String,
        value: String,
    ) {
        getCrashlytics()?.setCustomKey(key, value)
    }

    private fun getCrashlytics(): FirebaseCrashlytics? {
        if (!AndroidFirebaseEnvironment.isConfigured()) return null
        return FirebaseCrashlytics.getInstance()
    }
}
