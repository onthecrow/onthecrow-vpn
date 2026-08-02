package com.onthecrow.deltavpn.firebase.di

import com.onthecrow.deltavpn.errorreporting.CrashReporter
import com.onthecrow.deltavpn.firebase.AnalyticsTracker
import com.onthecrow.deltavpn.firebase.FirestoreClient
import org.koin.dsl.module

val firebaseModule = module {
    single<AnalyticsTracker> { createAnalyticsTracker() }
    single<CrashReporter> { createCrashReporter() }
    single<FirestoreClient> { createFirestoreClient() }
}

internal expect fun createAnalyticsTracker(): AnalyticsTracker

internal expect fun createCrashReporter(): CrashReporter

internal expect fun createFirestoreClient(): FirestoreClient
