package com.onthecrow.deltavpn.connection

import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.analytics.AutoRestartReason
import com.onthecrow.deltavpn.analytics.AutoRestartResult
import com.onthecrow.deltavpn.analytics.NoOpAnalyticsManager
import com.onthecrow.deltavpn.connection.domain.ConfigValidationResult
import com.onthecrow.deltavpn.connection.model.ConfigRef
import com.onthecrow.deltavpn.connection.model.ConfigSourcesState
import com.onthecrow.deltavpn.connection.model.RemoteConfig
import com.onthecrow.deltavpn.coroutines.ApplicationScopeProvider
import com.onthecrow.deltavpn.vpn.ConnectResult
import com.onthecrow.deltavpn.vpn.ConnectionStatus
import com.onthecrow.deltavpn.vpn.VpnController
import com.onthecrow.deltavpn.vpn.domain.SplitTunnelRepository
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode
import com.onthecrow.deltavpn.vpn.model.SplitTunnelSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reapply path, which is where a production crash lived.
 *
 * Applying split-tunnel settings to a running tunnel used to disconnect and then reconnect. The
 * service publishes `Disconnected` one statement before it stops itself, so the reconnect could never
 * reach ActivityManager in time to be seen as a newer start: the service was destroyed every time and
 * the connect that followed had to rebuild it from `onCreate` under a foreground-service deadline that
 * was already running, killing the whole app process with `ForegroundServiceDidNotStartInTimeException`.
 *
 * These tests pin the shape that fixes it — **one connect, no disconnect** — because it is the shape,
 * not the timing, that is load-bearing, and nothing else in the suite would notice it regressing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VpnSyncWorkerTest {

    private val configUrl = "vless://example"
    private val ref = ConfigRef(sourceId = "src", configId = "cfg")
    private val config = RemoteConfig(id = "cfg", name = "Config", url = configUrl)

    @Test
    fun `applying split-tunnel settings to a live tunnel connects without disconnecting first`() =
        runTest {
            val f = fixture()

            f.connectAndSettle()
            // The change under test: routing edited while the tunnel is up.
            f.splitTunnel.value = blockingMode(setOf("com.example.app"))
            f.scope.testScheduler.runCurrent()

            assertEquals(listOf("connect"), f.vpn.calls)
            assertEquals(
                listOf(AutoRestartReason.SPLIT_TUNNEL_CHANGE to AutoRestartResult.RESTARTED),
                f.analytics.restarts,
            )
        }

    /**
     * The reapply announces `Connecting` and then `Connected` again, and both come back through the
     * very flow the worker is collecting. If the new key were recorded after the connect rather than
     * before it, that echo would read as another unapplied change and reapply forever.
     */
    @Test
    fun `the reconnect it triggers does not itself look like another change`() = runTest {
        val f = fixture()

        f.connectAndSettle()
        f.splitTunnel.value = blockingMode(setOf("com.example.app"))
        f.scope.testScheduler.runCurrent()
        // The service comes back up on the new routing.
        f.vpn.status.value = ConnectionStatus.Connected
        f.scope.testScheduler.runCurrent()

        assertEquals(listOf("connect"), f.vpn.calls)
    }

    /** A config that no longer validates must cost nothing — the working tunnel stays up, untouched. */
    @Test
    fun `an invalid config leaves the live tunnel alone`() = runTest {
        val f = fixture(validation = ConfigValidationResult.Invalid("bad config"))

        f.connectAndSettle()
        f.splitTunnel.value = blockingMode(setOf("com.example.app"))
        f.scope.testScheduler.runCurrent()

        assertTrue(f.vpn.calls.isEmpty(), "expected no tunnel operation, got ${f.vpn.calls}")
        assertEquals(
            listOf(AutoRestartReason.SPLIT_TUNNEL_CHANGE to AutoRestartResult.KEPT_ON_INVALID),
            f.analytics.restarts,
        )
    }

    /** Selecting a different config is the other reapply trigger, and takes the same single-connect path. */
    @Test
    fun `selecting a different config reapplies the same way`() = runTest {
        val f = fixture()

        f.connectAndSettle()
        val other = RemoteConfig(id = "cfg2", name = "Other", url = "vless://other")
        f.sources.value = ConfigSourcesState(
            selected = ConfigRef(sourceId = "src", configId = "cfg2"),
            selectedConfig = other,
        )
        f.scope.testScheduler.runCurrent()

        assertEquals(listOf("connect"), f.vpn.calls)
        assertEquals(
            listOf(AutoRestartReason.SELECTION_CHANGE to AutoRestartResult.RESTARTED),
            f.analytics.restarts,
        )
    }

    /**
     * Toggling a package while split tunnelling is OFF resolves to identical routing, so it must not
     * touch the tunnel at all — the key deliberately drops the selection in that mode.
     */
    @Test
    fun `editing packages while split tunnelling is off never touches the tunnel`() = runTest {
        val f = fixture()

        f.connectAndSettle()
        f.splitTunnel.value = SplitTunnelSettings(
            mode = SplitTunnelMode.OFF,
            selectedPackages = setOf("com.example.app"),
        )
        f.scope.testScheduler.runCurrent()

        assertTrue(f.vpn.calls.isEmpty(), "expected no tunnel operation, got ${f.vpn.calls}")
    }

    // ---- fixture ----

    private fun TestScope.fixture(
        validation: ConfigValidationResult = ConfigValidationResult.Valid("{\"xray\":true}"),
    ): Fixture {
        val sources = MutableStateFlow(ConfigSourcesState(selected = ref, selectedConfig = config))
        val splitTunnel = MutableStateFlow(SplitTunnelSettings())
        val vpn = RecordingVpnController()
        val analytics = RecordingAnalytics()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        VpnSyncWorker(
            sources = sources,
            vpnController = vpn,
            prepareConnectionConfig = { validation },
            splitTunnelRepository = object : SplitTunnelRepository {
                override fun observe(): Flow<SplitTunnelSettings> = splitTunnel
                override suspend fun update(transform: (SplitTunnelSettings) -> SplitTunnelSettings) {
                    splitTunnel.value = transform(splitTunnel.value)
                }
            },
            analyticsManager = analytics,
            scopeProvider = object : ApplicationScopeProvider {
                override val scope: CoroutineScope = scope
            },
        )
        scope.testScheduler.runCurrent()
        return Fixture(sources, splitTunnel, vpn, analytics, scope)
    }

    private class Fixture(
        val sources: MutableStateFlow<ConfigSourcesState>,
        val splitTunnel: MutableStateFlow<SplitTunnelSettings>,
        val vpn: RecordingVpnController,
        val analytics: RecordingAnalytics,
        val scope: TestScope,
    ) {
        /** Bring the tunnel up and let the worker record it, so what follows is a change to a LIVE tunnel. */
        fun connectAndSettle() {
            vpn.status.value = ConnectionStatus.Connected
            scope.testScheduler.runCurrent()
            vpn.calls.clear()
        }
    }

    private fun blockingMode(packages: Set<String>) =
        SplitTunnelSettings(mode = SplitTunnelMode.BYPASS_SELECTED, selectedPackages = packages)

    /**
     * Models the one behaviour of the real controller this depends on: `connect` publishes `Connecting`
     * synchronously, back into the flow the worker is collecting.
     */
    private class RecordingVpnController : VpnController {
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
        val calls = mutableListOf<String>()

        override suspend fun connect(xrayJson: String): ConnectResult {
            calls += "connect"
            status.value = ConnectionStatus.Connecting
            return ConnectResult.Started
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            status.value = ConnectionStatus.Disconnecting
        }
    }

    private class RecordingAnalytics : AnalyticsManager by NoOpAnalyticsManager {
        val restarts = mutableListOf<Pair<AutoRestartReason, AutoRestartResult>>()
        override fun tunnelAutoRestart(reason: AutoRestartReason, result: AutoRestartResult) {
            restarts += reason to result
        }
    }
}
