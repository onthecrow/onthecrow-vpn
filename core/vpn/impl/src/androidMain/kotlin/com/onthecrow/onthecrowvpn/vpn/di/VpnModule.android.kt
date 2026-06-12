package com.onthecrow.onthecrowvpn.vpn.di

import com.onthecrow.onthecrowvpn.vpn.SplitTunnelAndroidSync
import org.koin.core.module.Module
import org.koin.dsl.module

actual val vpnPlatformModule: Module = module {
    // Keep AndroidSplitTunnelState in sync with persisted settings from app start.
    single(createdAtStart = true) { SplitTunnelAndroidSync(get(), get()) }
}
