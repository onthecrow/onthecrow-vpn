package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.SourceGroup
import com.onthecrow.onthecrowvpn.uicore.Reducer
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal class ConnectionReducer : Reducer<ConnectionState, ConnectionEvent> {
    override suspend fun reduce(
        state: ConnectionState,
        event: ConnectionEvent,
    ): ConnectionState = when (event) {
        is ConnectionEvent.OnSourcesChanged -> {
            val s = event.state
            val liveKeys = s.groups.mapTo(mutableSetOf(), SourceGroup::sourceId)
            state.copy(
                groups = s.groups,
                selected = s.selected,
                selectedConfig = s.selectedConfig,
                // Prune UI state of groups that no longer exist (deleted / revoked).
                collapsedGroupKeys = state.collapsedGroupKeys intersect liveKeys,
                refreshingSourceIds = state.refreshingSourceIds intersect liveKeys,
            )
        }

        is ConnectionEvent.OnSetGroupCollapsed -> state.copy(
            collapsedGroupKeys = if (event.collapsed) {
                state.collapsedGroupKeys + event.groupKey
            } else {
                state.collapsedGroupKeys - event.groupKey
            },
        )

        // Resolved once at start (saved set with the cold-start active-group override already applied).
        // Flips [collapsedLoaded] so the group list may finally compose — with the right initial state.
        is ConnectionEvent.OnCollapsedLoaded -> state.copy(
            collapsedGroupKeys = event.collapsedGroupKeys,
            collapsedLoaded = true,
        )

        is ConnectionEvent.OnRefreshStateChanged -> state.copy(
            refreshingSourceIds = if (event.refreshing) {
                state.refreshingSourceIds + event.sourceId
            } else {
                state.refreshingSourceIds - event.sourceId
            },
        )

        is ConnectionEvent.OnConnectionStatusChanged -> state.copy(
            connectionStatus = event.status,
            snackbar = when (val status = event.status) {
                is ConnectionStatus.Error -> SnackbarNotice(status.message, isError = true)
                else -> state.snackbar
            },
        )

        is ConnectionEvent.OnSnackbarRequested ->
            state.copy(snackbar = SnackbarNotice(event.message, event.isError))

        ConnectionEvent.OnSnackbarShown -> state.copy(snackbar = null)

        // Side-effect-only events: handled in the ViewModel, no state change here.
        is ConnectionEvent.OnAddFromClipboard,
        is ConnectionEvent.OnConfigSelected,
        is ConnectionEvent.OnRefreshSourceClick,
        is ConnectionEvent.OnDeleteSourceClick,
        ConnectionEvent.OnConnectClick,
        ConnectionEvent.OnDisconnectClick,
        ConnectionEvent.OnSettingsClick,
        -> state
    }
}
