package com.onthecrow.onthecrowvpn.connection

import androidx.lifecycle.viewModelScope
import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.ObserveSavedConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ValidateConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.uicore.BaseViewModel
import com.onthecrow.onthecrowvpn.vpn.ConnectResult
import com.onthecrow.onthecrowvpn.vpn.VpnController
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionRequester
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionResult
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

internal class ConnectionViewModel(
    private val observeSavedConnectionConfigUseCase: ObserveSavedConnectionConfigUseCase,
    private val validateConnectionConfigUseCase: ValidateConnectionConfigUseCase,
    private val vpnController: VpnController,
    private val vpnPermissionRequester: VpnPermissionRequester,
    reducer: ConnectionReducer,
) : BaseViewModel<ConnectionEvent, ConnectionState, ConnectionReducer>(reducer) {

    init {
        eventFlow.onEach { event ->
            when (event) {
                ConnectionEvent.OnApplyClick -> validateCurrentInput()
                ConnectionEvent.OnConnectClick -> connectOrDisconnect()
                ConnectionEvent.OnDisconnectClick -> disconnect()
                else -> Unit
            }
        }.launchIn(viewModelScope)

        vpnController.status
            .onEach { onEvent(ConnectionEvent.OnConnectionStatusChanged(it)) }
            .launchIn(viewModelScope)

        observeSavedConnectionConfigUseCase()
            .filterNotNull()
            .take(1)
            .onEach { savedConfig -> validate(savedConfig, showRestoredError = true) }
            .launchIn(viewModelScope)
    }

    override fun getInitialState(): ConnectionState = ConnectionState()

    private fun validateCurrentInput() {
        validate(state.value.rawInput, showRestoredError = false)
    }

    private fun validate(rawConfig: String, showRestoredError: Boolean) {
        viewModelScope.launch {
            onEvent(ConnectionEvent.OnValidationStarted(rawConfig.trim()))
            when (val result = validateConnectionConfigUseCase(rawConfig)) {
                is ConfigValidationResult.Valid -> onEvent(ConnectionEvent.OnValidationSucceeded(result.config))
                is ConfigValidationResult.Invalid -> {
                    onEvent(ConnectionEvent.OnValidationFailed(result.message))
                    if (showRestoredError) {
                        onEvent(ConnectionEvent.OnSnackbarRequested("Saved config is no longer accepted: ${result.message}"))
                    }
                }
            }
        }
    }

    private fun connectOrDisconnect() {
        if (state.value.isConnected) {
            disconnect()
            return
        }
        val config = state.value.validatedConfig
        if (config == null) {
            onEvent(ConnectionEvent.OnSnackbarRequested("Apply a valid configuration first"))
            return
        }
        viewModelScope.launch {
            when (val permission = vpnPermissionRequester.requestPermission()) {
                VpnPermissionResult.Granted -> when (val result = vpnController.connect(config)) {
                    ConnectResult.Started -> Unit
                    is ConnectResult.Failed -> onEvent(ConnectionEvent.OnSnackbarRequested(result.message))
                }

                is VpnPermissionResult.Denied -> onEvent(ConnectionEvent.OnSnackbarRequested(permission.message))
            }
        }
    }

    private fun disconnect() {
        viewModelScope.launch {
            vpnController.disconnect()
        }
    }
}
