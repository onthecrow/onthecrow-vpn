package com.onthecrow.onthecrowvpn.vpn

interface VpnPermissionRequester {
    suspend fun requestPermission(): VpnPermissionResult
}

sealed interface VpnPermissionResult {
    data object Granted : VpnPermissionResult
    data class Denied(val message: String) : VpnPermissionResult
}
