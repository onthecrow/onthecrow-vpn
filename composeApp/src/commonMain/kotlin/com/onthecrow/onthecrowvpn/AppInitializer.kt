package com.onthecrow.onthecrowvpn

import com.onthecrow.onthecrowvpn.di.applicationModule
import org.koin.core.context.startKoin

object AppInitializer {
    private var isInitialized = false

    fun initialize(platform: Platform) {
        if (isInitialized) return
        startKoin {
            modules(
                platform.platformModule,
                applicationModule,
            )
        }
        isInitialized = true
    }
}
