package com.onthecrow.deltavpn.analytics.di

import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.analytics.AnalyticsManagerImpl
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Provides [AnalyticsManager]. Depends on `AnalyticsTracker` from `firebaseModule`, so that module
 * must be loaded too (it is, via `applicationModule`).
 */
val analyticsModule = module {
    single { AnalyticsManagerImpl(get()) } bind AnalyticsManager::class
}
