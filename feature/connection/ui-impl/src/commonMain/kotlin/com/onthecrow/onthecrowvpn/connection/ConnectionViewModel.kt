package com.onthecrow.onthecrowvpn.connection

import androidx.lifecycle.viewModelScope
import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.LoadBundleUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ObserveActiveBundleUseCase
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase
import com.onthecrow.onthecrowvpn.uicore.BaseViewModel
import com.onthecrow.onthecrowvpn.vpn.ConnectResult
import com.onthecrow.onthecrowvpn.vpn.VpnController
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionRequester
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionResult
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class ConnectionViewModel(
    observeActiveBundleUseCase: ObserveActiveBundleUseCase,
    private val loadBundleUseCase: LoadBundleUseCase,
    private val selectConfigUseCase: SelectConfigUseCase,
    private val prepareConnectionConfigUseCase: PrepareConnectionConfigUseCase,
    private val vpnController: VpnController,
    private val vpnPermissionRequester: VpnPermissionRequester,
    reducer: ConnectionReducer,
) : BaseViewModel<ConnectionEvent, ConnectionState, ConnectionReducer>(reducer) {

    init {
        eventFlow.onEach { event ->
            when (event) {
                ConnectionEvent.OnLoadClick -> handleLoadClick()
                is ConnectionEvent.OnConfigSelected -> handleConfigSelected(event.configId)
                ConnectionEvent.OnConnectClick -> handleConnectClick()
                ConnectionEvent.OnDisconnectClick -> handleDisconnectClick()
                else -> Unit
            }
        }.launchIn(viewModelScope)

        observeActiveBundleUseCase()
            .onEach { onEvent(ConnectionEvent.OnActiveBundleChanged(it)) }
            .launchIn(viewModelScope)

        vpnController.status
            .onEach { onEvent(ConnectionEvent.OnConnectionStatusChanged(it)) }
            .launchIn(viewModelScope)
    }

    override fun getInitialState(): ConnectionState = ConnectionState()

    private fun handleLoadClick() {
        val id = state.value.idInput.trim()
        if (id.isBlank()) return
        viewModelScope.launch {
            onEvent(ConnectionEvent.OnLoadStarted)
            loadBundleUseCase(id)
        }
    }

    private fun handleConfigSelected(configId: String) {
        viewModelScope.launch { selectConfigUseCase(configId) }
    }

    private fun handleConnectClick() {
        if (state.value.isConnected) {
            handleDisconnectClick()
            return
        }
        val cfg = state.value.selectedConfig
        if (cfg == null) {
            onEvent(ConnectionEvent.OnSnackbarRequested("Select a configuration first"))
            return
        }
        viewModelScope.launch {
            when (val result = prepareConnectionConfigUseCase(cfg.url)) {
                is ConfigValidationResult.Invalid -> {
                    onEvent(ConnectionEvent.OnSnackbarRequested(result.message))
                }
                is ConfigValidationResult.Valid -> when (val perm = vpnPermissionRequester.requestPermission()) {
                    VpnPermissionResult.Granted -> when (val outcome = vpnController.connect(result.xrayJson)) {
                        ConnectResult.Started -> Unit
                        is ConnectResult.Failed -> onEvent(ConnectionEvent.OnSnackbarRequested(outcome.message))
                    }
                    is VpnPermissionResult.Denied -> onEvent(ConnectionEvent.OnSnackbarRequested(perm.message))
                }
            }
        }
    }

    private fun handleDisconnectClick() {
        viewModelScope.launch { vpnController.disconnect() }
    }
}
