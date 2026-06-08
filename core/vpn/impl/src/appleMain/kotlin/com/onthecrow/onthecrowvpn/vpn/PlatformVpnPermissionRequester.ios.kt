package com.onthecrow.onthecrowvpn.vpn

actual class PlatformVpnPermissionRequester : VpnPermissionRequester {
    override suspend fun requestPermission(): VpnPermissionResult = VpnPermissionResult.Granted
}
