package com.onthecrow.deltavpn.firebase.crashlytics

import com.onthecrow.deltavpn.errorreporting.CrashReporter
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics
import dev.gitlive.firebase.initialize

/**
 * macOS actuals for the NE crash surface. macOS has no grpcpp problem, so it binds Crashlytics through
 * the GitLive KMP SDK (same as core:firebase's macosMain) instead of a cinterop. Every call is guarded:
 * `Firebase.crashlytics` throws if no app is configured, so a macOS extension bundle without a
 * `GoogleService-Info.plist` reports nothing rather than crashing.
 */
internal actual fun configureCrashlytics() {
    runCatching { Firebase.initialize() }
}

internal actual fun createCrashReporter(): CrashReporter = MacosCrashReporter

private object MacosCrashReporter : CrashReporter {
    override fun log(message: String) {
        runCatching { Firebase.crashlytics.log(message) }
    }

    override fun recordException(throwable: Throwable) {
        runCatching { Firebase.crashlytics.recordException(throwable) }
    }

    override fun setUserId(userId: String?) {
        runCatching { Firebase.crashlytics.setUserId(userId.orEmpty()) }
    }

    override fun setKey(key: String, value: String) {
        runCatching { Firebase.crashlytics.setCustomKey(key, value) }
    }
}
