package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.domain.ConfigValidationResult
import com.onthecrow.onthecrowvpn.connection.domain.ObserveSavedConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.domain.ValidateConnectionConfigUseCase
import com.onthecrow.onthecrowvpn.connection.model.ConnectionConfigSummary
import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import com.onthecrow.onthecrowvpn.vpn.ConnectResult
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import com.onthecrow.onthecrowvpn.vpn.VpnController
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionRequester
import com.onthecrow.onthecrowvpn.vpn.VpnPermissionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    fun startupRevalidatesSavedConfig() = runTest(dispatcher) {
        val config = sampleConfig(rawConfig = "vless://saved")
        val validator = FakeValidateConnectionConfigUseCase(ConfigValidationResult.Valid(config))

        val viewModel = createViewModel(
            savedConfig = " vless://saved ",
            validator = validator,
        )
        advanceUntilIdle()

        assertEquals(listOf(" vless://saved "), validator.validatedInputs)
        assertEquals("vless://saved", viewModel.state.value.rawInput)
        assertEquals(config, viewModel.state.value.validatedConfig)
        assertNull(viewModel.state.value.validationError)
    }

    @Test
    fun startupKeepsSavedTextWhenSavedConfigIsRejected() = runTest(dispatcher) {
        val validator = FakeValidateConnectionConfigUseCase(
            ConfigValidationResult.Invalid("unsupported payload"),
        )

        val viewModel = createViewModel(
            savedConfig = " broken ",
            validator = validator,
        )
        advanceUntilIdle()

        assertEquals("broken", viewModel.state.value.rawInput)
        assertEquals("unsupported payload", viewModel.state.value.validationError)
        assertEquals("Saved config is no longer accepted: unsupported payload", viewModel.state.value.snackbarMessage)
    }

    private fun createViewModel(
        savedConfig: String?,
        validator: ValidateConnectionConfigUseCase,
    ): ConnectionViewModel {
        return ConnectionViewModel(
            observeSavedConnectionConfigUseCase = ObserveSavedConnectionConfigUseCase { flowOf(savedConfig) },
            validateConnectionConfigUseCase = validator,
            vpnController = FakeVpnController(),
            vpnPermissionRequester = FakeVpnPermissionRequester(),
            reducer = ConnectionReducer(),
        )
    }

    private class FakeValidateConnectionConfigUseCase(
        private val result: ConfigValidationResult,
    ) : ValidateConnectionConfigUseCase {
        val validatedInputs = mutableListOf<String>()

        override suspend fun invoke(rawConfig: String): ConfigValidationResult {
            validatedInputs += rawConfig
            return result
        }
    }

    private class FakeVpnController : VpnController {
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
        override suspend fun connect(config: ValidatedConnectionConfig): ConnectResult = ConnectResult.Started
        override suspend fun disconnect() = Unit
    }

    private class FakeVpnPermissionRequester : VpnPermissionRequester {
        override suspend fun requestPermission(): VpnPermissionResult = VpnPermissionResult.Granted
    }

    private fun sampleConfig(rawConfig: String) = ValidatedConnectionConfig(
        rawConfig = rawConfig,
        xrayJson = """{"outbounds":[{"protocol":"vless"}]}""",
        summary = ConnectionConfigSummary(
            title = "sample",
            protocol = "vless",
            address = "78.17.84.51",
            port = 443,
            security = "reality",
            transport = "tcp",
            sni = "www.microsoft.com",
            outboundCount = 1,
            isAdvanced = false,
        ),
    )
}
