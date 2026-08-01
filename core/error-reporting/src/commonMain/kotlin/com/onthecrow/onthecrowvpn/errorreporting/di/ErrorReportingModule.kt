package com.onthecrow.onthecrowvpn.errorreporting.di

import com.onthecrow.onthecrowvpn.errorreporting.ErrorReporter
import com.onthecrow.onthecrowvpn.errorreporting.ErrorReporterImpl
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Provides [ErrorReporter]. Depends on `CrashReporter` from `firebaseModule`, so that module must be
 * loaded too (it is, via `applicationModule`). Real Crashlytics on Android/iOS/macOS, stdout on JVM.
 */
val errorReportingModule = module {
    single { ErrorReporterImpl(get()) } bind ErrorReporter::class
}
