package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.LoadBundleUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ObserveActiveBundleUseCase
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ActiveBundleState
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.vpn.ConnectResult
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import com.onthecrow.onthecrowvpn.vpn.VpnController
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionRequester
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
internal class ConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun activeBundleStatePopulatesUi() = runTest(dispatcher) {
        val bundleFlow = MutableStateFlow(
            ActiveBundleState(savedBundleId = "abc", bundle = sampleBundle("abc"), selectedConfigId = "c1"),
        )

        val viewModel = createViewModel(bundleFlow)
        advanceUntilIdle()

        assertEquals("abc", viewModel.state.value.idInput)
        assertEquals(sampleBundle("abc"), viewModel.state.value.bundle)
        assertEquals("c1", viewModel.state.value.selectedConfigId)
    }

    @Test
    fun remoteRevocationClearsBundleAndShowsMessage() = runTest(dispatcher) {
        val bundleFlow = MutableStateFlow(
            ActiveBundleState(savedBundleId = "abc", bundle = sampleBundle("abc"), selectedConfigId = "c1"),
        )

        val viewModel = createViewModel(bundleFlow)
        advanceUntilIdle()

        // The orchestrator wiped local state and emitted a one-shot revoked signal.
        bundleFlow.value = ActiveBundleState(
            revoked = true,
            error = "This configuration is no longer available",
        )
        advanceUntilIdle()

        assertNull(viewModel.state.value.bundle)
        assertEquals("", viewModel.state.value.idInput)
        assertEquals("This configuration is no longer available", viewModel.state.value.snackbarMessage)
    }

    private fun createViewModel(
        bundleFlow: MutableStateFlow<ActiveBundleState>,
    ): ConnectionViewModel = ConnectionViewModel(
        observeActiveBundleUseCase = ObserveActiveBundleUseCase { bundleFlow },
        loadBundleUseCase = LoadBundleUseCase { },
        selectConfigUseCase = SelectConfigUseCase { },
        prepareConnectionConfigUseCase = PrepareConnectionConfigUseCase { ConfigValidationResult.Valid("{}") },
        vpnController = FakeVpnController(),
        vpnPermissionRequester = FakeVpnPermissionRequester(),
        reducer = ConnectionReducer(),
    )

    private class FakeVpnController : VpnController {
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
        override suspend fun connect(xrayJson: String): ConnectResult = ConnectResult.Started
        override suspend fun disconnect() = Unit
    }

    private class FakeVpnPermissionRequester : VpnPermissionRequester {
        override suspend fun requestPermission(): VpnPermissionResult = VpnPermissionResult.Granted
    }

    private fun sampleBundle(id: String) = ConfigBundle(
        id = id,
        name = "sample",
        createdAt = 0,
        updatedAt = 0,
        configs = listOf(RemoteConfig(id = "c1", name = "Server", url = "vless://x")),
    )
}
