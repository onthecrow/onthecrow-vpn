package com.onthecrow.onthecrowvpn.errorreporting

/**
 * Reports caught, non-fatal errors for aggregate crash diagnostics.
 *
 * Call [report] at a catch site where an **unexpected** error was swallowed and you want fleet-wide
 * visibility without asking the user to send their log. The implementation ([ErrorReporterImpl])
 * forwards to the cross-platform `CrashReporter` — Firebase Crashlytics on Android/iOS, stdout on JVM —
 * so callers stay platform-agnostic and never touch Crashlytics directly. Uncaught crashes are captured
 * by Crashlytics automatically and do **not** need this; this is only for errors you catch.
 *
 * ### Privacy contract (this is a VPN — read before calling)
 * A reported error is uploaded off-device, so it is held to the same discipline as analytics. The
 * implementation **drops the throwable's message** before upload and keeps only its type and stack trace
 * plus the bounded [ErrorDomain] — because a caught throwable's message routinely carries a server
 * host/port, credential, config URL, or destination (e.g. `"xray start failed: <host>"`, a ktor error
 * embedding a subscription URL). Never work around this by putting raw detail into the domain, and never
 * call the underlying `CrashReporter.setUserId` with a real id (it would create a Data-safety *User ID*).
 * A stack trace (class + line) is safe; the message is not.
 *
 * ### What to report vs skip
 * Report genuinely unexpected, actionable failures (engine/recovery/IPC/fetch/persistence/boot errors).
 * Do **not** report expected control-flow caught by design — probe timeouts (fire every few seconds),
 * `close()`/`release()`/flush cleanups, capability/lookup fallbacks — reporting those would flood
 * Crashlytics with non-actionable noise and burn its quota. Keep the local `OtcLog` line for full detail.
 *
 * ### Behaviour
 * Fire-and-forget and cheap; it no-ops unless Crashlytics is configured (a real `google-services.json`),
 * so calling from debug or a test is harmless.
 */
interface ErrorReporter {
    /**
     * Record a caught non-fatal error.
     * @param domain the subsystem it came from (a bounded label, never raw data).
     * @param error the caught throwable; its type and stack are kept, its **message is dropped**.
     */
    fun report(domain: ErrorDomain, error: Throwable)
}

/**
 * Build the default [ErrorReporter] on top of a [CrashReporter] binding. Use this where there is no DI
 * container to resolve [ErrorReporter] — notably the iOS Network-Extension process, which has no Koin and
 * constructs its reporter directly from `core:firebase-crashlytics`'s Crashlytics-only [CrashReporter].
 * In the app process, prefer resolving [ErrorReporter] from Koin (`errorReportingModule`) instead.
 */
fun ErrorReporter(crashReporter: CrashReporter): ErrorReporter = ErrorReporterImpl(crashReporter)
