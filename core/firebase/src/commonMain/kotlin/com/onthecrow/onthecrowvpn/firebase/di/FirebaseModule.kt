package com.onthecrow.onthecrowvpn.firebase.di

import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import com.onthecrow.onthecrowvpn.firebase.CrashReporter
import org.koin.dsl.module

val firebaseModule = module {
    single<AnalyticsTracker> { createAnalyticsTracker() }
    single<CrashReporter> { createCrashReporter() }
}

internal expect fun createAnalyticsTracker(): AnalyticsTracker

internal expect fun createCrashReporter(): CrashReporter
