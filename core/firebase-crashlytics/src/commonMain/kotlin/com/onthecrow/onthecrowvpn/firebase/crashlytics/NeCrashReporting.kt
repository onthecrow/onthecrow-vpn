package com.onthecrow.onthecrowvpn.firebase.crashlytics

import com.onthecrow.onthecrowvpn.errorreporting.CrashReporter
import com.onthecrow.onthecrowvpn.errorreporting.ErrorReporter

/**
 * The Crashlytics-only reporting surface for the iOS/macOS Network-Extension tunnel (`OnthecrowTunnelCore`).
 *
 * The NE runs in its **own process** with **no Koin container**, so — unlike the app, which resolves
 * [ErrorReporter] from `errorReportingModule` — it constructs one directly from here. This module binds
 * ONLY Firebase Crashlytics (never Analytics/Firestore/gRPC), so the appex stays slim; see the module's
 * `build.gradle.kts`.
 *
 * Usage from the tunnel: call [configure] once at startup, hold one [errorReporter], and call
 * [ErrorReporter.report] at catch sites. Everything is guarded — if `GoogleService-Info.plist` is not
 * embedded in the appex bundle, [configure] and every report simply no-op instead of crashing the tunnel.
 */
object NeCrashReporting {
    /**
     * Bootstrap Firebase in the extension process (`FIRApp.configure()` on iOS, `Firebase.initialize()`
     * on macOS). Idempotent and best-effort: a missing config disables reporting rather than failing.
     */
    fun configure() = configureCrashlytics()

    /**
     * A privacy-scrubbing [ErrorReporter] backed by the Crashlytics-only [CrashReporter] binding. Reuses
     * the same message-dropping [ErrorReporter] the rest of the app uses, so the NE honours the identical
     * privacy contract. No-ops until [configure] has run (and Crashlytics is configured).
     */
    fun errorReporter(): ErrorReporter = ErrorReporter(createCrashReporter())
}

/** Bootstraps Firebase for the extension process. */
internal expect fun configureCrashlytics()

/** The Crashlytics-only [CrashReporter] binding for this platform. */
internal expect fun createCrashReporter(): CrashReporter
