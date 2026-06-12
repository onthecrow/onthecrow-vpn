package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.AddSourceResult
import com.onthecrow.onthecrowvpn.connection.domain.AddSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.ObserveConfigSourcesUseCase
import com.onthecrow.onthecrowvpn.connection.domain.PrepareConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.RefreshResult
import com.onthecrow.onthecrowvpn.connection.domain.RefreshSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.RemoveSourceUseCase
import com.onthecrow.onthecrowvpn.connection.domain.SelectConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigRow
import com.onthecrow.onthecrowvpn.connection.model.ConfigSourcesState
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.connection.model.SourceGroup
import com.onthecrow.onthecrowvpn.connection.model.SourceKind
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun sourcesStatePopulatesUi() = runTest(dispatcher) {
        val sourcesFlow = MutableStateFlow(
            ConfigSourcesState(
                groups = listOf(group("s1")),
                selected = ConfigRef("s1", "c1"),
                selectedConfig = config("c1"),
            ),
        )

        val viewModel = createViewModel(sourcesFlow)
        advanceUntilIdle()

        assertEquals(listOf(group("s1")), viewModel.state.value.groups)
        assertEquals(ConfigRef("s1", "c1"), viewModel.state.value.selected)
        assertEquals(config("c1"), viewModel.state.value.selectedConfig)
    }

    @Test
    fun remoteRevocationShowsErrorSnackbar() = runTest(dispatcher) {
        val sourcesFlow = MutableStateFlow(
            ConfigSourcesState(groups = listOf(group("s1"))),
        )

        val viewModel = createViewModel(sourcesFlow)
        advanceUntilIdle()

        // Orchestrator removed the source and emitted the one-shot revocation signal.
        sourcesFlow.value = ConfigSourcesState(
            groups = emptyList(),
            revokedActiveSelection = true,
            revokedSourceTitles = listOf("sample"),
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.groups.isEmpty())
        assertEquals(
            SnackbarNotice("Subscription “sample” is no longer available", isError = true),
            viewModel.state.value.snackbar,
        )
    }

    @Test
    fun invalidAddShowsErrorSnackbarAndNothingChanges() = runTest(dispatcher) {
        val sourcesFlow = MutableStateFlow(ConfigSourcesState())
        var addCalled = false
        val viewModel = createViewModel(
            sourcesFlow,
            addSourceUseCase = object : AddSourceUseCase {
                override suspend fun addSubscriptionId(raw: String): AddSourceResult {
                    addCalled = true
                    return AddSourceResult.Invalid("Bundle not found")
                }

                override suspend fun addSubscriptionUrl(raw: String) = AddSourceResult.Added
                override suspend fun addXrayLink(raw: String) = AddSourceResult.Added
            },
        )
        advanceUntilIdle()

        viewModel.onEvent(
            ConnectionEvent.OnAddFromClipboard(ConnectionEvent.AddKind.SUBSCRIPTION_ID, "dead-id"),
        )
        advanceUntilIdle()

        assertTrue(addCalled)
        assertEquals(SnackbarNotice("Bundle not found", isError = true), viewModel.state.value.snackbar)
        assertTrue(viewModel.state.value.groups.isEmpty())
    }

    @Test
    fun emptyClipboardShowsErrorWithoutCallingUseCase() = runTest(dispatcher) {
        val sourcesFlow = MutableStateFlow(ConfigSourcesState())
        var addCalled = false
        val viewModel = createViewModel(
            sourcesFlow,
            addSourceUseCase = object : AddSourceUseCase {
                override suspend fun addSubscriptionId(raw: String): AddSourceResult {
                    addCalled = true
                    return AddSourceResult.Added
                }

                override suspend fun addSubscriptionUrl(raw: String) = AddSourceResult.Added
                override suspend fun addXrayLink(raw: String) = AddSourceResult.Added
            },
        )
        advanceUntilIdle()

        viewModel.onEvent(
            ConnectionEvent.OnAddFromClipboard(ConnectionEvent.AddKind.SUBSCRIPTION_ID, "  "),
        )
        advanceUntilIdle()

        assertFalse(addCalled)
        assertEquals(SnackbarNotice("Clipboard is empty", isError = true), viewModel.state.value.snackbar)
    }

    private fun createViewModel(
        sourcesFlow: MutableStateFlow<ConfigSourcesState>,
        addSourceUseCase: AddSourceUseCase = NoopAddSourceUseCase,
    ): ConnectionViewModel = ConnectionViewModel(
        observeConfigSourcesUseCase = ObserveConfigSourcesUseCase { sourcesFlow },
        addSourceUseCase = addSourceUseCase,
        removeSourceUseCase = RemoveSourceUseCase { },
        refreshSourceUseCase = RefreshSourceUseCase { RefreshResult.Ok },
        selectConfigUseCase = SelectConfigUseCase { },
        prepareConnectionConfigUseCase = PrepareConnectionConfigUseCase { ConfigValidationResult.Valid("{}") },
        vpnController = FakeVpnController(),
        vpnPermissionRequester = FakeVpnPermissionRequester(),
        reducer = ConnectionReducer(),
    )

    private object NoopAddSourceUseCase : AddSourceUseCase {
        override suspend fun addSubscriptionId(raw: String) = AddSourceResult.Added
        override suspend fun addSubscriptionUrl(raw: String) = AddSourceResult.Added
        override suspend fun addXrayLink(raw: String) = AddSourceResult.Added
    }

    private class FakeVpnController : VpnController {
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
        override suspend fun connect(xrayJson: String): ConnectResult = ConnectResult.Started
        override suspend fun disconnect() = Unit
    }

    private class FakeVpnPermissionRequester : VpnPermissionRequester {
        override suspend fun requestPermission(): VpnPermissionResult = VpnPermissionResult.Granted
    }

    private fun config(id: String) = RemoteConfig(id = id, name = "Server", url = "vless://x")

    private fun group(sourceId: String) = SourceGroup(
        sourceId = sourceId,
        kind = SourceKind.SUBSCRIPTION_ID,
        title = "sample",
        subtitle = "bundle-id",
        rows = listOf(ConfigRow(ConfigRef(sourceId, "c1"), config("c1"))),
        addedAt = 1L,
    )
}
