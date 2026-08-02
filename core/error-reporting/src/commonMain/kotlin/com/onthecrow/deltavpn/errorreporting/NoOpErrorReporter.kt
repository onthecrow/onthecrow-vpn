package com.onthecrow.deltavpn.errorreporting

/** An [ErrorReporter] that does nothing. For tests and any wiring that needs the dependency inertly. */
object NoOpErrorReporter : ErrorReporter {
    override fun report(domain: ErrorDomain, error: Throwable) = Unit
}
