package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.connection.model.ConfigRef
import com.onthecrow.deltavpn.connection.model.ConfigSourcesState
import com.onthecrow.deltavpn.uicore.Event
import com.onthecrow.deltavpn.vpn.ConnectionStatus

internal sealed interface ConnectionEvent : Event {
    enum class AddKind { SUBSCRIPTION_ID, SUBSCRIPTION_URL, XRAY_LINK }

    // User actions
    /** Clipboard is read in the composable (the only place it exists) and shipped with the event. */
    data class OnAddFromClipboard(val kind: AddKind, val clipboardText: String?) : ConnectionEvent
    data class OnConfigSelected(val ref: ConfigRef) : ConnectionEvent
    /** Explicit target state (the composable knows the current collapsed value) — persisted per key. */
    data class OnSetGroupCollapsed(val groupKey: String, val collapsed: Boolean) : ConnectionEvent
    data class OnRefreshSourceClick(val sourceId: String) : ConnectionEvent
    /** Whole group via the header menu, or a single xray link via its per-link source id (swipe). */
    data class OnDeleteSourceClick(val sourceId: String) : ConnectionEvent
    data object OnConnectClick : ConnectionEvent
    data object OnDisconnectClick : ConnectionEvent
    data object OnSettingsClick : ConnectionEvent
    data object OnSnackbarShown : ConnectionEvent
    /** User accepted the VPN disclosure; the tunnel connect proceeds and the acceptance is persisted. */
    data object OnConsentAccepted : ConnectionEvent
    /** User dismissed the VPN disclosure without accepting (button or back) — no connect. */
    data object OnConsentDeclined : ConnectionEvent

    // Internal events
    /**
     * The persisted collapsed set, resolved once at start with the cold-start active-group expand
     * already folded in. Seeds [ConnectionState.collapsedGroupKeys] and unblocks group composition.
     */
    data class OnCollapsedLoaded(val collapsedGroupKeys: Set<String>) : ConnectionEvent
    data class OnSourcesChanged(val state: ConfigSourcesState) : ConnectionEvent
    data class OnConnectionStatusChanged(val status: ConnectionStatus) : ConnectionEvent
    data class OnSnackbarRequested(val message: String, val isError: Boolean = false) : ConnectionEvent
    data class OnRefreshStateChanged(val sourceId: String, val refreshing: Boolean) : ConnectionEvent
    /** Emitted by the ViewModel when Connect is tapped without prior consent; raises the disclosure. */
    data object OnConsentRequested : ConnectionEvent
}
