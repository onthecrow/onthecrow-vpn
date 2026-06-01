package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.uicore.State
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal data class ConnectionState(
    val idInput: String = "",
    val isEditingId: Boolean = true,
    val isLoadingBundle: Boolean = false,
    val bundleError: String? = null,
    val bundle: ConfigBundle? = null,
    val selectedConfigId: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val snackbarMessage: String? = null,
) : State {
    val selectedConfig: RemoteConfig?
        get() = bundle?.configs?.firstOrNull { it.id == selectedConfigId }

    val canLoad: Boolean
        get() = idInput.isNotBlank() && !isLoadingBundle

    val canConnect: Boolean
        get() = selectedConfig != null && !isBusy

    val isConnected: Boolean
        get() = connectionStatus is ConnectionStatus.Connected

    val isBusy: Boolean
        get() = connectionStatus is ConnectionStatus.PreparingPermission ||
            connectionStatus is ConnectionStatus.Connecting ||
            connectionStatus is ConnectionStatus.Disconnecting
}
