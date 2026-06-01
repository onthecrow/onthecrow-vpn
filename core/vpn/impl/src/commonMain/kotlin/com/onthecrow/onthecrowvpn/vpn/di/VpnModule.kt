package com.onthecrow.onthecrowvpn.vpn.di

import com.onthecrow.onthecrowvpn.vpn.PlatformVpnController
import com.onthecrow.onthecrowvpn.vpn.PlatformVpnPermissionRequester
import com.onthecrow.onthecrowvpn.vpn.VpnController
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionRequester
import org.koin.dsl.bind
import org.koin.dsl.module

val vpnModule = module {
    single { PlatformVpnController() } bind VpnController::class
    single { PlatformVpnPermissionRequester() } bind VpnPermissionRequester::class
}
