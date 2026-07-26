package com.onthecrow.onthecrowvpn.connection

import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigRow
import com.onthecrow.onthecrowvpn.connection.model.ConfigSourcesState
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.connection.model.SourceGroup
import com.onthecrow.onthecrowvpn.connection.model.SourceKind
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
    fun sourcesChangedPopulatesGroupsAndSelection() = runTest {
        val sourcesState = ConfigSourcesState(
            groups = listOf(group("s1")),
            selected = ConfigRef("s1", "c1"),
            selectedConfig = config("c1"),
        )

        val state = reducer.reduce(ConnectionState(), ConnectionEvent.OnSourcesChanged(sourcesState))

        assertEquals(listOf(group("s1")), state.groups)
        assertEquals(ConfigRef("s1", "c1"), state.selected)
        assertEquals(config("c1"), state.selectedConfig)
        assertTrue(state.hasAnySource)
        assertTrue(state.canConnect)
    }

    @Test
    fun sourcesChangedPrunesCollapseAndRefreshStateOfRemovedGroups() = runTest {
        val initial = ConnectionState(
            groups = listOf(group("s1"), group("s2")),
            collapsedGroupKeys = setOf("s1", "s2"),
            refreshingSourceIds = setOf("s2"),
        )

        val state = reducer.reduce(
            initial,
            ConnectionEvent.OnSourcesChanged(ConfigSourcesState(groups = listOf(group("s1")))),
        )

        assertEquals(setOf("s1"), state.collapsedGroupKeys)
        assertTrue(state.refreshingSourceIds.isEmpty())
    }

    @Test
    fun setGroupCollapsedAppliesTargetState() = runTest {
        var state = reducer.reduce(ConnectionState(), ConnectionEvent.OnSetGroupCollapsed("s1", collapsed = true))
        assertEquals(setOf("s1"), state.collapsedGroupKeys)

        // Idempotent: collapsing again keeps a single key (no toggle semantics).
        state = reducer.reduce(state, ConnectionEvent.OnSetGroupCollapsed("s1", collapsed = true))
        assertEquals(setOf("s1"), state.collapsedGroupKeys)

        state = reducer.reduce(state, ConnectionEvent.OnSetGroupCollapsed("s1", collapsed = false))
        assertTrue(state.collapsedGroupKeys.isEmpty())
    }

    @Test
    fun collapsedLoadedSeedsSetAndUnblocksComposition() = runTest {
        val state = reducer.reduce(
            ConnectionState(),
            ConnectionEvent.OnCollapsedLoaded(setOf("s1", "s2")),
        )
        assertEquals(setOf("s1", "s2"), state.collapsedGroupKeys)
        assertTrue(state.collapsedLoaded)
    }

    @Test
    fun connectionErrorProducesErrorSnackbar() = runTest {
        var state = reducer.reduce(
            ConnectionState(),
            ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Error("cannot connect")),
        )
        assertEquals(SnackbarNotice("cannot connect", isError = true), state.snackbar)

        state = reducer.reduce(state, ConnectionEvent.OnSnackbarShown)
        assertNull(state.snackbar)
    }

    @Test
    fun connectionLifecycleExposesBusyAndConnectedStates() = runTest {
        val withSelection = ConnectionState(
            groups = listOf(group("s1")),
            selected = ConfigRef("s1", "c1"),
            selectedConfig = config("c1"),
        )

        var state = reducer.reduce(
            withSelection,
            ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.PreparingPermission),
        )
        assertTrue(state.isBusy)
        // The button stays live mid-transition (canConnect no longer gates on !isBusy): a transition
        // shows progress but must never remove the only way out. See ConnectionState.canConnect.
        assertTrue(state.canConnect)

        state = reducer.reduce(state, ConnectionEvent.OnConnectionStatusChanged(ConnectionStatus.Connected))
        assertTrue(state.isConnected)
        assertFalse(state.isBusy)
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
