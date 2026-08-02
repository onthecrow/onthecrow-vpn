package com.onthecrow.deltavpn.vpn.di

import com.onthecrow.deltavpn.vpn.AndroidInstalledAppsProvider
import com.onthecrow.deltavpn.vpn.SplitTunnelAndroidSync
import com.onthecrow.deltavpn.vpn.domain.InstalledAppsProvider
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val vpnPlatformModule: Module = module {
    // Keep AndroidSplitTunnelState in sync with persisted settings from app start.
    single(createdAtStart = true) { SplitTunnelAndroidSync(get(), get()) }
    single { AndroidInstalledAppsProvider(get()) } bind InstalledAppsProvider::class
}
