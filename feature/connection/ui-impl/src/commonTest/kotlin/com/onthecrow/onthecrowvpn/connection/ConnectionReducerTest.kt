package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ConnectionConfigSummary
import com.onthecrow.onthecrowvpn.connection.model.ValidatedConnectionConfig
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ConnectionReducerTest {
    private val reducer = ConnectionReducer()

    @Test
    fun validationLifecycleUpdatesInputAndValidity() = runTest {
        val config = sampleConfig()

        var state = reducer.reduce(ConnectionState(), ConnectionEvent.OnValidationStarted("vless://sample"))
        assertEquals("vless://sample", state.rawInput)
        assertTrue(state.isValidating)
        assertNull(state.validationError)

        state = reducer.reduce(state, ConnectionEvent.OnValidationSucceeded(config))
        assertEquals(config, state.validatedConfig)
        assertFalse(state.isValidating)
        assertNull(state.validationError)

        state = reducer.reduce(state, ConnectionEvent.OnValidationFailed("bad config"))
        assertNull(state.validatedConfig)
        assertEquals("bad config", state.validationError)
        assertFalse(state.isValidating)
    }

    @Test
    fun manualInputChangeInvalidatesAcceptedConfig() = runTest {
        val state = ConnectionState(validatedConfig = sampleConfig())

        val updated = reducer.reduce(state, ConnectionEvent.OnInputChanged("hysteria2://sample"))

        assertEquals("hysteria2://sample", updated.rawInput)
        assertNull(updated.validatedConfig)
        assertNull(updated.validationError)
        assertFalse(updated.canConnect)
    }

    @Test
    fun connectionLifecycleExposesBusyConnectedAndErrorStates() = runTest {
        var state = ConnectionState(validatedConfig = sampleConfig())

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.PreparingPermission))
        assertTrue(state.isBusy)
        assertFalse(state.canConnect)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Connecting))
        assertTrue(state.isBusy)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Connected))
        assertTrue(state.isConnected)
        assertFalse(state.isBusy)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Disconnecting))
        assertTrue(state.isBusy)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Error("cannot connect")))
        assertEquals("cannot connect", state.snackbarMessage)
        assertFalse(state.isBusy)

        state = reducer.reduce(state, ConnectionEvent.OnSnackbarShown)
        assertNull(state.snackbarMessage)
    }

    private fun sampleConfig() = ValidatedConnectionConfig(
        rawConfig = "vless://sample",
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
