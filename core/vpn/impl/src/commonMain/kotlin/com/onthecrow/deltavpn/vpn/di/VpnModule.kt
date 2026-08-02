package com.onthecrow.deltavpn.vpn.di

import com.onthecrow.deltavpn.vpn.PlatformVpnController
import com.onthecrow.deltavpn.vpn.PlatformVpnPermissionRequester
import com.onthecrow.deltavpn.vpn.VpnController
import com.onthecrow.deltavpn.vpn.VpnPermissionRequester
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Platform-specific VPN wiring (Android: split-tunnel sync). Empty on non-Android targets. The
 * SplitTunnelRepository binding lives in the connection logic module (the KMP module that hosts
 * DataStore), since core/vpn targets macOS where DataStore isn't available.
 */
expect val vpnPlatformModule: Module

val vpnModule = module {
    single { PlatformVpnController() } bind VpnController::class
    single { PlatformVpnPermissionRequester() } bind VpnPermissionRequester::class

    includes(vpnPlatformModule)
}
