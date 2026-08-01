package com.onthecrow.onthecrowvpn.errorreporting

/**
 * Default [ErrorReporter]. Wraps the caught throwable in a [ReportedError] that keeps the original type
 * but replaces the (potentially secret-bearing) message with the bounded domain, tags it with the
 * `error_domain` custom key, and hands it to [CrashReporter.recordException].
 */
internal class ErrorReporterImpl(
    private val crashReporter: CrashReporter,
) : ErrorReporter {

    override fun report(domain: ErrorDomain, error: Throwable) {
        crashReporter.setKey(DOMAIN_KEY, domain.raw())
        crashReporter.recordException(ReportedError(domain, error))
    }

    private companion object {
        const val DOMAIN_KEY = "error_domain"
    }
}

/**
 * A privacy-scrubbed stand-in for a caught throwable: the message is the original type + domain only
 * (never the original message, which can embed a server/credential/URL). The cause is deliberately not
 * chained — chaining would upload the original message again.
 *
 * It captures ITS OWN stack (created inside [ErrorReporterImpl.report], i.e. at the catch site), rather
 * than copying the original throwable's stack. Copying `Throwable.stackTrace` is a JVM-only API that
 * does not compile for Kotlin/Native (iOS/macOS) — and this module targets both. The catch-site stack is
 * the cross-platform, leak-free choice; the bounded [ErrorDomain] plus the type in the message locate it.
 */
private class ReportedError(
    domain: ErrorDomain,
    original: Throwable,
) : Exception("${original.typeName()} [${domain.raw()}]")

private fun ErrorDomain.raw(): String = name.lowercase()

private fun Throwable.typeName(): String = this::class.simpleName ?: "Throwable"
