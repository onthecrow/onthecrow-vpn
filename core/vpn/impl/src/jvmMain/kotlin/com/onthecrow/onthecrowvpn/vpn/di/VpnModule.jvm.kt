package com.onthecrow.onthecrowvpn.vpn.di

import com.onthecrow.onthecrowvpn.vpn.domain.InstalledAppsProvider
import com.onthecrow.onthecrowvpn.vpn.model.InstalledApp
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

// Split tunneling is Android-only for now. The provider is still bound so the shared settings screen
// can inject it unconditionally — it simply has no apps to offer here.
actual val vpnPlatformModule: Module = module {
    single {
        object : InstalledAppsProvider {
            override suspend fun installedApps(): List<InstalledApp> = emptyList()
        }
    } bind InstalledAppsProvider::class
}
