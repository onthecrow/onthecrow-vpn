package com.onthecrow.onthecrowvpn

import com.onthecrow.onthecrowvpn.di.applicationModule
import com.onthecrow.onthecrowvpn.firebase.AnalyticsTracker
import com.onthecrow.onthecrowvpn.firebase.FirebaseInitializer
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
        getKoin().get<AnalyticsTracker>().logEvent(
            name = "app_initialized",
            parameters = mapOf("platform" to platform.name),
        )
        isInitialized = true
    }
}
