package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ActiveBundleState
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
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
    fun loadingThenBundleArrivalPopulatesState() = runTest {
        var state = reducer.reduce(ConnectionState(idInput = "abc"), ConnectionEvent.OnLoadStarted)
        assertTrue(state.isLoadingBundle)
        assertTrue(state.isEditingId)
        assertNull(state.bundle)

        state = reducer.reduce(
            state,
            ConnectionEvent.OnActiveBundleChanged(
                ActiveBundleState(savedBundleId = "abc", bundle = bundle("abc"), selectedConfigId = "c1"),
            ),
        )
        assertEquals("abc", state.idInput)
        assertEquals(bundle("abc"), state.bundle)
        assertEquals("c1", state.selectedConfigId)
        assertFalse(state.isLoadingBundle)
        assertFalse(state.isEditingId)
        assertNull(state.bundleError)
    }

    @Test
    fun remoteRevocationClearsInputAndBundle() = runTest {
        val connected = ConnectionState(idInput = "abc", bundle = bundle("abc"), selectedConfigId = "c1")

        val state = reducer.reduce(
            connected,
            ConnectionEvent.OnActiveBundleChanged(
                ActiveBundleState(revoked = true, error = "This configuration is no longer available"),
            ),
        )

        assertEquals("", state.idInput)
        assertNull(state.bundle)
        assertEquals("This configuration is no longer available", state.bundleError)
        assertTrue(state.isEditingId)
    }

    @Test
    fun connectionLifecycleExposesBusyConnectedAndErrorStates() = runTest {
        var state = ConnectionState(bundle = bundle("abc"), selectedConfigId = "c1")

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.PreparingPermission))
        assertTrue(state.isBusy)
        assertFalse(state.canConnect)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Connected))
        assertTrue(state.isConnected)
        assertFalse(state.isBusy)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Error("cannot connect")))
        assertEquals("cannot connect", state.snackbarMessage)
        assertFalse(state.isBusy)

        state = reducer.reduce(state, ConnectionEvent.OnSnackbarShown)
        assertNull(state.snackbarMessage)
    }

    private fun bundle(id: String) = ConfigBundle(
        id = id,
        name = "sample",
        createdAt = 0,
        updatedAt = 0,
        configs = listOf(RemoteConfig(id = "c1", name = "Server", url = "vless://x")),
    )
}
