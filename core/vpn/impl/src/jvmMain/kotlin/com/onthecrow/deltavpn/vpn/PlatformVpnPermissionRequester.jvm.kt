package com.onthecrow.deltavpn.vpn

actual class PlatformVpnPermissionRequester : VpnPermissionRequester {
    override suspend fun requestPermission(): VpnPermissionResult = VpnPermissionResult.Granted
}
