package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigSourcesState
import com.onthecrow.onthecrowvpn.uicore.Event
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal sealed interface ConnectionEvent : Event {
    enum class AddKind { SUBSCRIPTION_ID, SUBSCRIPTION_URL, XRAY_LINK }

    // User actions
    /** Clipboard is read in the composable (the only place it exists) and shipped with the event. */
    data class OnAddFromClipboard(val kind: AddKind, val clipboardText: String?) : ConnectionEvent
    data class OnConfigSelected(val ref: ConfigRef) : ConnectionEvent
    data class OnToggleGroupCollapsed(val groupKey: String) : ConnectionEvent
    data class OnRefreshSourceClick(val sourceId: String) : ConnectionEvent
    /** Whole group via the header menu, or a single xray link via its per-link source id (swipe). */
    data class OnDeleteSourceClick(val sourceId: String) : ConnectionEvent
    data object OnConnectClick : ConnectionEvent
    data object OnDisconnectClick : ConnectionEvent
    data object OnSnackbarShown : ConnectionEvent

    // Internal events
    data class OnSourcesChanged(val state: ConfigSourcesState) : ConnectionEvent
    data class OnConnectionStatusChanged(val status: ConnectionStatus) : ConnectionEvent
    data class OnSnackbarRequested(val message: String, val isError: Boolean = false) : ConnectionEvent
    data class OnRefreshStateChanged(val sourceId: String, val refreshing: Boolean) : ConnectionEvent
}
