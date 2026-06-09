package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.uicore.Reducer
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

internal class ConnectionReducer : Reducer<ConnectionState, ConnectionEvent> {
    override suspend fun reduce(
        state: ConnectionState,
        event: ConnectionEvent,
    ): ConnectionState = when (event) {
        is ConnectionEvent.OnIdInputChanged -> state.copy(
            idInput = event.value,
            bundleError = null,
        )

        ConnectionEvent.OnLoadStarted -> state.copy(
            isLoadingBundle = true,
            isEditingId = true,
            bundleError = null,
            bundle = null,
            selectedConfigId = null,
        )

        ConnectionEvent.OnLoadClick -> state
        ConnectionEvent.OnEditIdClick -> state.copy(isEditingId = true)

        is ConnectionEvent.OnConfigSelected -> state.copy(selectedConfigId = event.configId)

        ConnectionEvent.OnConnectClick,
        ConnectionEvent.OnDisconnectClick -> state

        is ConnectionEvent.OnActiveBundleChanged -> {
            val s = event.state
            // Sticky loading: once OnLoadStarted set isLoadingBundle=true, keep it true
            // until either a bundle arrives or an error is reported. This hides the
            // transient null-id phase that the LoadBundle use case produces to force
            // a Firestore re-subscription.
            val hasDefinitiveResult = s.bundle != null || s.error != null
            state.copy(
                // On revocation the saved id was wiped — clear the input box so the config fully
                // disappears rather than leaving the now-dead id in place.
                idInput = if (s.revoked) "" else (s.savedBundleId ?: state.idInput),
                bundle = s.bundle,
                selectedConfigId = s.selectedConfigId,
                isLoadingBundle = if (hasDefinitiveResult) false else (s.isLoading || state.isLoadingBundle),
                bundleError = s.error,
                isEditingId = s.bundle == null || s.error != null,
            )
        }

        is ConnectionEvent.OnConnectionStatusChanged -> state.copy(
            connectionStatus = event.status,
            snackbarMessage = when (val status = event.status) {
                is ConnectionStatus.Error -> status.message
                else -> state.snackbarMessage
            },
        )

        is ConnectionEvent.OnSnackbarRequested -> state.copy(snackbarMessage = event.message)
        ConnectionEvent.OnSnackbarShown -> state.copy(snackbarMessage = null)
    }
}
