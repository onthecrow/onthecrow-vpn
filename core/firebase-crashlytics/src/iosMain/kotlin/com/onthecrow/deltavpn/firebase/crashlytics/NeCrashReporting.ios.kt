package com.onthecrow.deltavpn.firebase.crashlytics

import com.onthecrow.deltavpn.errorreporting.CrashReporter
import kotlinx.cinterop.ExperimentalForeignApi
import platform.FirebaseCrashlytics.FIRApp
import platform.FirebaseCrashlytics.FIRCrashlytics
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSLog

/**
 * iOS actuals for the NE crash surface. Binds Crashlytics through this module's own cinterop
 * (`platform.FirebaseCrashlytics`), which links ONLY the Crashlytics closure — no Firestore/gRPC.
 *
 * The extension is a separate process from the app, so it must configure its own [FIRApp]. It reads
 * `GoogleService-Info.plist` from the **appex** bundle (`NSBundle.mainBundle` here is the appex, not the
 * host app), so that plist must be copied into the appex at build time; otherwise this stays a silent
 * no-op and reporting is disabled.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun configureCrashlytics() {
    if (FIRApp.defaultApp() != null) return
    val configPath = NSBundle.mainBundle.pathForResource(name = "GoogleService-Info", ofType = "plist")
    if (configPath == null) {
        NSLog("Crashlytics config missing in the extension bundle. NE crash reporting is disabled.")
        return
    }
    FIRApp.configure()
}

internal actual fun createCrashReporter(): CrashReporter = IosCrashReporter

@OptIn(ExperimentalForeignApi::class)
private object IosCrashReporter : CrashReporter {
    override fun log(message: String) {
        crashlytics()?.setCustomValue(message, forKey = "last_log")
    }

    override fun recordException(throwable: Throwable) {
        val error = NSError.errorWithDomain(
            domain = "com.onthecrow.deltavpn.tunnel",
            code = 0,
            userInfo = mapOf(NSLocalizedDescriptionKey to throwable.stackTraceToString()),
        )
        crashlytics()?.recordError(error)
    }

    override fun setUserId(userId: String?) {
        crashlytics()?.setUserID(userId)
    }

    override fun setKey(key: String, value: String) {
        crashlytics()?.setCustomValue(value, forKey = key)
    }

    private fun crashlytics(): FIRCrashlytics? {
        if (FIRApp.defaultApp() == null) return null
        return FIRCrashlytics.crashlytics()
    }
}
