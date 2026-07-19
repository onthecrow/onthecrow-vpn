package com.onthecrow.onthecrowvpn.vpn.di

import com.onthecrow.onthecrowvpn.vpn.AndroidInstalledAppsProvider
import com.onthecrow.onthecrowvpn.vpn.SplitTunnelAndroidSync
import com.onthecrow.onthecrowvpn.vpn.domain.InstalledAppsProvider
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

actual val vpnPlatformModule: Module = module {
    // Keep AndroidSplitTunnelState in sync with persisted settings from app start.
    single(createdAtStart = true) { SplitTunnelAndroidSync(get(), get()) }
    single { AndroidInstalledAppsProvider() } bind InstalledAppsProvider::class
}
