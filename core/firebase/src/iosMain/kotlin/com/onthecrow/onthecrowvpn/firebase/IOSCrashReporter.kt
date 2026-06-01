package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.CrashReporter
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Firebase.FIRApp
import platform.Firebase.FIRCrashlytics
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey

internal actual fun createCrashReporter(): CrashReporter = IOSCrashReporter()

@OptIn(ExperimentalForeignApi::class)
private class IOSCrashReporter : CrashReporter {
    override fun log(message: String) {
        getCrashlytics()?.setCustomValue(message, forKey = "last_log")
    }

    override fun recordException(throwable: Throwable) {
        val description = throwable.stackTraceToString()
        val error = NSError.errorWithDomain(
            domain = "com.onthecrow.onthecrowvpn.kotlin",
            code = 0,
            userInfo = mapOf(NSLocalizedDescriptionKey to description),
        )
        getCrashlytics()?.recordError(error)
    }

    override fun setUserId(userId: String?) {
        getCrashlytics()?.setUserID(userId)
    }

    override fun setKey(
        key: String,
        value: String,
    ) {
        getCrashlytics()?.setCustomValue(value, forKey = key)
    }

    private fun getCrashlytics(): FIRCrashlytics? {
        if (FIRApp.defaultApp() == null) return null
        return FIRCrashlytics.crashlytics()
    }
}
