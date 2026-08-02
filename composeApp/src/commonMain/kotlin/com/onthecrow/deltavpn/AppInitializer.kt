package com.onthecrow.deltavpn

import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.di.applicationModule
import com.onthecrow.deltavpn.firebase.FirebaseInitializer
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform.getKoin

object AppInitializer {
    private var isInitialized = false

    fun initialize(platform: Platform) {
        if (isInitialized) return
        FirebaseInitializer.initialize(platform)
        startKoin {
            modules(
                platform.platformModule,
                applicationModule,
            )
        }
        getKoin().get<AnalyticsManager>().appInitialized(platform.name)
        isInitialized = true
    }
}
