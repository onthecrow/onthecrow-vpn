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
    /** Group keys ([SourceGroup.sourceId]) the user collapsed. Persisted between sessions. */
    val collapsedGroupKeys: Set<String> = emptySet(),
    /**
     * The persisted collapsed set (and the cold-start active-group override) has been resolved. Until
     * then the group list is withheld from composition so groups never render expanded-then-collapse
     * (which would animate the collapse on every launch).
     */
    val collapsedLoaded: Boolean = false,
    /** Sources with a refresh in flight (spinner instead of the refresh glyph; re-taps ignored). */
    val refreshingSourceIds: Set<String> = emptySet(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val snackbar: SnackbarNotice? = null,
) : State {
    val hasAnySource: Boolean get() = groups.isNotEmpty()

    /**
     * The button is live whenever there is something to act on — including mid-transition.
     *
     * It used to also require `!isBusy`, which meant any status that lingered took the only control on
     * the screen away with it. A tunnel that never confirmed left the user unable to cancel, stop, or
     * do anything at all. A transition is a reason to show progress, never a reason to remove the way
     * out; the service tolerates a disconnect at any point in a connect and stands down.
     */
    val canConnect: Boolean
        get() = selectedConfig != null

    val isConnected: Boolean
        get() = connectionStatus is ConnectionStatus.Connected

    val isBusy: Boolean
        get() = connectionStatus is ConnectionStatus.PreparingPermission ||
            connectionStatus is ConnectionStatus.Connecting ||
            connectionStatus is ConnectionStatus.Disconnecting
}
