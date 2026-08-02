package com.onthecrow.deltavpn.errorreporting

/**
 * Low-level, cross-platform crash-reporting **port** — the thin seam over the platform crash backend
 * (Firebase Crashlytics on Android/iOS/macOS, stdout on JVM). It lives here, in the reporting-abstraction
 * module, rather than in `core:firebase` on purpose: [ErrorReporter] (the high-level, privacy-scrubbing
 * API most code should call) depends only on this port, so a consumer can supply a Crashlytics-only
 * binding without dragging the full Firebase graph (Analytics/**Firestore**/gRPC). That is exactly what
 * the iOS Network-Extension tunnel does via `core:firebase-crashlytics`; the main app supplies the full
 * binding via `core:firebase`.
 *
 * Prefer [ErrorReporter.report] at call sites — it drops the (secret-bearing) throwable message for you.
 * Touch this port directly only inside a platform binding.
 */
interface CrashReporter {
    fun log(message: String)

    fun recordException(throwable: Throwable)

    fun setUserId(userId: String?)

    fun setKey(
        key: String,
        value: String,
    )
}
