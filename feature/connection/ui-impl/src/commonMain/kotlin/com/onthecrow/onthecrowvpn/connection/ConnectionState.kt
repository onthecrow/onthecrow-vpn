package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.connection.model.SourceGroup
import com.onthecrow.onthecrowvpn.uicore.State
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal data class SnackbarNotice(
    val message: String,
    val isError: Boolean = false,
)

internal data class ConnectionState(
    val groups: List<SourceGroup> = emptyList(),
    val selected: ConfigRef? = null,
    val selectedConfig: RemoteConfig? = null,
    /** Group keys ([SourceGroup.sourceId]) the user collapsed. In-memory only (resets on restart). */
    val collapsedGroupKeys: Set<String> = emptySet(),
    /** Sources with a refresh in flight (spinner instead of the refresh glyph; re-taps ignored). */
    val refreshingSourceIds: Set<String> = emptySet(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val snackbar: SnackbarNotice? = null,
) : State {
    val hasAnySource: Boolean get() = groups.isNotEmpty()

    val canConnect: Boolean
        get() = selectedConfig != null && !isBusy

    val isConnected: Boolean
        get() = connectionStatus is ConnectionStatus.Connected

    val isBusy: Boolean
        get() = connectionStatus is ConnectionStatus.PreparingPermission ||
            connectionStatus is ConnectionStatus.Connecting ||
            connectionStatus is ConnectionStatus.Disconnecting
}
