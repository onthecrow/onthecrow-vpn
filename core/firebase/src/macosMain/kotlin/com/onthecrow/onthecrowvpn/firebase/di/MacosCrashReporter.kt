package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.errorreporting.CrashReporter
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics

internal actual fun createCrashReporter(): CrashReporter = MacosCrashReporter

/**
 * Real macOS Crashlytics via the GitLive KMP SDK (Firebase Crashlytics supports macOS). Every call is
 * guarded — `Firebase.crashlytics` throws if no Firebase app is configured, so a build without a
 * `GoogleService-Info.plist` simply reports nothing instead of crashing.
 */
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
