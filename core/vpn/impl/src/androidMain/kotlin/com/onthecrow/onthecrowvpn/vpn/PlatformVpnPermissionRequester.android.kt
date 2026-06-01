package com.onthecrow.onthecrowvpn.vpn

actual class PlatformVpnPermissionRequester : VpnPermissionRequester {
    override suspend fun requestPermission(): VpnPermissionResult {
        AndroidVpnRuntime.status.value = ConnectionStatus.PreparingPermission
        return AndroidVpnPermissionBridge.requestPermission().also { result ->
            if (result is VpnPermissionResult.Denied) {
                AndroidVpnRuntime.status.value = ConnectionStatus.Error(result.message)
            }
        }
    }
}
