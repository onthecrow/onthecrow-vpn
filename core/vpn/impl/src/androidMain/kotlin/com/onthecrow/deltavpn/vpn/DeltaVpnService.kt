package com.onthecrow.deltavpn.vpn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onthecrow.deltavpn.analytics.AnalyticsManager
import com.onthecrow.deltavpn.analytics.ConfirmVia
import com.onthecrow.deltavpn.analytics.ConnectVia
import com.onthecrow.deltavpn.analytics.EngineDeathReason
import com.onthecrow.deltavpn.analytics.KeepaliveWindow
import com.onthecrow.deltavpn.analytics.RecoveryMode
import com.onthecrow.deltavpn.analytics.RecoveryOutcome
import com.onthecrow.deltavpn.vpn.domain.RecoveryTuningRepository
import com.onthecrow.deltavpn.analytics.RecoveryTrigger
import com.onthecrow.deltavpn.analytics.SessionEndReason
import com.onthecrow.deltavpn.analytics.Transport
import com.onthecrow.deltavpn.analytics.TunRebuildOutcome
import com.onthecrow.deltavpn.analytics.VpnErrorCategory
import com.onthecrow.deltavpn.analytics.VpnPermissionOutcome
import com.onthecrow.deltavpn.errorreporting.ErrorDomain
import com.onthecrow.deltavpn.errorreporting.ErrorReporter
import com.onthecrow.deltavpn.vpn.impl.R
import com.onthecrow.deltavpn.vpn.log.DebugLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.onthecrow.deltavpn.xray.AndroidVpnSocketProtector
import com.onthecrow.deltavpn.xray.AndroidXrayEnvironment
import com.onthecrow.deltavpn.xray.OtcLog
import com.onthecrow.deltavpn.xray.createTunnelEngine
import com.onthecrow.deltavpn.xray.XrayConfigSanitizer
import com.onthecrow.deltavpn.xray.XrayRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class DeltaVpnService : VpnService(), KoinComponent {
    // Fire-and-forget analytics. Main-process only (Firebase is not initialised in `:xray`), which this
    // service always is. Carries outcomes/enums/buckets only — never a server, credential, or config.
    private val analyticsManager: AnalyticsManager by inject()
    // Caught non-fatals → Crashlytics (message scrubbed). Main process only. See ErrorReporter.
    private val errorReporter: ErrorReporter by inject()

    // ---- Per-session analytics state (reset on each fresh connect in [runConnect]) ----
    /** elapsedRealtime when the current session reached Connected; null when no session is up. */
    @Volatile
    private var connectedAt: Long? = null
    /** How the current session was brought up, for `vpn_connected`. */
    @Volatile
    private var pendingConnectVia: ConnectVia = ConnectVia.FRESH
    /** Confirmed-DEAD keepalive probes this session (for `vpn_keepalive_health`). */
    private var keepaliveDeadCount = 0
    /** INCONCLUSIVE (Doze-frozen) keepalive probes this session — reported apart from dead ones. */
    private var keepaliveInconclusiveCount = 0
    /** Gate so `vpn_tunnel_confirmed` fires once per session, not on every confirmed probe. */
    private val tunnelConfirmedThisSession = java.util.concurrent.atomic.AtomicBoolean(false)

    // CoroutineExceptionHandler catches anything that escapes the runCatching guards (so a thrown
    // recovery/keepalive coroutine is logged with its stack instead of vanishing into Logcat in Doze).
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { ctx, error ->
            logd("UNCAUGHT coroutine error in $ctx: ${error.stackTraceToString()}")
            errorReporter.report(ErrorDomain.VPN_TUNNEL, error)
        },
    )
    // The engine runs in `:xray`, not here. `protect` is passed across so that process can exclude its
    // own outbound sockets from the tunnel if it turns out it cannot do that for itself.
    private val xrayEngine = createTunnelEngine(
        protect = ::protectSocket,
        onUnexpectedDeath = ::onEngineDied,
    )
    private val sanitizer = XrayConfigSanitizer()
    private val operationMutex = Mutex()

    /**
     * The underlying network the running engine dialled its upstream over.
     *
     * Set when the engine starts, compared on every validated-network callback. It is what separates
     * "the tunnel works" — which a probe answers by opening a NEW connection — from "the connections
     * the tunnel is already carrying still work", which a probe cannot see at all.
     */
    @Volatile
    private var engineNetwork: Network? = null

    /**
     * A trigger that arrived while a recovery held the tunnel, kept so it can be honoured afterwards.
     * One slot: the strongest kind wins and repeats coalesce, because ten path changes during one
     * ladder still only warrant one follow-up.
     */
    @Volatile
    private var pendingTrigger: TunnelStart? = null

    @Volatile
    private var pendingTriggerReason: String = ""

    /** TEMP (diagnosis): paces [probeTcpForDiagnosis]. */
    private var keepaliveRounds = 0

    // The tun interface, built once per process by [runConnect]. `:xray` gets a *dup* of it, sent over
    // the binder; the descriptor number on the far side is its own and means nothing here, so there is
    // nothing left to track locally.
    private var tunInterface: ParcelFileDescriptor? = null

    // When the current tun was established. [probeTunnel] refuses to answer inside the grace window
    // after it, because netd installs this VPN's per-UID routing rules asynchronously and a probe fired
    // in that window leaves on the PHYSICAL network — answering "healthy" for a dead tunnel.
    @Volatile
    private var lastEstablishAt = 0L

    // TEMP (diagnosis): the emitted xray config is dumped once per process, not on every re-dial.
    @Volatile
    private var runtimeJsonLogged = false

    // Guards the recovery ladder: a second trigger while one is running is dropped rather than queued —
    // the running ladder's own probes already observe the current state.
    private val recovering = java.util.concurrent.atomic.AtomicBoolean(false)

    // The config to refresh with while a tunnel is active (null when disconnected).
    @Volatile
    private var activeXrayJson: String? = null

    // Per-app routing for the current session (from the CONNECT intent), reused when a recovery
    // rebuilds the tun so the same exclusions stay applied. At most one is non-empty.
    @Volatile
    private var activeDisallow: List<String> = emptyList()

    @Volatile
    private var activeAllow: List<String> = emptyList()

    // Network monitor: default callback (API 31+, reads the VPN's real underlying network) or a
    // NOT_VPN fallback (< 31). Refreshes xray when the underlying physical network actually changes.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * TEMP (diagnosis): watches the DEFAULT network, which for us is the VPN's own — see
     * [registerAppsEyeViewCallback]. Purely observational.
     */
    private var appsEyeViewCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastUnderlying: Network? = null

    @Volatile
    private var underlyingSeeded = false

    private val mtu = 1500

    private var screenReceiver: BroadcastReceiver? = null
    private var idleModeReceiver: BroadcastReceiver? = null

    // Signature of the current underlying link's properties, so a route change that keeps the same netId
    // is still detected without reacting to routine DNS/MTU churn.
    @Volatile
    private var lastLinkSignature: String? = null

    /**
     * The network `onCapabilitiesChanged(VALIDATED)` last acted on. ONLY that callback writes it.
     *
     * Deliberately separate from [lastUnderlying], which is "what we currently advertise" and is also
     * written by our own Doze self-repair. Conflating the two let a repair adopt a network moments
     * before its VALIDATED callback arrived, so the callback read as "no change" and the handover
     * produced no recovery at all.
     */
    private var validatedUnderlying: Network? = null

    /**
     * The user (or a revocation) asked us to stop. Read by the recovery ladder at every wait point so a
     * teardown does not queue behind up to half a minute of NonCancellable patience. Volatile: written
     * on the main thread from `onStartCommand`, read from the recovery coroutine.
     */
    @Volatile
    private var disconnecting = false

    /**
     * A CONNECT has been accepted and is waiting on [operationMutex]. The mirror of [disconnecting],
     * and read at the same wait points.
     *
     * It exists because a settings reapply is now a plain CONNECT into the LIVE service
     * (`VpnSyncWorker.restartWith`) instead of a disconnect/reconnect pair. The disconnect it replaced
     * carried [disconnecting] with it, which stood the ladder down; without a mirror, a reapply that
     * lands mid-recovery would queue behind up to 45s of NonCancellable T0 patience with the UI
     * sitting on "Connecting".
     *
     * Best-effort by design: it is a hint that makes the ladder yield sooner, never a correctness
     * gate. [runConnect] is queued on the mutex regardless, so losing a race here costs latency, not
     * the reconnect.
     */
    @Volatile
    private var connectRequested = false

    /**
     * How many engine restarts in a row have failed to produce a working tunnel. Drives the backoff in
     * [restartDelayFor]; reset by [noteTunnelHealthy] on any confirmed probe.
     *
     * A plain field, not SharedPreferences. It lived in prefs because the process that owned it was
     * about to be SIGKILLed; the process that gets killed now is `:xray`, and this one survives.
     */
    private var engineRestartAttempt = 0

    // Live screen state. It no longer gates recovery — only the keepalive cadence.
    @Volatile
    private var screenOn = true

    // The single tunnel-health state machine (recover + keepalive); alive while screen-on + connected.
    private var tunnelJob: Job? = null

    // Persisted connect params so the service can self-reconnect after a crash / system kill.
    private val paramsStore by lazy { ConnectionParamsStore(this, errorReporter) }

    /** Authoritative per-app routing, written by the main process. See [SplitTunnelRoutingStore]. */
    private val routingStore by lazy { SplitTunnelRoutingStore(this, errorReporter) }

    /**
     * The newest `startId` [onStartCommand] has seen. Only for teardown paths that were not started by
     * a command of their own — the system's `onRevoke`, and a retry — which want "whatever is newest
     * right now". Every path that DOES have its own start must carry that id down instead of reading
     * this, or the check inverts: see [stopSelfUnlessRestarted].
     */
    @Volatile
    private var lastStartId: Int = 0

    private val recoveryTuningRepository: RecoveryTuningRepository by inject()

    /**
     * Latest "aggressive keepalive" preference, mirrored here by a collector started in [onCreate].
     *
     * Held as a field rather than read inside the ladder: `runLadder` runs `NonCancellable` under
     * `operationMutex` while a tunnel is down, and a DataStore read there would put storage I/O on the
     * repair path for a value that changes once in a blue moon. `false` until the first emission, which
     * is also the default — an unread preference can only ever mean the patient ladder.
     */
    @Volatile
    private var aggressiveKeepalive = false

    // A retry is waiting out its backoff. Trigger events consult this to cut the wait short.
    @Volatile
    private var retryPending = false
    private var retryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Idempotent, and kept even though the Application already does this for the app process: a
        // service can be created before Application.onCreate has finished on some paths, and the cost
        // of a second call is nothing against a lateinit that throws.
        AndroidXrayEnvironment.initialize(this)
        AndroidVpnEnvironment.initialize(this)
        DebugLog.setSink { tag, message -> OtcLog.log(tag, message) }
        // Mirror the recovery-tuning preference for the whole life of the service. Applies live: a
        // toggle lands here and the next ladder picks it up, with no reconnect (which is exactly why
        // the setting is kept out of SplitTunnelSettings — see [RecoveryTuningRepository]).
        //
        // Guarded end to end: a settings read must never be able to take the tunnel down. If the flow
        // fails, [aggressiveKeepalive] simply stays at its last value — and its initial value is the
        // shipped default, so the worst case is the patient ladder, never a surprise aggressive one.
        //
        // Every change is logged because this is the ONLY point where the chain
        // (switch → DataStore → here → ladder) can be observed on a device: pair this line with the
        // `mode=` field that `recover()` logs at the top of each run.
        runCatching {
            recoveryTuningRepository.observe()
                .onEach {
                    if (it != aggressiveKeepalive) logd("recovery tuning: aggressiveKeepalive=$it")
                    aggressiveKeepalive = it
                }
                .catch { error -> logd("recovery tuning: observe failed (${error.message}) — keeping $aggressiveKeepalive") }
                .launchIn(scope)
        }.onFailure { logd("recovery tuning: unavailable (${it.message}) — patient ladder") }
        logd("onCreate sdk=${Build.VERSION.SDK_INT}")
        logPreviousExitReasons()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FIRST, before anything that touches storage or the app graph, and for EVERY start —
        // including the teardown actions.
        //
        // The deadline belongs to the START, not to what we intend to do with it: the moment anyone
        // calls startForegroundService(), Android gives us a few seconds to reach startForeground() or
        // it kills the process with ForegroundServiceDidNotStartInTimeException. This used to skip
        // DISCONNECT/REVOKE, which was safe only by accident — `sendStop` happens to use startService()
        // — so a single call site switching to startForegroundService() would have turned that skip
        // into a guaranteed crash.
        //
        // Promoting on a teardown start costs nothing: the service is already in the foreground for the
        // session — though NOT always: `VpnSyncWorker` revokes regardless of connection status, so a
        // revoke can cold-start the service and briefly post a notification for a tunnel that never
        // existed. The teardown paths below are what drop it —
        // every one of them ends in [stopUnlessRestarted]. Deliberately NOT paired with an immediate
        // stopForeground here: the tun is still up at this point, and dropping foreground before the
        // teardown has run would invite the process to be frozen mid-disconnect.
        startAsForeground()
        // For the teardown paths that have no start of their own (the system's onRevoke, a retry). Read
        // at the moment they run, which is the right answer for them: "the newest start we have seen".
        lastStartId = startId
        logd("onStartCommand action=${intent?.action} flags=$flags startId=$startId")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val fromIntent = intent.getStringExtra(EXTRA_XRAY_JSON)
                // A start with no payload falls back to the persisted params — on the boot and sticky
                // paths there is no in-memory state, so they ARE the source of truth.
                val restored = if (fromIntent.isNullOrBlank()) paramsStore.load() else null
                val xrayJson = fromIntent ?: restored?.xrayJson
                activeDisallow = intent.getStringArrayListExtra(EXTRA_DISALLOW)
                    ?: restored?.disallow ?: emptyList()
                activeAllow = intent.getStringArrayListExtra(EXTRA_ALLOW)
                    ?: restored?.allow ?: emptyList()
                if (restored != null) logd("connect: restored persisted params")
                // fromIntent present = a user-driven connect; a blank payload that fell back to persisted
                // params = a restore (boot / sticky self-heal delivered as CONNECT).
                pendingConnectVia = if (restored != null) ConnectVia.RESTORE else ConnectVia.FRESH
                // Persist for crash self-heal (cleared on deliberate teardown / fatal failure).
                if (!xrayJson.isNullOrBlank()) {
                    paramsStore.save(ConnectionParams(xrayJson, activeDisallow, activeAllow))
                }
                connectRequested = true
                scope.launch { runConnect(xrayJson, startId) }
            }
            // Set BEFORE launching, and outside [operationMutex], because a recovery may be holding that
            // mutex right now: the ladder is NonCancellable and can legitimately sit in its patience
            // window for half a minute, and the user must not watch a dead Disconnect button for that
            // long. The ladder polls this flag and stands down.
            ACTION_DISCONNECT -> {
                disconnecting = true
                scope.launch { runUserDisconnect(SessionEndReason.USER, startId) }
            }
            // Remote revocation: same teardown as disconnect (Android has no persisted system profile).
            ACTION_REVOKE -> {
                disconnecting = true
                scope.launch { runUserDisconnect(SessionEndReason.EXTERNAL_REVOKE, startId) }
            }
            // intent == null (and so action == null): START_STICKY recreated us. That signal is now
            // unambiguous — it means an UNPLANNED death, because nothing kills this process on purpose
            // any more. The in-memory config is gone, so self-heal from the persisted params; no
            // persisted params means there was nothing to restore, so stand down.
            else -> {
                val saved = paramsStore.load()
                if (saved != null) {
                    logd("sticky restart — restoring tunnel from persisted config")
                    activeDisallow = saved.disallow
                    activeAllow = saved.allow
                    pendingConnectVia = ConnectVia.RESTORE
                    connectRequested = true
                    scope.launch { runConnect(saved.xrayJson, startId) }
                } else {
                    logd("sticky restart — no persisted config; standing down")
                    stopUnlessRestarted(startId)
                }
            }
        }
        return Service.START_STICKY
    }

    /**
     * The system tore the VPN down externally: the user revoked us in Settings/Quick Settings, or
     * another VPN app took over. TERMINAL, never recoverable — our authorisation is gone, so retrying
     * would only fight the user or the other app. Full teardown, params cleared.
     */
    override fun onRevoke() {
        logd("onRevoke (external teardown) — terminal, not recoverable")
        // Set here and not inside the coroutine, exactly as the DISCONNECT/REVOKE intents do: the
        // ladder is NonCancellable and polls this flag at every wait point. Without it a revocation
        // left it repairing a tunnel whose permission had just been taken away — up to 45s of patience
        // plus a backoff that reaches five minutes, killing and respawning the engine the whole time,
        // which is what happens when another VPN app takes over.
        disconnecting = true
        scope.launch { runUserDisconnect(SessionEndReason.EXTERNAL_REVOKE, lastStartId) }
    }

    /**
     * Runs on the app process's MAIN thread, so it must not wait for anything.
     *
     * It used to be `runBlocking { operationMutex.withLock { ... } }`. That mutex is held by the
     * recovery ladder, which can legitimately sit in its patience window for the better part of a
     * minute — in the app process that is not a slow teardown, it is a frozen UI and an ANR. The flag
     * below is what the ladder already polls at every wait point to stand down, so setting it is both
     * faster and more correct than waiting for it.
     *
     * The teardown itself is handed to [scope], which is cancelled only after it has run.
     */
    override fun onDestroy() {
        logd("onDestroy")
        disconnecting = true
        // Detached first and synchronously: everything below is asynchronous, and until this runs the
        // process-wide engine still holds a `protect` callback bound to this — now dying — service.
        xrayEngine.release()
        scope.launch {
            operationMutex.withLock { stopMonitoring(); stopTunnel() }
            unregisterMonitoring()
            scope.cancel()
        }
        super.onDestroy()
    }

    // A FRESH establish: build a new tun and start xray. This is the ONLY way the tunnel ever comes up —
    // a user connect, a crash self-heal, or a recovery restart, each in a brand-new process. There is
    // no in-process path, because xray's tun readers outlive [stopXray] and would fight the new
    // instance for packets.
    private suspend fun runConnect(xrayJson: String?, startId: Int) {
        operationMutex.withLock {
            // We are the connect the ladder was told to yield to; it has, so stop asking it to.
            connectRequested = false
            val configJson = xrayJson ?: activeXrayJson
            logd("runConnect: hasConfig=${!configJson.isNullOrBlank()} underlying=$lastUnderlying")
            if (configJson.isNullOrBlank()) {
                fail("No validated configuration is available", startId, VpnErrorCategory.CONFIG_INVALID)
                return
            }
            activeXrayJson = configJson
            disconnecting = false
            // Re-entering over a LIVE tunnel. This is the settings-reapply path: `VpnSyncWorker` used to
            // disconnect and reconnect, which destroyed the service and is what made a split-tunnel
            // change crash with ForegroundServiceDidNotStartInTimeException (§14). It now sends a plain
            // CONNECT into the running service, so the two things that disconnect did for us have to
            // happen here instead — and ONLY here, because [connectedAt] and [tunInterface] are both
            // null on every other path into this function.
            if (tunInterface != null) {
                logd("connect: reapplying over a live session")
                // The session really does end here — new tun, new engine — so it is measured and closed
                // out before the success block below resets [connectedAt] and the keepalive counters.
                // No-ops when nothing was connected, which is every other path into this function.
                reportSessionEnd(SessionEndReason.RECONNECT)
                // [tunnelJob] is deliberately NOT cancelled, tempting as it is — the old keepalive is
                // about to probe a tun this function closes. Cancelling it loses the guarantee that the
                // new session gets a watcher at all: [startMonitoring] below can be deferred by the
                // `recovering` guard (an old keepalive's recover() blocked on the mutex we are holding
                // counts as recovering), and the ONLY thing that drains that deferral is the tail of
                // this very job. Left alone, both branches end with a watcher; cancelled, one of them
                // ends with a live tunnel nobody is watching. What it costs instead is bounded and
                // self-correcting: everything destructive the old loop can reach needs [operationMutex],
                // which we hold until the new tunnel is up.
            }
            // Re-read the physical network from the system instead of trusting cached callback state
            // (callbacks are dropped across Doze), then advertise it. On this path the cache is empty in
            // a fresh process, so the scan runs and seeds it.
            refreshUnderlyingFromSystem()
            // Re-read the routing at the last possible moment. The lists carried in the CONNECT intent
            // (or restored from persisted params) are a snapshot from whenever that path last ran,
            // and this service re-establishes on paths the main process never sees. The store is
            // authoritative; the snapshot is only a fallback for a first run that predates it.
            routingStore.load()?.let { routing ->
                if (routing.disallow.toList() != activeDisallow || routing.allow.toList() != activeAllow) {
                    logd("split-tunnel: refreshed from store (was disallow=$activeDisallow allow=$activeAllow)")
                }
                activeDisallow = routing.disallow.toList()
                activeAllow = routing.allow.toList()
            }
            logd("connect: establishing tunnel")
            runCatching {
                stopTunnel()
                AndroidVpnSocketProtector.setProtector(::protectSocket)
                tunInterface = establishTun()
                // netd installs this VPN's per-UID routing rules asynchronously AFTER establish()
                // returns; [probeTunnel] must not answer inside that window (see PROBE_ESTABLISH_GRACE_MS).
                lastEstablishAt = SystemClock.elapsedRealtime()
                logd("tun established: fd=${tunInterface?.fd}")
                // Re-advertise AFTER establish, not before: the declaration hangs off the VPN's
                // NetworkAgent, and establish() builds a new one. Doing it in the connect prologue left
                // the fresh agent with no underlying network declared at all.
                applyUnderlyingNetworks(lastUnderlying)
                when (val result = startXrayOnTun(configJson)) {
                    XrayRunResult.Success -> {
                        // The engine started, so we are out of the "xray failed to start" regime that
                        // [scheduleRetry] governs — clear it NOW, before [startMonitoring] fires its
                        // "connected" self-trigger. If the flag were still set there, that trigger would
                        // route through [retryNow] instead of the probe, re-run this whole connect, and
                        // do so forever: the probe that would have cleared the flag never runs. That was
                        // a two-day reconnect storm in the field. From here the keepalive and the
                        // recovery ladder own the tunnel's health; a probe that never confirms is their
                        // problem to escalate, not the retry backoff's.
                        retryPending = false
                        retryJob?.cancel()
                        // A fresh session's analytics counters start here (a recovery re-dial via
                        // [restartEngine] deliberately does NOT reset them — it is the same session).
                        connectedAt = SystemClock.elapsedRealtime()
                        keepaliveDeadCount = 0
                        keepaliveInconclusiveCount = 0
                        tunnelConfirmedThisSession.set(false)
                        // Connected as soon as the engine is up, NOT on the first confirmed probe.
                        // Waiting for the probe left the UI's only button disabled with no way out when
                        // a probe never confirmed (bad config, blocked server); a failing probe is
                        // reported by the recovery ladder anyway.
                        publishStatus(ConnectionStatus.Connected)
                        analyticsManager.vpnConnected(pendingConnectVia)
                        logd("connect: xray up")
                        startMonitoring()
                    }
                    is XrayRunResult.Failure -> scheduleRetry("xray start failed: ${result.message}")
                }
            }.onFailure { error ->
                if (error is CancellationException) {
                    logd("runConnect CANCELLED: ${error.message}")
                    throw error
                }
                if (error is NotPreparedException) {
                    // Retrying cannot help: nobody but the user can grant this, and the old behaviour
                    // was three silent attempts on an escalating backoff that only ended when they
                    // happened to open the consent screen. The persisted params are deliberately kept
                    // — the configuration is fine, only the permission is missing.
                    logd("connect: VPN permission is not granted — waiting for the user, not retrying")
                    retryPending = false
                    retryJob?.cancel()
                    analyticsManager.vpnPermissionResult(VpnPermissionOutcome.MISSING_AT_ESTABLISH)
                    analyticsManager.vpnError(VpnErrorCategory.PERMISSION_DENIED, terminal = true)
                    publishStatus(ConnectionStatus.Error("VPN permission is required"))
                    stopUnlessRestarted(startId)
                    return@onFailure
                }
                logd("runConnect FAILED: ${error.stackTraceToString()}")
                errorReporter.report(ErrorDomain.VPN_TUNNEL, error)
                scheduleRetry(error.message ?: "Failed to start VPN")
            }
        }
    }

    /**
     * Give xray a fresh **dup** of the held-open tun fd and start it. We dup so xray can be
     * stopped/restarted (on every re-dial) without ever closing the master [tunInterface] — the
     * virtual interface, and the OS routing of app traffic into it, stay up across the handover.
     */

    /**
     * Build the tun. One place, because [rebuildTunForApps] must produce an interface identical to the
     * one `runConnect` makes — a drift between them would show up as a routing difference that only
     * appears after a handover.
     */
    private fun establishTun(): ParcelFileDescriptor = Builder()
        .setSession("Delta VPN")
        .setMtu(mtu)
        .addAddress(TUN_ADDRESS, 32)
        .addRoute("0.0.0.0", 0)
        // Claim IPv6 WITHOUT giving the interface a v6 address. Android only routes the address
        // families a VPN declares, so without this every v6-capable app talks straight past the
        // tunnel — and on a dual-stack network Happy Eyeballs actively prefers v6.
        //
        // No address is deliberate: with the route but no source address the kernel has nothing to
        // bind, so a v6 connect() fails instantly and falls through to IPv4, which the tunnel does
        // carry. Giving the tun a v6 address would make apps PREFER a path we cannot forward.
        .addRoute("::", 0)
        .addDnsServer("1.1.1.1")
        // Without this the VPN network is metered on EVERY underlying network, including unmetered
        // Wi-Fi: from targetSdk Q the platform defaults a VpnService network to metered, and it was
        // visible in the field as `notMetered=false` over a Wi-Fi that reported `notMetered=true`
        // one line earlier. Apps throttle background work on a metered network and Data Saver blocks
        // it outright, so the tunnel was quietly imposing that on everything behind it.
        //
        // We do not meter anything ourselves; what the traffic costs is a property of the underlying
        // network, which the platform already folds in.
        .setMetered(false)
        .apply { applySplitTunnel(this) }
        .establish()
        // Documented: null means the app is not prepared, or its consent was revoked — never a
        // transient network problem. [NotPreparedException] keeps that distinct so the retry ladder
        // does not spend a five-minute backoff on something only the user can fix.
        ?: throw NotPreparedException()

    /**
     * Replace the tun after a recovery that took long enough for apps to give up, so they are told the
     * network is usable again.
     *
     * The problem this addresses, measured: a handover leaves the tunnel dead for 16-35 seconds. An app
     * that tries during that window gets a socket the tun stack accepted LOCALLY in a millisecond, waits
     * ~5s for the upstream dial that never lands, and backs off. When the tunnel does come back, nothing
     * tells it — the VPN network is the same object it always was, so there is no `onAvailable`, only a
     * capabilities change. Telegram sat like that for twelve minutes with the tunnel demonstrably
     * healthy, and recovered the instant a Wi-Fi switch changed the capabilities again. A fresh connect
     * over the same cellular network never has the problem, because then the VPN network is new.
     *
     * NOT unconditional. If the tunnel never actually went down — T0 answered on its first probe — no
     * app can have backed off, and a rebuild would be pure cost: the platform's own docs call this a
     * "seamless handover", and it opens a window in which our traffic egresses physically while netd
     * reinstalls the per-UID rules (the reason [PROBE_ESTABLISH_GRACE_MS] exists).
     *
     * Whether apps actually observe a new network is exactly what "seamless" leaves unspecified, and it
     * is the thing to check in the next log: the `apps-eye` callback will show either an onLost/
     * onAvailable pair — in which case this works — or another bare capabilities change, in which case
     * it does not and should be reverted.
     */
    private suspend fun rebuildTunForApps(reason: String): Boolean {
        val configJson = activeXrayJson ?: return false
        val previous = tunInterface ?: return false
        val rebuilt = runCatching { establishTun() }.getOrElse {
            // The old interface is untouched when establish() fails, so the tunnel keeps working.
            logd("tun rebuild($reason) failed, keeping the current interface: ${it.message}")
            analyticsManager.vpnTunRebuild(TunRebuildOutcome.ESTABLISH_FAILED)
            errorReporter.report(ErrorDomain.VPN_TUNNEL, it)
            return false
        }
        tunInterface = rebuilt
        lastEstablishAt = SystemClock.elapsedRealtime()
        logd("tun rebuilt($reason): fd=${rebuilt.fd} (was ${previous.fd})")
        return try {
            // The engine is still reading a dup of the OLD interface, which the platform has just
            // deactivated, so it has to be replaced rather than merely re-pointed.
            xrayEngine.killEngineProcess()
            val result = startXrayOnTun(configJson)
            logd("tun rebuild($reason): engine back on the new interface — $result")
            // The caller decides what to do about a failure; what must NOT happen is what happened
            // before, which was to log it and carry on reporting Connected over a tun nothing reads.
            val ok = result is XrayRunResult.Success
            analyticsManager.vpnTunRebuild(
                if (ok) TunRebuildOutcome.REBUILT_OK else TunRebuildOutcome.ENGINE_NOT_BACK,
            )
            ok
        } finally {
            // In the finally, because a throw between here and there used to leak this descriptor for
            // good — in a process that no longer dies, one per rebuild against a limit of 1024. The
            // contract is to close the old one once the new interface is the live one, which it is
            // from the moment establish() returned.
            runCatching { previous.close() }
                .onFailure { logd("tun rebuild: closing the old interface threw: ${it.message}") }
        }
    }

    private suspend fun startXrayOnTun(configJson: String): XrayRunResult {
        val master = tunInterface ?: return XrayRunResult.Failure("Tun interface is not established")
        // TEMP (diagnosis): verbose xray log into a pullable file. logLevel=debug surfaces the upstream
        // dial / hysteria QUIC reconnect chatter we need. Revert logLevel/errorLogPath when fixed.
        val xrayLogPath = prepareXrayLogFile()
        // `warning` in release left us blind exactly where it hurt: a day of field logs contains nothing
        // from xray but its version banner, so when a redial produced a tunnel that carried nothing
        // there was no way to see whether it had failed to dial, failed to resolve, or never been handed
        // a packet. `info` is the level that carries the tun fd it attached to and the dial errors,
        // without `debug`'s per-connection access chatter — the expensive part, measured at 110-183
        // lines/minute, each a synchronous write to FUSE-backed external storage.
        val logLevel = if (OtcLog.isDebugBuild) "debug" else "info"
        val runtimeJson = sanitizer.withTunInbound(
            configJson,
            mtu = mtu,
            logLevel = logLevel,
            errorLogPath = xrayLogPath,
            // The dominant term in every recovery we have measured. Left at xray's defaults, a dead
            // QUIC session is not noticed for ~30s of awake time and nothing re-dials before then, so
            // both a handover and a Doze exit cost 19-25s of no traffic no matter what we do. Ten
            // seconds bounds that, and the 3s keepalive is what makes ten seconds safe: without it QUIC
            // sends nothing on an idle connection and a healthy tunnel would time itself out.
            //
            // The cost is radio wakeups every 3s while the CPU is awake. It buys nothing during deep
            // sleep either way — the timer does not advance while suspended, which is precisely why a
            // path that dies during Doze is only noticed after waking.
            quicLiveness = XrayConfigSanitizer.QuicLiveness(
                maxIdleTimeoutSeconds = QUIC_MAX_IDLE_TIMEOUT_S,
                keepAlivePeriodSeconds = QUIC_KEEPALIVE_PERIOD_S,
            ),
        )
        // Whether a config switch reached the engine is confirmed by the runtimeJson dump below (debug
        // only) and by xray's own banner — NOT by fingerprinting the credential. The log ships in
        // release and users are asked to share it, so even a 6-char prefix of a per-client secret does
        // not belong in it.
        logd("xray start: xray loglevel=$logLevel")
        // TEMP (diagnosis): dump the config we actually hand to xray, once per process, so the emitted
        // transport settings can be checked against what we think we generated.
        //
        // Credentials are redacted. This log ships in release builds and lives in /Android/data, which
        // the user and any MANAGE_EXTERNAL_STORAGE holder can read — writing a VLESS uuid or hysteria2
        // password there in clear would hand over the account. Redaction costs nothing diagnostically:
        // the question this answers is about transport settings, and the fingerprint line above still
        // identifies WHICH config is live.
        if (OtcLog.isDebugBuild && !runtimeJsonLogged) {
            runtimeJsonLogged = true
            val redacted = runtimeJson.replace(CREDENTIAL_REGEX) { m -> "${m.groupValues[1]}***\"" }
            logd("xray runtimeJson (${runtimeJson.length}B): $redacted")
        }
        // Duplicated at the last possible moment, and as a wrapper rather than a bare int. Everything
        // above can throw, and this process no longer dies after a failed start — so a descriptor
        // taken earlier would simply leak, one per attempt, against a default limit of 1024 in the
        // process that also holds the tun. `startOnTun` owns it from here and closes our copy either
        // way. (The old code detached to an int and never closed it, which was survivable only because
        // the process holding it was about to be killed — the very thing this split removes.)
        val tun = runCatching { master.dup() }.getOrElse {
            errorReporter.report(ErrorDomain.VPN_TUNNEL, it)
            return XrayRunResult.Failure("Could not duplicate the tun descriptor: ${it.message}")
        }
        val started = SystemClock.elapsedRealtime()
        val result = xrayEngine.startOnTun(tun, runtimeJson)
        // Recorded even on failure: a half-started engine is still bound to this path, and pretending
        // otherwise would make the next genuine move look like a first sighting.
        engineNetwork = lastUnderlying
        logd("xray start result: $result (${SystemClock.elapsedRealtime() - started}ms) on $engineNetwork")
        return result
    }

    /**
     * TEMP (diagnosis): xray writes its own log via libXray (Go), which on some devices' FUSE-emulated
     * external storage produces a file that `adb pull` / file managers can't read ("Permission denied").
     * So we create the file from the APP first — Go then opens the EXISTING file in append mode and
     * never re-creates or re-chmods it, so it inherits the same app-owned, pullable attributes as
     * vpn-debug.log. [reset] only on a fresh user connect (not on re-dials/recoveries), so one whole
     * session — including every reconnect attempt — accumulates in one pullable file.
     */
    /**
     * Point xray's own error log at the SAME file everything else writes to, so a user sharing their
     * log shares one file with the whole story in it.
     *
     * Deliberately does NOT truncate: [OtcLog] owns the size cap and truncates IN PLACE, because
     * deleting the file out from under xray's open append handle would silently swallow its output
     * for the life of the process.
     */
    private fun prepareXrayLogFile(): String? = runCatching {
        OtcLog.logFile(this).apply {
            if (!exists()) {
                parentFile?.mkdirs()
                createNewFile()
            }
            // Debug only. World-readable lets `adb pull` and file managers reach the log while
            // instrumenting; in release the file stays app-private and the only way out is the share
            // sheet in settings, which grants access per-URI.
            if (OtcLog.isDebugBuild) runCatching { setReadable(true, false) }
        }.absolutePath
    }.getOrElse {
        logd("log file prepare FAILED: ${it.message}")
        null
    }

    /**
     * Apply per-app split-tunnel routing. disallow/allow are mutually exclusive. Invariants: never
     * disallow our own package, and in allow-list mode always tunnel our own package — otherwise the
     * health probe (which runs in this process) would test a direct connection instead of the tunnel.
     */
    private fun applySplitTunnel(builder: Builder) {
        when {
            activeDisallow.isNotEmpty() -> activeDisallow.filterNot { it == packageName }.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { logd("split-tunnel: cannot disallow $pkg: ${it.message}") }
            }
            activeAllow.isNotEmpty() -> {
                var applied = 0
                (activeAllow + packageName).distinct().forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg); applied++ }
                        .onFailure { logd("split-tunnel: cannot allow $pkg: ${it.message}") }
                }
                // The two branches fail in OPPOSITE directions, which is why only this one is checked.
                // A package we cannot DISALLOW merely stays in the tunnel — harmless. A package we
                // cannot ALLOW leaves the tunnel. If every selected app is gone (uninstalled since it
                // was picked) only our own package survives, and the result is a tunnel that carries
                // nothing but us while reporting Connected. Refuse to establish instead.
                if (applied <= 1) {
                    error("None of the apps selected for the VPN are installed any more")
                }
            }
        }
        if (activeDisallow.isNotEmpty() || activeAllow.isNotEmpty()) {
            logd("split-tunnel: disallow=$activeDisallow allow=$activeAllow")
        }
    }

    /**
     * Stop xray.
     *
     * NB this does NOT fully stop it. `proxy/tun.Handler` has no Close method, the always-on inbound
     * handler never closes `h.proxy`, and `AndroidTun.Close()` returns nil — so the gVisor reader
     * goroutines keep reading the tun fd for the life of the PROCESS. Every caller of this must
     * therefore end in process death; starting xray again in the same process gives you two stacks
     * competing for one tun queue and roughly half the packets vanish. That is why recovery restarts
     * the process instead of re-dialling in place.
     *
     * The dup'd fd is left open deliberately: closing it frees the fd number for the next `dup()`,
     * and the surviving reader would then be reading an unrelated socket. It is reclaimed when the
     * process dies, which is imminent by construction.
     */
    private suspend fun stopXray(): Boolean {
        val result = xrayEngine.stop()
        engineNetwork = null
        logd("xray stop: $result")
        return result is XrayRunResult.Success
    }

    /**
     * Deliberate, terminal teardown: the user disconnected, or the VPN was revoked out from under us.
     *
     * The ONLY place that clears the persisted params — a recovery must never come through here, or it
     * destroys its own fallback config. It also must never emit `Disconnected` on a recovery path:
     * besides the above, that status drives `VpnSyncWorker` to reset its active key, so a genuine remote
     * config change arriving mid-recovery would be silently swallowed.
     */
    private suspend fun runUserDisconnect(reason: SessionEndReason, startId: Int) {
        operationMutex.withLock {
            logd("runUserDisconnect")
            // Cancel any pending retry FIRST: the whole point of an uncapped retry policy is that only
            // the user stops it, so this is the one place that must actually stop it.
            retryPending = false
            retryJob?.cancel()
            retryJob = null
            paramsStore.clear()
            publishStatus(ConnectionStatus.Disconnecting)
            stopMonitoring()
            reportSessionEnd(reason)
            stopTunnel()
            publishStatus(ConnectionStatus.Disconnected)
            stopUnlessRestarted(startId)
            // [stopTunnel] above has already taken the engine process with it, which is what clears
            // xray's global connection pool — with keepalive on, a pool entry that is never re-dialled
            // would otherwise outlive the disconnect. Nothing is killed here any more.
            finishTeardown()
        }
    }

    /**
     * Deliberately empty of any kill.
     *
     * This used to SIGKILL the whole process on disconnect, to clear xray-core's process-global
     * hysteria pool. Two things changed: that pool now lives in `:xray`, which [stopTunnel] kills
     * directly; and this is the app process, so killing it would take MainActivity, Compose and the
     * Koin graph down in front of a user who just tapped Disconnect.
     *
     * The log flush stays. It is the one thing that was worth doing here, and it is off the main
     * thread because it waits on the writer.
     */
    private fun finishTeardown() {
        scope.launch(Dispatchers.IO) { OtcLog.flushBlocking() }
    }

    /** Full teardown: stop xray AND close the tun interface (used on disconnect / fatal error). */
    private suspend fun stopTunnel() {
        stopXray()
        // And take the engine PROCESS with it. Stopping xray does not clear the state that makes a
        // reconnect reuse the previous client's authenticated QUIC session — that state is
        // process-global in Go, and the process it belongs to is now `:xray`, which would otherwise
        // outlive this teardown and be bound again by the next connect.
        runCatching { xrayEngine.killEngineProcess() }
            .onFailure { logd("stopTunnel: killing the engine threw: ${it.message}") }
        AndroidVpnSocketProtector.setProtector(null)
        tunInterface?.let { runCatching { it.close() }.onFailure { logd("stopTunnel: close failed: ${it.message}") } }
        if (tunInterface != null) logd("stopTunnel: tun interface closed")
        tunInterface = null
    }

    // ---- Resilience: one state machine guards tunnel health ----
    //
    // Every trigger (validated network change, link-property change, Doze exit, screen on, keepalive
    // failure) feeds ONE job, which either runs the recovery ladder (see [recover]) or settles into the
    // keepalive loop that re-enters it on silent death. The job runs regardless of screen state — a
    // handover while the screen is off is exactly the case that has to work — and is cancelled only on
    // disconnect.

    /**
     * DECLARATION ORDER IS LOAD-BEARING: a deferred trigger is coalesced with `maxOf`, so these must
     * stay sorted from weakest to strongest response.
     */
    private enum class TunnelStart {
        /** Just connected & known healthy — go straight to keepalive (don't re-probe immediately). */
        KEEPALIVE_ONLY,

        /** Screen on / wake: probe first; recover only if the tunnel is actually dead. */
        PROBE_FIRST,

        /** Physical-network change: the old upstream socket is dead — recover straight away. */
        FORCE_RECOVER,

        /**
         * TEMP (experiment): the underlying network MOVED — recover, then rebuild the tun so apps are
         * handed a NEW network object.
         *
         * What it tests: Telegram sits in "waiting for network" after a Wi-Fi -> cellular switch while
         * the tunnel is measurably fine — recovery completes in 67-125ms, probes pass, and other apps
         * blocked in the same region keep working through the very same tunnel. It recovers instantly
         * on a switch back to Wi-Fi, and a fresh connect over that same cellular network is fine too.
         * The one thing a fresh connect has that a switch does not is a new VPN network, so this asks
         * whether that signal is what the app is waiting for.
         *
         * NOT a default. Rebuilding costs an icon blink and a ~250ms window in which our own traffic
         * egresses physically while netd reinstalls the per-UID rules. Keep only if it works.
         */
        PATH_CHANGED,
    }

    private fun startTunnelJob(reason: String, start: TunnelStart, externalTrigger: Boolean = true) {
        // A retry is sitting out its backoff and an EXTERNAL event just changed the situation — a
        // network appeared, the screen came on — so try again now rather than wait out a timer that
        // knows nothing about the network. But NOT for the self-triggers a successful connect fires on
        // its own way out ("connected", "network confirmed"): those are not new events, and routing
        // them through retryNow re-ran the connect that had just succeeded. A.1 already clears the flag
        // before those fire; this is the second lock on the same door.
        if (retryPending && externalTrigger) {
            retryNow(reason)
            return
        }
        if (activeXrayJson == null) {
            logd("startTunnelJob($reason/$start) ignored — no active config")
            return
        }
        // A recovery in flight already owns the tunnel, so leave it alone. Cancelling the job does NOT
        // stop the ladder — it runs NonCancellable — so the replacement job would find recover() refused
        // by the `recovering` guard and drop straight into a SECOND keepalive loop, probing against the
        // ladder's own probes. That is exactly what the field log shows at 09:26. The ladder's probes
        // read current reality anyway, and its job falls through to the keepalive when it finishes.
        if (recovering.get()) {
            // Deferred, not dropped. The running ladder cannot know about a path that moved after it
            // started, and a lost PATH_CHANGED means the apps behind us are never told the network is
            // back. In the field two of these arrived 130ms apart and it only worked out by accident.
            //
            // One slot, strongest wins: PATH_CHANGED > FORCE_RECOVER > PROBE_FIRST. Coalescing is the
            // point — ten changes during one ladder deserve one follow-up, not ten.
            val deferred = maxOf(start, pendingTrigger ?: start)
            if (start != TunnelStart.KEEPALIVE_ONLY) {
                pendingTrigger = deferred
                pendingTriggerReason = reason
                logd("startTunnelJob($reason/$start) deferred — a recovery already owns the tunnel")
            } else {
                logd("startTunnelJob($reason/$start) ignored — a recovery already owns the tunnel")
            }
            return
        }
        if (tunnelJob?.isActive == true) logd("startTunnelJob($reason): cancelling in-flight tunnel job")
        tunnelJob?.cancel()
        logd("startTunnelJob: reason=$reason start=$start")
        tunnelJob = scope.launch {
            // try/finally, because the keepalive is the ONLY thing watching the tunnel once the ladder
            // is done. Anything thrown below used to skip it and land in the scope's exception handler,
            // which merely logs — leaving a live tun, no engine and no watcher until an unrelated
            // trigger happened to fire. The watcher outranks whatever went wrong on the way to it.
            try {
                when (start) {
                // recover() kills the process if it proceeds; if it's debounced it returns and we fall
                // through to keepAliveLoop so the (still-alive) process keeps watching.
                    TunnelStart.FORCE_RECOVER -> recover(reason)
                    TunnelStart.PATH_CHANGED -> recover(reason, rebuildTun = true)
                    TunnelStart.PROBE_FIRST -> when (probeTunnel(PROBE_TIMEOUT_MS)) {
                        true -> {
                            logd("$reason: tunnel already healthy")
                            noteTunnelHealthy()
                        }
                        false -> {
                            logd("$reason: no answer on first ask — handing to the ladder")
                            recover(reason)
                        }
                        null -> logd("$reason: no verdict — leaving it to the keepalive")
                    }
                    TunnelStart.KEEPALIVE_ONLY -> logd("$reason: starting keepalive watch")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logd("$reason: recovery threw (${error.javaClass.simpleName}: ${error.message}) — watching anyway")
                errorReporter.report(ErrorDomain.VPN_TUNNEL, error)
            }
            drainPendingTrigger()
            keepAliveLoop()
        }
    }

    /**
     * The recovery ladder.
     *
     * It never asks the main process for anything: for a backgrounded VPN the main process is normally
     * dead (measured: a 35-minute gap with the tunnel up), a runtime-registered receiver cannot start a
     * process, and even when it was merely cached, broadcasts to it were delivered 39-68s late. Routing
     * recovery through it was three stacked ways to fail.
     *
     *   T0  probe, patiently                 up to [T0_PATIENCE_MS]   is it dead, or just not up yet?
     *   T2  replace the engine process       backed off, uncapped     the repair
     *
     * T0 owns most of the budget on purpose. Every probe failure ever recorded in the field was a
     * timeout rather than an error, so "no answer" cannot distinguish a broken path from one that has
     * not finished coming up — and collapsing that distinction into 1.9 seconds, as the first version
     * did, produced a repair loop that fired on healthy tunnels and killed the process ~37 times a day
     * without fixing anything.
     *
     * There used to be a T1 between them: stop and restart the engine inside its own process. It is
     * gone. Across five field logs T0 never once failed, so T1 never ran; and it could not have helped
     * anyway, because the one piece of state a restart has to clear does not live in the engine
     * instance (see below).
     *
     * WHY THE ENGINE NEEDS A WHOLE FRESH PROCESS. Two earlier explanations were written here and both
     * are false for libXray v26.7.11 — recorded because they were load-bearing and wrong:
     *
     *   - "tun.Handler has no Close, so the gVisor readers outlive stopXray". It does have one
     *     (proxy/tun/handler.go:216), and the chain reaches endpoint.Attach(nil), which signals the
     *     dispatcher's eventfd and joins the goroutines. That was true of 26.3.27, not of this version.
     *   - "hysteria2's client pool is behind a sync.Once, so a second start reuses the session". The
     *     Once guards only the map's creation, and the key is a dialerConf holding a POINTER to the
     *     MemoryStreamConfig — a new core.Instance builds a new one, so a client is never reused.
     *
     * The real reason is narrower and worse: nothing is ever deleted from that map
     * (transport/internet/hysteria/dialer.go — no `delete(` anywhere), and the janitor only closes
     * clients whose connection has gone Inactive. With keepAlivePeriod=3s an orphaned connection keeps
     * sending PINGs and never goes Inactive, so a stopped engine leaves an immortal session still
     * talking to the server. Only process death reaps it.
     *
     * Runs NonCancellable under [operationMutex] with a wakelock held: a recovery interrupted halfway
     * (by a screen event, a competing trigger, or CPU suspend) leaves the tunnel worse off than one
     * that never started. The one exception is [disconnecting], polled at every wait point, because the
     * user must not wait out half a minute of patience for a teardown.
     */
    private suspend fun recover(
        reason: String,
        rebuildTun: Boolean = false,
    ) {
        if (activeXrayJson == null) return
        if (!recovering.compareAndSet(false, true)) {
            logd("recover($reason): a recovery is already running — ignored")
            return
        }
        val wakeLock = acquireRecoveryWakeLock()
        val started = SystemClock.elapsedRealtime()
        try {
            withContext(NonCancellable) {
                operationMutex.withLock {
                    val tuning = currentRecoveryTuning()
                    val outcome = runLadder(reason, rebuildTun, tuning)
                    // One event per ladder run. A null outcome is a stand-down and is NOT reported —
                    // [standDownReason] has two of them now, a user disconnect AND a connect waiting on
                    // the mutex, and neither is a recovery result worth a number.
                    // `attempts` reads the restart counter post-run: a confirmed probe resets it to 0, so
                    // a recovered run buckets to "1" and an unrecovered one carries the climbing count.
                    if (outcome != null) {
                        analyticsManager.vpnRecovery(
                            trigger = recoveryTriggerOf(reason),
                            outcome = outcome,
                            attempts = engineRestartAttempt,
                            durationMs = SystemClock.elapsedRealtime() - started,
                            transport = currentTransport(),
                            mode = if (tuning.aggressive) RecoveryMode.AGGRESSIVE else RecoveryMode.PATIENT,
                        )
                    }
                }
            }
        } finally {
            recovering.set(false)
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    private suspend fun runLadder(
        reason: String,
        rebuildTun: Boolean = false,
        // Resolved once by the caller and passed in, so the profile the ladder RAN under is the one the
        // analytics event reports — re-reading the store afterwards could straddle a toggle mid-run.
        tuning: RecoveryTuning,
    ): RecoveryOutcome? {
        val patienceMs = tuning.t0PatienceMs
        val startedWall = SystemClock.elapsedRealtime()
        // uptimeMillis stops during CPU suspend; elapsedRealtime does not. The difference between them
        // IS the time this device spent asleep, measured rather than guessed.
        val startedAwake = SystemClock.uptimeMillis()
        fun elapsed() = SystemClock.elapsedRealtime() - startedWall
        fun awakeElapsed() = SystemClock.uptimeMillis() - startedAwake
        fun sleptMs() = elapsed() - awakeElapsed()
        logd(
            // protectFd counts used to appear here. The counter now lives in `:xray` along with the
            // dialer callback, so reading it from this process would print a constant zero — worse than
            // nothing, since the whole point of the number was to tell "xray never dialled" apart from
            // "xray dialled and got nowhere". The engine's own `protectFd #N` lines are in the same log
            // file with timestamps, which answers the same question honestly.
            "recover($reason): START screenOn=$screenOn mode=${if (tuning.aggressive) "aggressive" else "patient"} " +
                "t0Patience=${patienceMs}ms batteryOptIgnored=${isIgnoringBatteryOptimizations()}",
        )

        // T0 — ask the tunnel itself, patiently.
        //
        // The old T0 gave the tunnel 1.9s (two 600ms probes) before condemning it. That is shorter than
        // the thing it is usually measuring. A cellular bearer that has just attached is not carrying a
        // proxy handshake yet: in the field the gap between "cell appeared" and the first probe that
        // could succeed was 23 SECONDS, during which the ladder ran twice, killed the process twice, and
        // repaired nothing — the tunnel that finally worked was identical to the two that were killed,
        // it was simply started late enough. Every one of the 179 probe failures in a day was a timeout,
        // never a socket error, so "no answer" carries no information about whether the path is broken
        // or merely not up yet. The only way to tell them apart is to wait.
        //
        // So T0 now keeps asking across [T0_PATIENCE_MS], with the timeout growing per attempt: a fresh
        // handshake over a cold LTE bearer does not fit in 600ms, while a warm tunnel answers in ~110ms
        // and still exits on the first attempt. Patience costs nothing when the tunnel is alive.
        // Nothing to recover ONTO. Every rung below repairs the tunnel's own state, and none of them can
        // conjure a network: with the phone's Wi-Fi asleep and no cellular, an overnight run spent an
        // hour per ladder, restarted the VPN process three times and made 670 futile dials against a
        // system that reported no usable network at all. Standing down is not giving up — a network
        // appearing fires onCapabilitiesChanged(VALIDATED), which starts a fresh recovery, and that is
        // exactly what did eventually fix it at 06:27 that night.
        if (refreshUnderlyingFromSystem() == null) {
            logd("recover($reason): no underlying network — standing down until one appears")
            return RecoveryOutcome.STOOD_DOWN_NO_NETWORK
        }

        var attempt = 0
        // Budgeted on AWAKE time, because the thing we are usually waiting for — hysteria2's QUIC idle
        // timeout — is a Go timer on CLOCK_MONOTONIC and does not advance during suspend either. Wall
        // time would spend the whole budget on a single Doze nap without ever asking the tunnel.
        while (awakeElapsed() < patienceMs) {
            standDownReason()?.let { why ->
                logd("recover($reason): standing down — $why")
                return null
            }
            if (sleptMs() > LADDER_ABANDON_SLEEP_MS) {
                // The device slept through this ladder, so everything it has learned is stale and the
                // rungs below would act on it: the same overnight run escalated to a process restart on
                // evidence gathered 75 minutes earlier. Abandoning is cheap — the caller falls straight
                // through to the keepalive loop, and waking fires its own triggers.
                logd("recover($reason): abandoning — device slept ${sleptMs()}ms mid-ladder")
                return RecoveryOutcome.ABANDONED_SLEPT
            }
            val steps = tuning.t0ProbeTimeoutsMs
            val timeout = steps[attempt.coerceAtMost(steps.lastIndex)]
            if (probeTunnel(timeout) == true) {
                logd("recover($reason): T0 OK — alive after ${elapsed()}ms (attempt ${attempt + 1})")
                onRecoverySucceeded()
                // Rebuilt on EVERY confirmed move, with no health test in front of it.
                //
                // Gating this on "the tunnel was actually down" was tried and is wrong twice over.
                // Wrong in principle: apps need the signal because THEIR network changed transport,
                // which has nothing to do with whether our tunnel stayed up. And wrong in practice,
                // measured — skipping the rebuild leaves [engineNetwork] pointing at the network the
                // engine still runs on, so the next switch BACK to it compares equal and is not seen
                // as a move at all. In the field: rebuilt on cellular 190, moved to Wi-Fi 192 with a
                // first-probe pass so no rebuild, then back to 190 — reported as "confirmed" rather
                // than "MOVED", no signal, and Telegram stayed dead.
                //
                // The cost is real and accepted: ~600-850ms with the engine restarting, and a window
                // of roughly 90ms in which the physical interface is the default. Both are paid during
                // a switch that has already broken every connection the tunnel was carrying.
                if (rebuildTun && !rebuildTunForApps(reason)) {
                    logd("recover($reason): tun rebuilt but the engine did not come back — escalating")
                    restartEngineForRecovery(reason)
                }
                return RecoveryOutcome.RECOVERED_PROBE
            }
            attempt++
            delay(T0_PROBE_RETRY_DELAY_MS)
        }
        standDownReason()?.let { why ->
            logd("recover($reason): standing down — $why")
            return null
        }
        if (sleptMs() > LADDER_ABANDON_SLEEP_MS) {
            logd("recover($reason): abandoning — device slept ${sleptMs()}ms mid-ladder")
            return RecoveryOutcome.ABANDONED_SLEPT
        }
        logd("recover($reason): T0 dead — $attempt probes over ${awakeElapsed()}ms awake of ${patienceMs}ms")

        // T2 — throw the ENGINE process away and start a fresh one on the tun we still hold.
        //
        // This used to kill THIS process and have an AlarmManager resurrect it, which worked but tore
        // the VPN down on the way: the kernel closes the tun with the process, so Android dropped the
        // interface and the system VPN icon blinked every time. Now only `:xray` dies. The tun is never
        // closed, nothing is re-established, and there is nothing to schedule — which is also why the
        // alarm, `SCHEDULE_EXACT_ALARM` and the battery-optimisation exemption are all gone.
        standDownReason()?.let { why ->
            logd("recover($reason): standing down — $why")
            return null
        }
        // Re-checked here and not only above, because T0's patience contains waits and the device can
        // fall asleep inside them — that is precisely how a restart once fired on 75-minute-old evidence.
        if (sleptMs() > LADDER_ABANDON_SLEEP_MS) {
            logd("recover($reason): abandoning before T2 — device slept ${sleptMs()}ms mid-ladder")
            return RecoveryOutcome.ABANDONED_SLEPT
        }
        if (refreshUnderlyingFromSystem() == null) {
            logd("recover($reason): no underlying network — not restarting into an outage")
            return RecoveryOutcome.STOOD_DOWN_NO_NETWORK
        }
        logd("recover($reason): T0 dead (${elapsed()}ms) → T2 engine restart")
        return restartEngineForRecovery(reason)
    }

    /**
     * How impatient this ladder run is allowed to be. Only T0 — the "ask the tunnel whether it is still
     * alive" rung — is affected; every rung below it, and every safety rail (sleep-abandon, the
     * no-usable-network stand-down, INCONCLUSIVE probes in Doze), is shared by both modes because each
     * of them fixes a measured field failure, not a preference.
     */
    private class RecoveryTuning(
        val t0PatienceMs: Long,
        val t0ProbeTimeoutsMs: IntArray,
        val aggressive: Boolean,
    )

    /**
     * Turn the current preference into a profile.
     *
     * [PATIENT_TUNING] uses this file's own constants verbatim, so with the toggle off the ladder is the
     * one that shipped — same patience, same growing probe timeouts, same everything.
     */
    private fun currentRecoveryTuning(): RecoveryTuning =
        if (aggressiveKeepalive) AGGRESSIVE_TUNING else PATIENT_TUNING

    /**
     * T2: replace the engine process, then start it again on the existing tun.
     *
     * The backoff is a plain field now, not SharedPreferences: it used to have to survive this
     * process being killed, and this process is no longer the one being killed. It is reset by
     * [noteTunnelHealthy] on any confirmed probe.
     *
     * Uncapped by design. A network outage is not a reason to give up on the user's VPN — only an
     * explicit disconnect is. The backoff just stops it spinning, and because this process now stays
     * alive throughout, a network coming back short-circuits the wait instead of running it out.
     */
    /**
     * Replace the engine process and start it again on the tun we already hold.
     *
     * @return null if it could not be brought back, so callers can stop rather than probe something
     *   that is not there.
     */
    private suspend fun restartEngine(reason: String): XrayRunResult? {
        val configJson = activeXrayJson ?: run {
            logd("engine restart($reason): no active config — standing down")
            return null
        }
        val cleanKill = xrayEngine.killEngineProcess()
        val result = startXrayOnTun(configJson)
        logd("engine restart($reason): back (cleanKill=$cleanKill) result=$result")
        return if (result is XrayRunResult.Success) result else null
    }

    /**
     * Why the ladder should stop what it is doing, or null to carry on. Polled at every wait point,
     * because the ladder is NonCancellable and can legitimately sit in T0's patience for the better
     * part of a minute — long enough that a user who just tapped Disconnect, or a settings change that
     * needs the tunnel rebuilt, would otherwise watch a dead UI for all of it.
     */
    private fun standDownReason(): String? = when {
        disconnecting -> "user is disconnecting"
        connectRequested -> "a connect is waiting to run"
        else -> null
    }

    private suspend fun restartEngineForRecovery(reason: String): RecoveryOutcome {
        val attempt = ++engineRestartAttempt
        val backoff = restartDelayFor(attempt)
        logd("T2 restart($reason): attempt=$attempt after ${backoff}ms")
        delay(backoff)
        standDownReason()?.let { why ->
            logd("T2 restart: standing down — $why")
            return RecoveryOutcome.UNRECOVERED_BACKOFF
        }
        restartEngine(reason) ?: return RecoveryOutcome.UNRECOVERED_BACKOFF
        for (wait in ENGINE_PROBE_DELAYS_MS) {
            delay(wait)
            if (probeTunnel(PROBE_TIMEOUT_STEPS_MS.last()) == true) {
                logd("T2 restart: confirmed alive")
                onRecoverySucceeded()
                return RecoveryOutcome.RECOVERED_ENGINE_RESTART
            }
        }
        logd("T2 restart: still no answer — the next trigger will try again")
        return RecoveryOutcome.UNRECOVERED_BACKOFF
    }

    /**
     * A confirmed round-trip through the tunnel. This — and nothing else — is what proves we are
     * connected, so it both clears the restart backoff and is the only thing that reports Connected.
     *
     * Called from EVERY successful probe, not just from inside the ladder. Resetting only on a ladder
     * success left the counter climbing across restarts that had in fact recovered: a fresh process
     * that reconnects and then probes clean never went through [recover], so its attempt count survived
     * and an unrelated failure an hour later inherited a five-minute backoff it had not earned.
     *
     * The prefs write is skipped when the counter is already zero, since this runs on every keepalive.
     */
    private fun noteTunnelHealthy() {
        retryPending = false
        engineRestartAttempt = 0
        // First confirmed round-trip of the session = honest time-to-usable. Gated so it fires once per
        // session (reset in [runConnect]); noteTunnelHealthy itself runs on every keepalive.
        if (connectedAt != null && tunnelConfirmedThisSession.compareAndSet(false, true)) {
            analyticsManager.vpnTunnelConfirmed(
                firstProbeMs = SystemClock.elapsedRealtime() - lastEstablishAt,
                via = if (recovering.get()) ConfirmVia.RECOVERY else ConfirmVia.FRESH,
            )
        }
        publishStatus(ConnectionStatus.Connected)
    }

    private fun onRecoverySucceeded() {
        // The ladder confirmed a re-establish (T0 re-probe or T2 restart). Distinct from a fresh connect
        // so activation rate is not inflated by reconnects; fired only here, never per keepalive.
        analyticsManager.vpnConnected(ConnectVia.RECOVERY)
        noteTunnelHealthy()
    }

    /**
     * How long to wait before the restarting alarm fires, as a function of how many restarts in a row
     * have failed to produce a working tunnel.
     *
     * This used to be a flat 500ms at every attempt: [RETRY_BACKOFF_MS] existed but was reachable only
     * from [scheduleRetry], i.e. only when xray itself refused to start — a path that fired zero times
     * in a day of field logs, while the restart path fired 37 times. So the advertised 1s→5min backoff
     * was never once applied to the thing that actually restarts. The visible result was a self-feeding
     * kill loop: nine restarts in four minutes, at a fixed 8.2s period, on a network that never changed.
     *
     * Never gives up (only an explicit disconnect does that), it just stops spinning: a phone in a
     * tunnel or on a plane settles at one attempt per five minutes.
     */
    private fun restartDelayFor(attempt: Int): Long =
        RETRY_BACKOFF_MS[(attempt - 1).coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]

    private fun acquireRecoveryWakeLock(): PowerManager.WakeLock? = runCatching {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(RECOVERY_WAKELOCK_TIMEOUT_MS)
        }
    }.getOrElse {
        logd("recovery wakelock unavailable: ${it.message}")
        null
    }

    // Load-bearing for three separate grants (exact-alarm eligibility, allow-while-idle quota exemption,
    // and the background foreground-service start). A silent revocation changes the whole failure mode,
    // so every recovery records it.
    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    }.getOrDefault(false)

    /**
     * Why did the PREVIOUS instance of one of our processes die?
     *
     * A silent process death is invisible in our own log — the last line simply stops — and it is the
     * worst failure we have: the kernel closes the tun along with the process, so traffic leaves in the
     * clear while the UI still shows Connected. A dying process cannot report its own death, so the
     * next one reads it from the system here.
     *
     * The reason is the whole diagnosis. CRASH_NATIVE points at libXray/Go; LOW_MEMORY or SIGNALED at
     * the low-memory killer or an OEM policy kill; EXIT_SELF at our own [scheduleProcessDeath].
     */
    private fun logPreviousExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(packageName, /* pid = */ 0, EXIT_REASONS_TO_LOG)
                // Our own process and the engine's, nothing else. The filter used to key on ":vpn"; after
            // the move that name exists nowhere, so it silently matched nothing — and the death of THIS
            // process is now the worst failure there is (the kernel closes the tun with it, so traffic
            // leaves in the clear while the UI still says Connected). It is the one most worth seeing.
            .filter { it.processName == packageName || it.processName.endsWith(XRAY_PROCESS_SUFFIX) }
                .forEach { info ->
                    logd(
                        "previous exit: ${info.processName} pid=${info.pid} reason=${exitReasonLabel(info.reason)} " +
                            "status=${info.status} importance=${info.importance} rssKb=${info.rss} " +
                            "at=${info.timestamp} desc=${info.description}",
                    )
                }
        }.onFailure { logd("previous exit: unavailable (${it.message})") }
    }

    private fun exitReasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH_JVM"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN($reason)"
    }

    // ---- Analytics helpers (all fire-and-forget; main process) ----

    /**
     * Emit `vpn_session_end` + `vpn_keepalive_health` for a session that reached Connected, then clear
     * the session state. No-ops if the tunnel never came up ([connectedAt] null), so a connect that
     * failed before Connected is reported by `connect_result`/`vpn_error`, not as a session.
     */
    private fun reportSessionEnd(reason: SessionEndReason) {
        val startedAt = connectedAt ?: return
        analyticsManager.vpnSessionEnd(reason, SystemClock.elapsedRealtime() - startedAt)
        analyticsManager.vpnKeepaliveHealth(
            window = if (keepaliveDeadCount >= KEEPALIVE_DEGRADED_DEAD_THRESHOLD) {
                KeepaliveWindow.DEGRADED
            } else {
                KeepaliveWindow.HEALTHY
            },
            deadCount = keepaliveDeadCount,
            inconclusiveCount = keepaliveInconclusiveCount,
        )
        connectedAt = null
    }

    /** Map a ladder trigger reason string to the bounded analytics enum (never the raw string). */
    private fun recoveryTriggerOf(reason: String): RecoveryTrigger = when {
        reason.contains("path changed") -> RecoveryTrigger.NETWORK_MOVED
        reason.contains("network confirmed") -> RecoveryTrigger.NETWORK_CONFIRMED
        reason.contains("link") -> RecoveryTrigger.LINK_CHANGE
        reason.contains("screen") -> RecoveryTrigger.SCREEN_ON
        reason.contains("idle") -> RecoveryTrigger.DOZE_EXIT
        reason.contains("engine died") -> RecoveryTrigger.ENGINE_DIED
        reason.contains("keepalive") -> RecoveryTrigger.KEEPALIVE_FAIL
        else -> RecoveryTrigger.OTHER
    }

    /** Coarse underlying-transport TYPE — the only network detail analytics may carry. */
    private fun currentTransport(): Transport? {
        val caps = lastUnderlying?.let { caps(it) } ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            else -> Transport.UNKNOWN
        }
    }

    /** Best-effort cause of the last `:xray` process exit, mapped to the bounded enum. */
    private fun latestXrayExitReason(): EngineDeathReason {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return EngineDeathReason.OTHER
        return runCatching {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(packageName, /* pid = */ 0, EXIT_REASONS_TO_LOG)
                .firstOrNull { it.processName.endsWith(XRAY_PROCESS_SUFFIX) }
                ?.let { mapExitReason(it.reason) }
                ?: EngineDeathReason.OTHER
        }.getOrElse { EngineDeathReason.OTHER }
    }

    private fun mapExitReason(reason: Int): EngineDeathReason = when (reason) {
        ApplicationExitInfo.REASON_ANR -> EngineDeathReason.ANR
        ApplicationExitInfo.REASON_CRASH -> EngineDeathReason.CRASH_JVM
        ApplicationExitInfo.REASON_CRASH_NATIVE -> EngineDeathReason.CRASH_NATIVE
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> EngineDeathReason.DEPENDENCY_DIED
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> EngineDeathReason.EXCESSIVE_RESOURCE
        ApplicationExitInfo.REASON_EXIT_SELF -> EngineDeathReason.EXIT_SELF
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> EngineDeathReason.INIT_FAILURE
        ApplicationExitInfo.REASON_LOW_MEMORY -> EngineDeathReason.LOW_MEMORY
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> EngineDeathReason.PERMISSION_CHANGE
        ApplicationExitInfo.REASON_SIGNALED -> EngineDeathReason.SIGNALED
        ApplicationExitInfo.REASON_USER_REQUESTED -> EngineDeathReason.USER_REQUESTED
        ApplicationExitInfo.REASON_USER_STOPPED -> EngineDeathReason.USER_STOPPED
        else -> EngineDeathReason.OTHER
    }

    /**
     * Publish a status to the main process AND remember it, so a main process that starts later can
     * ask what the truth is instead of assuming the tunnel is down.
     *
     * Repeats are dropped. Connected is now asserted by every successful keepalive probe (that is what
     * makes it honest), which without this would be a broadcast every 8 seconds forever.
     */
    /**
     * Publish a status. A plain flow write now — the service and the UI share a process, so there is
     * nothing to cross.
     *
     * This used to be a broadcast, with a second receiver on the other side so a freshly started main
     * process could ask what the truth was. Both existed only because the two lived apart; the
     * round trip was also measured taking 39-68 seconds to reach a cached process, which is why the
     * UI could show a stale state. Repeats are still dropped: Connected is asserted by every
     * successful keepalive, which would otherwise be an emission every 8 seconds forever.
     */
    private fun publishStatus(status: ConnectionStatus) {
        // Deduped against the FLOW, never against a field of our own. `PlatformVpnController` writes
        // `Connecting` and `Disconnecting` straight into the flow without coming through here, so a
        // private mirror drifts out of date the moment it does — and the reapply path made that fatal:
        // the mirror still said `Connected` from the previous session while the flow said `Connecting`,
        // so the `Connected` that ended the reconnect was dropped as a repeat and the UI sat on
        // "Connecting" forever. `MutableStateFlow` conflates equal values anyway; this is the same
        // guarantee, read from the one place that is actually authoritative.
        if (status == AndroidVpnRuntime.status.value) return
        AndroidVpnRuntime.status.value = status
    }


    /**
     * Probe the tunnel on a cadence; the probe doubles as a real upstream keepalive. Two consecutive
     * failures (one blip is tolerated) re-enter recovery. Exits when the job is cancelled (disconnect)
     * — delay() is the cancellation point.
     *
     * The loop keeps running with the screen off, just slower. It is best-effort by construction —
     * `delay()` rides CLOCK_MONOTONIC and does not advance while the CPU is suspended — and that is the
     * point: it bounds the dead window at ~[KEEPALIVE_INTERVAL_SCREEN_OFF_MS] of AWAKE time instead of
     * "until the user unlocks".
     */
    /**
     * Honour whatever arrived while the ladder held the tunnel.
     *
     * Run here rather than by posting a fresh job, because this coroutine already owns the sequence:
     * a new job would be cancelled by the next trigger anyway, and the `recovering` flag has only just
     * been released. Loops, because a change can land while we are servicing the previous one; each
     * pass clears the slot first, so it terminates as soon as nothing new arrives.
     */
    private suspend fun drainPendingTrigger() {
        while (true) {
            val start = pendingTrigger ?: return
            val reason = pendingTriggerReason
            pendingTrigger = null
            if (disconnecting || activeXrayJson == null) return
            logd("deferred trigger: $reason/$start")
            when (start) {
                TunnelStart.PATH_CHANGED -> recover(reason, rebuildTun = true)
                TunnelStart.FORCE_RECOVER -> recover(reason)
                TunnelStart.PROBE_FIRST ->
                    if (probeTunnel(PROBE_TIMEOUT_MS) == false) recover(reason) else noteTunnelHealthy()
                TunnelStart.KEEPALIVE_ONLY -> Unit
            }
        }
    }

    private suspend fun keepAliveLoop() {
        var fails = 0
        while (true) {
            delay(if (screenOn) KEEPALIVE_INTERVAL_MS else KEEPALIVE_INTERVAL_SCREEN_OFF_MS)
            if (activeXrayJson == null) return
            // TEMP (diagnosis): every Nth round, ask the same question over TCP as well.
            if (++keepaliveRounds % TCP_PROBE_EVERY_N_ROUNDS == 0) {
                withContext(Dispatchers.IO) { probeTcpForDiagnosis() }
            }
            val alive = probeTunnel(PROBE_TIMEOUT_MS)
            if (alive == null) {
                // Inconclusive = the process was frozen mid-probe by Doze (measured nothing). Counted
                // apart from real failures so a Doze freeze never scores as a tunnel failure.
                keepaliveInconclusiveCount++
                continue
            }
            if (alive) {
                fails = 0
                noteTunnelHealthy()
            } else {
                keepaliveDeadCount++
                fails++
                logd("keepalive: probe failed ($fails/$KEEPALIVE_FAILS_BEFORE_RECOVER)")
                if (fails >= KEEPALIVE_FAILS_BEFORE_RECOVER) {
                    fails = 0
                    // Same patience as any other trigger. A keepalive failure means the tunnel has gone
                    // quiet, and what un-sticks it is the same QUIC idle timeout regardless of whether
                    // the link visibly moved — so shortening the wait here would only buy an earlier
                    // restart, never an earlier recovery.
                    recover("keepalive")
                }
            }
        }
    }

    /**
     * Real END-TO-END health check: a DNS A-query to [PROBE_DNS_SERVER]:53 that must round-trip through
     * the tunnel. UDP has no local handshake, so — unlike a TCP connect, which xray's tun completes
     * locally — a valid reply can only arrive if traffic genuinely reached the upstream and came back.
     *
     * Three things make the verdict trustworthy, and all three are load-bearing (the previous version
     * had none of them and reported dead tunnels as healthy):
     *  - `connect()` forces the kernel to pick the source address NOW, from the route the datagram will
     *    actually take. Asserting `localAddress == TUN_ADDRESS` is the only way to know the probe went
     *    through the tun rather than escaping onto the physical network.
     *  - the reply's DNS transaction id and QR bit are verified, so a stray datagram can't count as a pass.
     *  - a probe issued inside [PROBE_ESTABLISH_GRACE_MS] of establish() is delayed, not answered: netd
     *    installs the per-UID rules asynchronously, and probes fired in that window egress physically.
     *
     * The socket is intentionally NOT protected — its traffic must traverse the tun (we don't exclude
     * ourselves), which is exactly what we're testing. Literal IP, no DNS resolution of the target.
     */
    private suspend fun probeTunnel(timeoutMs: Int): Boolean? {
        val sinceEstablish = SystemClock.elapsedRealtime() - lastEstablishAt
        if (sinceEstablish < PROBE_ESTABLISH_GRACE_MS) {
            delay(PROBE_ESTABLISH_GRACE_MS - sinceEstablish)
        }
        // null is "we learned nothing", and it is deliberately NOT collapsed into either verdict. The
        // process can be frozen mid-probe by Doze; elapsedRealtime counts through the suspend, so the
        // probe reports a multi-second or multi-minute timeout that measured nothing at all. Scoring
        // that as dead is how an idle phone talked itself into a restart overnight, and scoring it as
        // alive would reset the restart backoff and report Connected on a tunnel we never reached.
        // Retried once first, because the freeze is over by the time we look.
        repeat(PROBE_FROZEN_RETRIES) {
            when (withContext(Dispatchers.IO) { probeTunnelBlocking(timeoutMs) }) {
                ProbeResult.ALIVE -> return true
                ProbeResult.DEAD -> return false
                ProbeResult.INCONCLUSIVE -> Unit
            }
        }
        logd("probe: inconclusive after $PROBE_FROZEN_RETRIES tries — no verdict")
        return null
    }

    private enum class ProbeResult { ALIVE, DEAD, INCONCLUSIVE }

    private fun probeTunnelBlocking(timeoutMs: Int): ProbeResult {
        val started = SystemClock.elapsedRealtime()
        return runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getByName(PROBE_DNS_SERVER), 53))
                val local = socket.localAddress?.hostAddress
                if (local != TUN_ADDRESS) {
                    logd("probe: NOT on tun — localAddress=$local expected=$TUN_ADDRESS; verdict=dead")
                    return@use ProbeResult.DEAD
                }
                socket.soTimeout = timeoutMs
                val query = buildDnsQuery(PROBE_DNS_NAME, PROBE_DNS_TXID)
                socket.send(DatagramPacket(query, query.size))
                val response = DatagramPacket(ByteArray(512), 512)
                socket.receive(response) // throws SocketTimeoutException if nothing comes back
                val data = response.data
                val txid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val isReply = (data[2].toInt() and 0x80) != 0
                val ok = response.length >= DNS_HEADER_SIZE && txid == PROBE_DNS_TXID && isReply
                val ms = SystemClock.elapsedRealtime() - started
                if (ok) {
                    logd("probe OK localAddress=$local ${ms}ms")
                } else {
                    logd("probe: bogus reply len=${response.length} txid=0x${txid.toString(16)} qr=$isReply ${ms}ms")
                }
                if (ok) ProbeResult.ALIVE else ProbeResult.DEAD
            }
        }.getOrElse { error ->
            val ms = SystemClock.elapsedRealtime() - started
            // A probe that ran far past its own socket timeout did not measure the tunnel — the thread
            // was suspended mid-probe by Doze or the cached-process freezer, and elapsedRealtime keeps
            // counting through a suspend. Observed at 23708ms against a 600ms deadline, and once at
            // 300069ms. Counting those as evidence of a dead tunnel means a freeze is indistinguishable
            // from a failure, which is how an idle phone talked itself into a restart overnight.
            if (ms > timeoutMs * FROZEN_PROBE_FACTOR) {
                logd("probe: INCONCLUSIVE — ${ms}ms against a ${timeoutMs}ms deadline (process was frozen)")
                return@getOrElse ProbeResult.INCONCLUSIVE
            }
            logd("probe: no answer in ${ms}ms (${error.javaClass.simpleName})")
            ProbeResult.DEAD
        }
    }


    /**
     * TEMP (diagnosis): the same DNS question, over TCP.
     *
     * The health probe is UDP, and has been from the start — so everything we have ever concluded
     * about "the tunnel is healthy" is really "UDP round-trips through the tunnel". That is a blind
     * spot with the exact shape of the symptom: Telegram speaks TCP, while YouTube and Instagram —
     * the two that keep working — speak mostly QUIC, which is UDP.
     *
     * A bare TCP connect would prove nothing, and that is the trap this avoids: xray's tun stack
     * completes the handshake LOCALLY and accepts the connection before it has dialled anything
     * upstream, which is why an app can sit on a socket that looks connected and is not. So this asks
     * a real question and waits for a real answer — DNS over TCP, RFC 1035 length prefix and all.
     *
     * Log-only. It does not vote on the tunnel's health; if it ever disagrees with the UDP probe,
     * that disagreement is the finding.
     */
    private fun probeTcpForDiagnosis() {
        val started = SystemClock.elapsedRealtime()
        runCatching {
            java.net.Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(InetAddress.getByName(PROBE_DNS_SERVER), 53),
                    TCP_PROBE_TIMEOUT_MS,
                )
                val local = socket.localAddress?.hostAddress
                if (local != TUN_ADDRESS) {
                    logd("tcp-probe: NOT on tun — localAddress=$local; inconclusive")
                    return
                }
                socket.soTimeout = TCP_PROBE_TIMEOUT_MS
                val query = buildDnsQuery(PROBE_DNS_NAME, PROBE_DNS_TXID)
                // DNS over TCP prefixes the message with its 16-bit length.
                val framed = ByteArray(query.size + 2)
                framed[0] = ((query.size shr 8) and 0xFF).toByte()
                framed[1] = (query.size and 0xFF).toByte()
                query.copyInto(framed, 2)
                socket.getOutputStream().apply { write(framed); flush() }
                val head = ByteArray(2)
                val input = socket.getInputStream()
                if (input.read(head) != 2) {
                    logd("tcp-probe: no length prefix after ${SystemClock.elapsedRealtime() - started}ms")
                    return
                }
                val expected = ((head[0].toInt() and 0xFF) shl 8) or (head[1].toInt() and 0xFF)
                val body = ByteArray(expected.coerceAtMost(512))
                val read = input.read(body)
                val txid = if (read >= 2) {
                    ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                } else {
                    -1
                }
                val ms = SystemClock.elapsedRealtime() - started
                if (txid == PROBE_DNS_TXID) {
                    logd("tcp-probe OK ${ms}ms (udp probe is the health verdict; this is diagnosis only)")
                } else {
                    logd("tcp-probe: bogus reply read=$read txid=0x${txid.toString(16)} ${ms}ms")
                }
            }
        }.onFailure { error ->
            logd(
                "tcp-probe FAILED after ${SystemClock.elapsedRealtime() - started}ms " +
                    "(${error.javaClass.simpleName}: ${error.message}) — TCP through the tunnel is not working",
            )
        }
    }

    private fun startMonitoring() {
        // Usually screen-on (user-initiated connect), but a crash self-heal can reconnect while in Doze.
        screenOn = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        registerUnderlyingNetworkCallback()
        registerAppsEyeViewCallback() // TEMP (diagnosis)
        registerScreenReceiver()
        registerIdleModeReceiver()
        // Probe rather than assume: this is what turns Connecting into Connected, so it has to run now
        // and not after one keepalive interval. On a warm path it answers in ~110ms; on a cold one the
        // ladder's patience carries it, which is the same thing the user would otherwise sit through.
        startTunnelJob("connected", TunnelStart.PROBE_FIRST, externalTrigger = false)
    }

    /**
     * Stop watching the tunnel. The network callback is deliberately NOT unregistered here — it is
     * registered once per process and released only in [onDestroy]. Cycling it per connect leaked
     * registrations (the unregister failure was swallowed) against AOSP's hard cap of 100 concurrent
     * NetworkRequests per UID, which a long-lived process doing in-process recovery can reach.
     */
    private fun stopMonitoring() {
        tunnelJob?.cancel()
        tunnelJob = null
        retryJob?.cancel()
        retryJob = null
        activeXrayJson = null
    }

    private fun unregisterMonitoring() {
        networkCallback?.let { cb -> runCatching { connectivityManager().unregisterNetworkCallback(cb) } }
        networkCallback = null
        appsEyeViewCallback?.let { cb -> runCatching { connectivityManager().unregisterNetworkCallback(cb) } }
        appsEyeViewCallback = null
        underlyingSeeded = false
        lastUnderlying = null
        validatedUnderlying = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        idleModeReceiver?.let { runCatching { unregisterReceiver(it) } }
        idleModeReceiver = null
    }

    // Screen state no longer gates recovery — a handover with the screen off is exactly the case that has
    // to work, and the user must find a live tunnel the instant they unlock. Screen state only sets the
    // keepalive cadence (see [keepAliveLoop]); SCREEN_ON additionally forces an immediate check.
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        logd("screen off — keepalive slowing to ${KEEPALIVE_INTERVAL_SCREEN_OFF_MS}ms")
                        screenOn = false
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        logd("screen on — checking tunnel")
                        screenOn = true
                        startTunnelJob("screen on", TunnelStart.PROBE_FIRST)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { screenReceiver = receiver }
            .onFailure { logd("could not register the screen receiver (${it.message}) — that trigger is lost") }
    }

    /**
     * Doze exit — the REAL wake signal, and it fires without the screen ever turning on. SCREEN_ON is
     * only a proxy for it and is kept as a fast path. While dozing, network callbacks are provably
     * missed (the field logs show the underlying network changing with no callback at all), so the
     * first thing this does is re-read the truth from the system rather than trust cached state.
     */
    private fun registerIdleModeReceiver() {
        if (idleModeReceiver != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val idle = runCatching {
                    (getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode
                }.getOrDefault(false)
                logd("device idle mode changed: idle=$idle")
                if (!idle) {
                    refreshUnderlyingFromSystem()
                    startTunnelJob("idle exit", TunnelStart.PROBE_FIRST)
                }
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        runCatching { registerReceiver(receiver, filter) }
            .onSuccess { idleModeReceiver = receiver }
            .onFailure {
                logd("could not register the idle-mode receiver (${it.message}) — that trigger is lost")
                errorReporter.report(ErrorDomain.VPN_TUNNEL, it)
            }
    }

    /**
     * Pick the best physical network to advertise as our underlying one.
     *
     * `activeNetwork` is useless here because we ARE the VPN and it reports our own tun, so we scan
     * every network for a non-VPN one with INTERNET. The scan must RANK rather than take the first
     * match: `allNetworks` has no documented ordering and empirically comes back netId-ascending, i.e.
     * oldest first. Taking `firstOrNull { validated }` therefore prefers the network being handed OFF
     * over the one being handed to, which is exactly backwards during a Wi-Fi/cellular switch — the
     * field logs show it re-selecting the outgoing cellular network over fresh Wi-Fi every time.
     *
     * Newest-first is the tiebreak that encodes "the network that just appeared is the one the system
     * is moving to". Wi-Fi/Ethernet beat cellular ahead of it because a phone that has both is on
     * Wi-Fi as far as the user is concerned, whatever order the netIds happen to be in.
     */
    private fun scanBestUnderlying(excluding: Network? = null): Network? {
        val cm = connectivityManager()
        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        return all.filter { it != excluding }
            .mapNotNull { net -> caps(net)?.takeIf { isUsableUnderlying(it) }?.let { net to it } }
            .maxWithOrNull(
                compareBy(
                    { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) },
                    { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) },
                    { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    { (n, _) -> netIdOf(n) },
                ),
            )?.first
    }

    private fun caps(network: Network): NetworkCapabilities? =
        runCatching { connectivityManager().getNetworkCapabilities(network) }.getOrNull()

    private fun isUsableUnderlying(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    /** `Network` has no public id accessor; its toString is the netId, which rises with age. */
    private fun netIdOf(network: Network): Int =
        network.toString().filter(Char::isDigit).toIntOrNull() ?: 0

    /**
     * Repair [lastUnderlying] if it has silently gone away — and ONLY then.
     *
     * Callbacks are demonstrably dropped across Doze (the field logs show the underlying network having
     * moved with no `onAvailable` ever delivered), so a recovery cannot simply trust cached state. But
     * the previous formulation re-DECIDED instead of repairing: it re-scanned unconditionally and
     * adopted the result whenever it differed, which meant it overruled a correct callback four
     * milliseconds old just as readily as a stale value forty minutes old.
     *
     * That was self-perpetuating, not merely wrong. Writing [lastUnderlying] back to the old network
     * re-arms the `network == lastUnderlying` guard in `onCapabilitiesChanged`, so the next capabilities
     * update for the new network reads as a fresh change and runs another full recovery, which reverts
     * it again — four rounds in seven seconds in the field, ending latched onto the network the user had
     * just left. Since `setUnderlyingNetworks` is what the platform derives the VPN's transports from,
     * that latch is what put a mobile-data indicator in the status bar over a live Wi-Fi connection.
     *
     * So: a live callback wins. We look only for the cached network having vanished.
     */
    private fun refreshUnderlyingFromSystem(): Network? {
        val cached = lastUnderlying
        if (cached != null && caps(cached)?.let { isUsableUnderlying(it) } == true) return cached
        val best = scanBestUnderlying()
        if (best != cached) {
            logd("underlying refresh: cached $cached is gone -> $best")
            adoptUnderlying(best)
        }
        return best
    }

    /**
     * Take [network] as the underlying one and advertise it.
     *
     * [lastLinkSignature] is cleared with it: the signature describes the interface of whichever network
     * was current, so carrying it across a switch makes the next genuine link change on the NEW network
     * compare against the OLD one's interface and fire a spurious recovery.
     */
    private fun adoptUnderlying(network: Network?) {
        lastUnderlying = network
        // Sticky: "have we seeded in this process yet", never "do we currently have a network". Letting
        // a loss clear it would make the NEXT network arrive as a seed instead of a change — and seeding
        // deliberately does not trigger recovery, so a genuine Wi-Fi→cellular switch would go unnoticed
        // until the keepalive happened to catch it. Cleared only by [unregisterMonitoring].
        if (network != null) underlyingSeeded = true
        lastLinkSignature = null
        applyUnderlyingNetworks(network)
    }

    private fun capsLabel(network: Network): String {
        val caps = runCatching { connectivityManager().getNetworkCapabilities(network) }.getOrNull()
            ?: return "caps=?"
        return "${transportLabel(caps)} validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun transportLabel(caps: NetworkCapabilities): String {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
        }
        return "transport=${transports.joinToString("|").ifEmpty { "?" }}"
    }

    private fun connectivityManager(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Protect xray's outbound socket from the VPN (route it over the physical underlying network rather
     * than back into our own tun). Called by xray via the DialerController for EVERY outbound socket —
     * so it runs on xray's (Go/JNI) threads and MUST NOT throw: an exception here would cross the JNI
     * boundary back into Go and can crash the engine process.
     *
     * We deliberately do NOT call `Network.bindSocket()` anymore: it threw EPERM constantly after a
     * Wi-Fi↔cell handover / Doze exit (black-holing the upstream → failed recovery). A protected socket
     * follows the system default network on its own, so a socket created AFTER a handover is already on
     * the new link — which is why recovery is about replacing sockets, not about re-binding them.
     */
    /**
     * The engine process died on its own — crashed, or was reaped under memory pressure.
     *
     * The tunnel is dead from this instant: the tun is still up and apps are still routed into it, but
     * nothing is reading the other end, so their traffic is being dropped. Nothing else would notice
     * promptly — the keepalive would take up to a minute of awake time — so this is the trigger.
     *
     * Delivered on a binder thread, so it only posts work. Doing anything here would either block a
     * binder thread or start a recovery concurrently with one already running.
     */
    private fun onEngineDied() {
        if (activeXrayJson == null) return
        logd("engine process died unexpectedly — recovering")
        scope.launch {
            // Best-effort cause from the OS exit record for :xray (mapped category only — never the
            // free-form description). Read off the binder thread, on the coroutine.
            analyticsManager.vpnEngineDeath(latestXrayExitReason())
            startTunnelJob("engine died", TunnelStart.FORCE_RECOVER)
        }
    }

    private fun protectSocket(fd: Int): Boolean {
        val protectedOk = runCatching { protect(fd) }.getOrElse {
            logd("protect fd=$fd threw: ${it.message}")
            errorReporter.report(ErrorDomain.SOCKET_PROTECT, it)
            false
        }
        return protectedOk
    }

    /**
     * Declare which physical network this VPN currently runs over.
     *
     * NB this ROUTES NOTHING. It writes NetworkAgent metadata only — transports, metered/roaming/
     * suspended flags, bandwidth, NetworkStats attribution, and what apps behind the VPN see from
     * `getActiveNetworkInfo()`. It sets no fwmark and touches no routing table: a `protect()`ed socket
     * follows the SYSTEM DEFAULT network, always. We keep it accurate so accounting and the apps behind
     * us are told the truth, not because it repairs anything.
     */
    private fun applyUnderlyingNetworks(network: Network?) {
        runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
        // TEMP (diagnosis): the underlying network's own bits, since the VPN's are derived from them
        // and a difference there is what an app behind us would ultimately be reacting to.
        val detail = network?.let { caps(it)?.let(::capsDetail) }
        logd("setUnderlyingNetworks(${network ?: "default"})${detail?.let { " [$it]" } ?: ""}")
    }

    /**
     * Observe the **physical** underlying network (Wi-Fi/cellular), not the VPN. Contrary to the old
     * assumption, `registerDefaultNetworkCallback` reports the VPN's *own* network here
     * (transport=wifi|vpn, ifc=tun0) — so it never saw real Wi-Fi↔cell changes. A `NOT_VPN + INTERNET`
     * request tracks the actual underlying link (independent of our own routing), so
     * `onAvailable`/`onCapabilitiesChanged(validated)` reflect the physical network and we refresh xray
     * when it changes. Event-driven, no timers.
     */

    /**
     * TEMP (diagnosis): log the network as an app behind the tunnel sees it.
     *
     * `registerDefaultNetworkCallback` reports OUR OWN network here — transport `wifi|vpn` on `tun0`,
     * measured and noted at [registerUnderlyingNetworkCallback]. That is precisely the view an app
     * gets from `getActiveNetwork()`, and it is the one thing about this failure nobody has ever
     * observed: whether a handover leaves the VPN network without transports, unvalidated, or gone
     * entirely for a moment.
     *
     * It exists because the alternative was to keep guessing. Two diagnoses have already been wrong —
     * one blamed a protect that works, one blamed stale connections that turned out to reconnect in
     * 10ms — and both were argued from what the VPN could see of ITSELF, never from what the apps
     * could see of it.
     *
     * Observational only: it changes nothing, triggers nothing, and comes out once the question is
     * settled. Capabilities are logged in full rather than summarised, because which bit matters is
     * exactly what is not yet known.
     */
    private fun registerAppsEyeViewCallback() {
        if (appsEyeViewCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logd("apps-eye: onAvailable $network")
            }

            override fun onLost(network: Network) {
                // The one that would explain everything: while there is no default network, an app
                // asking `getActiveNetwork()` gets null and a well-behaved one stops trying.
                logd("apps-eye: onLost $network — apps now see NO default network")
            }

            override fun onUnavailable() {
                logd("apps-eye: onUnavailable")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                logd("apps-eye: caps $network ${capsDetail(caps)}")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                logd("apps-eye: link $network ifc=${lp.interfaceName} addrs=${lp.linkAddresses}")
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                logd("apps-eye: blocked=$blocked on $network")
            }
        }
        runCatching { connectivityManager().registerDefaultNetworkCallback(callback) }
            .onSuccess { appsEyeViewCallback = callback }
            .onFailure { logd("apps-eye: could not register (${it.message})") }
    }

    /** TEMP (diagnosis): the capability bits an app would act on, spelled out. */
    private fun capsDetail(caps: NetworkCapabilities): String {
        fun has(capability: Int) = caps.hasCapability(capability)
        return buildString {
            append(transportLabel(caps))
            append(" validated=").append(has(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            append(" internet=").append(has(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            append(" notSuspended=").append(has(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))
            append(" notMetered=").append(has(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            append(" notRestricted=").append(has(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
            // The one bit left out of the first pass, and the only one that plausibly differs between
            // Wi-Fi and this particular cellular network: the handset is roaming (the status bar shows
            // "R"). Apps and platform policy both treat roaming differently from ordinary mobile data.
            append(" notRoaming=").append(has(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
            append(" captivePortal=").append(has(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        }
    }

    private fun registerUnderlyingNetworkCallback() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logd("net onAvailable: $network ${capsLabel(network)}")
                // Seeding only. Deliberately NOT a recovery trigger: cellular typically appears
                // unvalidated and validates 1-3s later, so re-dialling here burns the attempt on a link
                // that cannot yet carry the QUIC handshake. onCapabilitiesChanged(VALIDATED) is the
                // real signal.
                // Adopt whenever we are currently holding NOTHING — not merely the first time ever.
                //
                // The gate used to be `!underlyingSeeded`, which is sticky for the life of the
                // process, so after a loss nulled the current network this branch did nothing and the
                // VPN advertised no underlying network at all until the replacement VALIDATED. In the
                // field that window was 1.8s long, and a perfectly usable cellular network had been
                // sitting there, announced and ignored, for 1.5s of it.
                //
                // Still not a recovery trigger: an unvalidated network cannot carry a QUIC handshake
                // yet, and `onCapabilitiesChanged(VALIDATED)` is the signal that it can.
                if (lastUnderlying == null) {
                    adoptUnderlying(network)
                    logd("underlying adopted (no current network): $network")
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                if (network == validatedUnderlying) return
                // Did the network the engine dialled over actually move, or is this just the
                // VALIDATED confirmation of the one it is already on?
                //
                // [engineNetwork] answers that; "have we seen a network before" does not.
                // `underlyingSeeded` is sticky for the life of the process, and `validatedUnderlying`
                // is cleared by `onLost`, so a Wi-Fi drop makes the return to cellular look like a
                // first sighting.
                //
                // A move means the upstream socket is gone, so recover at once. A confirmation means
                // the tunnel came up seconds ago on this very network — ask it, do not force a ladder
                // that can hold [operationMutex] for [T0_PATIENCE_MS] against a tunnel that is fine.
                val moved = engineNetwork != null && network != engineNetwork
                logd(
                    "underlying ${if (moved) "MOVED" else "confirmed"} (validated): " +
                        "$validatedUnderlying -> $network ${transportLabel(caps)}",
                )
                validatedUnderlying = network
                adoptUnderlying(network)
                startTunnelJob(
                    reason = if (moved) "path changed" else "network confirmed",
                    start = if (moved) TunnelStart.PATH_CHANGED else TunnelStart.PROBE_FIRST,
                    // A real move is an external event and may cut a pending retry's backoff short; a
                    // confirmation of the network the engine already runs on is not.
                    externalTrigger = moved,
                )
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network != lastUnderlying) return
                // The only signal for a route change that KEEPS the netId — Wi-Fi reassociation, an IPv6
                // prefix change, an inter-RAT handover. None of these fire onAvailable/onCapabilities,
                // so before this they produced no recovery at all. Compare a signature so the routine
                // DNS//MTU churn on a stable link doesn't restart the watcher on every callback.
                val signature = "${lp.interfaceName}|${lp.linkAddresses}|${lp.routes.size}"
                if (signature == lastLinkSignature) return
                // Seed silently. A null signature means we have never seen this network's link before —
                // which is true for every freshly started process, and for the first callback after a
                // switch. Treating that as a CHANGE is what put a brand-new tunnel on trial ~1.1s after
                // establish, condemned it, and killed the process; the replacement process then did the
                // same thing, which is the 8-second restart loop seen in the field on a network that
                // never actually changed. There is nothing to recover from on first sight of a link.
                val seeding = lastLinkSignature == null
                logd("net linkProps ${if (seeding) "seeded" else "changed"} on underlying: $lastLinkSignature -> $signature")
                lastLinkSignature = signature
                if (seeding) return
                startTunnelJob("link properties changed", TunnelStart.PROBE_FIRST)
            }

            override fun onLost(network: Network) {
                logd("net onLost: $network")
                if (network != lastUnderlying) return
                // Advertise the best network we still have rather than blanking to "follow the system
                // default". During a handover there is a window with no default at all, and a VPN agent
                // that inherits no transports shows the user neither a Wi-Fi nor a mobile indicator —
                // the status bar simply goes blank. The replacement link is usually already present
                // (merely not validated yet), and advertising it is closer to the truth than nothing.
                // Excluded explicitly: ConnectivityManager still enumerates a network for a moment after
                // announcing its loss, and still reports usable capabilities for it, so a plain scan
                // hands back the very network we were told is going away ("replaced after loss: 800 ->
                // 800" in the field).
                val replacement = scanBestUnderlying(excluding = network)
                logd("underlying after loss of $network: ${replacement ?: "nothing left"}")
                // Advertise the stand-in, but do NOT record it as the current underlying network.
                //
                // These are two different jobs. Advertising keeps the VPN's transports honest so the
                // status bar does not go blank mid-handover. Recording is what `onCapabilitiesChanged`
                // compares against to decide that the path has moved — and adopting the replacement
                // here makes its own VALIDATED callback arrive as "no change", so the handover produces
                // no recovery at all. Measured three times in one day: at 09:59:16 Wi-Fi dropped to
                // cellular and nothing triggered; the tunnel survived on luck, not on design.
                //
                // Null, not the departing network: either makes the next VALIDATED callback read as a
                // change, but null cannot be mistaken for a live link by anything that reads this.
                lastUnderlying = null
                if (network == validatedUnderlying) validatedUnderlying = null
                lastLinkSignature = null
                applyUnderlyingNetworks(replacement)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        // The field is assigned only on success, and a failure is loud. It used to be set first and
        // the result discarded, so a refused registration left `networkCallback != null` — which is
        // the early-return guard at the top of this method — and the service ran for the rest of the
        // session with no network triggers at all and not one line saying so.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Single best-matching underlying network — behaves like a default callback.
                connectivityManager().registerBestMatchingNetworkCallback(
                    request,
                    callback,
                    Handler(Looper.getMainLooper()),
                )
            } else {
                connectivityManager().registerNetworkCallback(request, callback)
            }
        }.onSuccess { networkCallback = callback }
            .onFailure {
                logd("FATAL: no underlying-network callback (${it.message}) — recovery is blind")
                errorReporter.report(ErrorDomain.VPN_TUNNEL, it)
            }
    }

    /**
     * A recoverable failure: keep everything, wait, try again. Never gives up.
     *
     * In-process on purpose. If xray never started there are no tun readers to strand, so there is
     * nothing a fresh process would buy — and staying alive is what lets a network callback or a
     * screen-on cut the wait short via [retryNow]. The service also stays foreground, so the tunnel
     * comes back without the user touching anything.
     */
    private fun scheduleRetry(reason: String) {
        // One counter with T2, not a second private one. They were split before — the backoff array was
        // reachable only from here, a path that fired zero times in a day of field logs, while the
        // restart path used a flat 500ms and produced nine restarts in four minutes. Same budget, same
        // reset, whichever rung is spending it.
        val attempt = ++engineRestartAttempt
        val wait = restartDelayFor(attempt)
        logd("retry: attempt=$attempt in ${wait}ms — $reason")
        retryPending = true
        // Report as still working on it, NOT as an error: the user asked for a VPN and we have not
        // stopped trying, so an error would be a lie and would also clear the UI's connect intent.
        publishStatus(ConnectionStatus.Connecting)
        retryJob?.cancel()
        retryJob = scope.launch {
            runCatching { stopTunnel() }
            delay(wait)
            attemptRetry("backoff elapsed")
        }
    }

    /** Cut a pending backoff short — something changed that might make this attempt succeed. */
    private fun retryNow(reason: String) {
        if (!retryPending) return
        logd("retry: $reason — attempting now instead of waiting")
        retryJob?.cancel()
        retryJob = scope.launch { attemptRetry(reason) }
    }

    private suspend fun attemptRetry(reason: String) {
        val config = activeXrayJson ?: paramsStore.load()?.xrayJson
        if (config == null) {
            logd("retry ($reason): nothing to reconnect to — standing down")
            retryPending = false
            return
        }
        logd("retry ($reason): reconnecting")
        runConnect(config, lastStartId)
    }

    /**
     * TERMINAL. Only for failures retrying cannot fix — no configuration at all, an allowlist whose
     * apps are gone, permission revoked. Everything network-shaped goes through [scheduleRetry].
     */
    private fun fail(message: String, startId: Int, category: VpnErrorCategory = VpnErrorCategory.UNKNOWN) {
        scope.launch {
            operationMutex.withLock {
                logd("fail (tearing down): $message")
                retryPending = false
                // Clear persisted config: a fatal failure must NOT crash-restore-crash in a loop.
                paramsStore.clear()
                stopMonitoring()
                analyticsManager.vpnError(category, terminal = true)
                reportSessionEnd(SessionEndReason.FATAL_ERROR)
                stopTunnel()
                publishStatus(ConnectionStatus.Error(message))
                stopUnlessRestarted(startId)
                finishTeardown()
            }
        }
    }

    // TEMP (diagnosis): all service logs go to the unified, process-tagged, flush-per-line file logger
    // (vpn-debug.log) plus Logcat. Remove this and all logd() call sites once recovery is confirmed.
    /** `establish()` returned null: the app is not prepared or was revoked. Not a network failure. */
    private class NotPreparedException : IllegalStateException("VPN permission is not granted")

    private fun logd(message: String) = OtcLog.log(TAG, message)

    /**
     * Stop — but only if no NEWER start has arrived since [startId].
     *
     * Correct for any start that lands during the SLOW part of a teardown — a user tapping Connect
     * while `stopTunnel()` is killing `:xray`, for instance.
     *
     * It does **not**, on its own, win the `VpnSyncWorker.restartWith` reapply race, and it was a
     * mistake to claim it did. [runUserDisconnect] publishes `Disconnected` — which is what releases
     * the worker — on the statement immediately before this one, with no suspension point between them.
     * The worker parks on a different dispatcher, so its resumption is queued rather than run inline,
     * and it still has to wake a thread, log, build an intent and issue its own (heavier) AMS
     * transaction. This thread's `stopServiceToken` gets to AMS first, `stopSelfResult` returns true,
     * and the service is destroyed anyway. Fixing the reapply needs the teardown taken off that path
     * entirely — see §14.
     *
     * [startId] must be the id of the start that STARTED this teardown, captured synchronously in
     * [onStartCommand]. Reading [lastStartId] here instead would invert the test — it would be the
     * newer start's own id, and the service would stop precisely when it must not.
     */
    private fun stopUnlessRestarted(startId: Int) {
        // stopSelfResult, not stopSelf: the boolean is the only way to see this happen on a device, and
        // it is also what decides the foreground state below.
        if (!stopSelfResult(startId)) {
            // Deliberately leaves the foreground notification up. Dropping it here was the first version
            // of this fix and it was wrong: the newer start has already promoted us and is about to
            // build a tun, so clearing foreground would leave a VPN running as a background service —
            // killable, and a broken FGS contract on the very API levels that throw about it.
            logd("stopSelf($startId) skipped — a newer start is pending; staying foreground for it")
            return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Guarded, because this now runs on the teardown starts too and an uncaught throw here would be a
     * NEW crash on a path that never called it. `startForeground` can refuse (the background-start
     * rules), and when it does the system stops us regardless — so a logged non-fatal carrying our own
     * [ErrorDomain] is strictly more useful than an opaque death with a system stack.
     */
    private fun startAsForeground() {
        runCatching {
            ensureNotificationChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundServiceType(),
            )
        }.onFailure { error ->
            // OtcLog needs nothing from the graph; the reporter is a Koin lookup, and this is the
            // earliest line in onStartCommand — so it gets its own guard rather than a chance to throw
            // out of the handler for the failure it is reporting.
            logd("startForeground REFUSED: ${error.message}")
            runCatching { errorReporter.report(ErrorDomain.VPN_TUNNEL, error) }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // The app's own mark. This was `stat_sys_warning` — the system's warning triangle — so a
            // healthy tunnel advertised itself in the status bar with an error glyph.
            .setSmallIcon(R.drawable.ic_app_mono)
            .setContentTitle("Delta VPN")
            .setContentText("VPN connection is active")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.onthecrow.deltavpn.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.onthecrow.deltavpn.vpn.DISCONNECT"
        const val ACTION_REVOKE = "com.onthecrow.deltavpn.vpn.REVOKE"
        const val EXTRA_XRAY_JSON = "com.onthecrow.deltavpn.vpn.EXTRA_XRAY_JSON"
        const val EXTRA_DISALLOW = "com.onthecrow.deltavpn.vpn.EXTRA_DISALLOW"
        const val EXTRA_ALLOW = "com.onthecrow.deltavpn.vpn.EXTRA_ALLOW"
        private const val TAG = "DeltaVpn"

        // Group 1 is the key and opening quote (kept, so a redaction can splice a placeholder in),
        // group 2 the secret itself (used only for a short fingerprint, never logged whole).
        private val CREDENTIAL_REGEX = Regex("(\"(?:id|password|auth)\"\\s*:\\s*\")([^\"]+)\"")
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001



        // Restart backoff has to survive the kill, or each fresh process starts clean and hot-loops against a
        // server that is simply down. Reset once a restart is this old, or on any confirmed recovery.
        /**
         * Wait before the Nth retry. Uncapped in COUNT — we never stop trying while the user wants the
         * VPN on — but capped in RATE, so a phone in a tunnel or on a plane settles at one attempt per
         * five minutes instead of spinning. Deliberately plain `delay()`, not an alarm: it must not
         * wake a sleeping device. The events that matter (a validated network appearing, idle exit,
         * screen on) short-circuit the wait anyway, so the timer is only the fallback.
         */
        private val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 60_000, 300_000)

        // Floor under [restartDelayFor]: the process must actually die before the alarm fires, since an
        // alarm delivered into a still-terminating process can be dropped, and a LOST restart is an
        // unbounded leak whereas this delay is a bounded one. Err long, not short.

        private const val XRAY_PROCESS_SUFFIX = ":xray"
        private const val EXIT_REASONS_TO_LOG = 5

        // A session with this many confirmed-DEAD keepalive probes is reported as DEGRADED (a single
        // blip is tolerated by KEEPALIVE_FAILS_BEFORE_RECOVER, so one dead probe is not "degraded").
        private const val KEEPALIVE_DEGRADED_DEAD_THRESHOLD = 3

        private const val WAKELOCK_TAG = "DeltaVPN:recovery"

        // Safety net only (the wakelock is released in a `finally`), but it MUST outlast the whole
        // ladder: T0's patience is [T0_PATIENCE_MS] of awake time, plus the engine restart after it. If
        // it expired first the CPU could suspend mid-recovery, and the ladder would then abandon itself
        // over sleep that its own short wakelock had caused.
        private const val RECOVERY_WAKELOCK_TIMEOUT_MS = 60_000L

        // How long T0 keeps asking before condemning the tunnel.
        //
        // This number is not a guess about our own code — it is sized to something we now measure
        // directly in the xray log. Nothing we do repairs a tunnel whose path has moved: hysteria2
        // keeps using the QUIC session bound to the vanished interface until quic-go's idle timeout
        // expires, and only the request after that dials again. Two field recoveries, timed from the
        // service log against `proxy/hysteria: connection ends > timeout: no recent network activity`
        // in xray's own output:
        //
        //   Wi-Fi -> cellular, screen off       idle timeout at +13.4s, tunnel back at +18.9s
        //   wake from deep sleep, screen on     idle timeout at +21.6s, tunnel back at +25.4s
        //
        // The second is the expensive one: the idle timer is a Go timer on CLOCK_MONOTONIC, which does
        // not advance while the CPU is suspended, so a path that died during Doze is not noticed until
        // a full timeout has elapsed AWAKE. 25.4s of a 30s budget is not a margin, it is a coin flip,
        // and losing it means a redial and a process restart that could not have helped — the blink is
        // back. xray-core's default MaxIdleTimeout is 30s and it accepts up to 120s, so the bound we
        // are waiting on is genuinely ~30s plus the re-dial.
        //
        // Cheap insurance: this is only ever spent while the tunnel is NOT answering, and a live one
        // still exits on the first probe in ~110ms. Lower it once we set `maxIdleTimeout` ourselves.
        private const val T0_PATIENCE_MS = 45_000L
        private const val T0_PROBE_RETRY_DELAY_MS = 700L

        // Handed to xray so a dead QUIC path is abandoned in ~10s instead of ~30s. [T0_PATIENCE_MS]
        // must stay comfortably above the idle timeout plus the re-dial, or the ladder escalates into a
        // repair while the engine is still on its way to fixing itself.
        private const val QUIC_MAX_IDLE_TIMEOUT_S = 10
        private const val QUIC_KEEPALIVE_PERIOD_S = 3

        // How much CPU suspend a ladder tolerates before its findings are treated as stale. Generous
        // enough that ordinary sub-second naps between probes do not trip it, small enough that no rung
        // ever acts on evidence from a previous Doze window.
        private const val LADDER_ABANDON_SLEEP_MS = 20_000L

        // How long a freshly restarted engine gets to prove itself before we call the attempt failed.
        // A measured cold hysteria2 handshake was ~250ms; this waits out three of them.
        private val ENGINE_PROBE_DELAYS_MS = longArrayOf(500L, 500L, 1_000L)

        // The tun's own address. Shared with the Builder so the probe's "am I actually on the tunnel?"
        // assertion can never drift from what we configured.
        private const val TUN_ADDRESS = "10.77.0.2"

        // Health probe (DNS round-trip through the tunnel). A healthy upstream answers well inside the
        // first step — measured p50 112ms, p90 193ms, and only 0.4% of 2102 successful probes exceeded
        // 500ms — so 600ms is the right question to ask a tunnel that is already up, and it is what the
        // keepalive and the first ask of a trigger use.
        private const val PROBE_TIMEOUT_MS = 600

        // Inside the ladder the timeout GROWS, because there the question is different: not "is this
        // live tunnel still live" but "has this path come up yet". A proxy handshake over a freshly
        // attached LTE bearer has to fit a dial, a QUIC handshake and a DNS round trip into the budget,
        // and 600ms does not cover one lost-and-retransmitted packet, let alone a cold handshake. Every
        // probe failure ever logged was a timeout, never an error, so a short deadline does not detect
        // breakage faster — it only manufactures it.
        private val PROBE_TIMEOUT_STEPS_MS = intArrayOf(600, 1_200, 2_500, 4_000)

        // The default ladder: this file's own numbers, so "toggle off" is the behaviour that shipped.
        private val PATIENT_TUNING = RecoveryTuning(
            t0PatienceMs = T0_PATIENCE_MS,
            t0ProbeTimeoutsMs = PROBE_TIMEOUT_STEPS_MS,
            aggressive = false,
        )

        // "Aggressive keepalive" (opt-in): T0 as it was before the `:xray` split — two 600ms probes,
        // 700ms apart, then condemn. It buys a faster reaction on a path that is genuinely gone, and
        // pays for it on one that is merely slow to come up: a cold LTE bearer measured 23s between
        // "cell appeared" and the first probe that could succeed, and this budget condemns it long
        // before that. What it escalates INTO is the current mechanism, not the old one — `:xray` is
        // restarted on the tun we already hold, so the tun is never closed and the VPN icon never blinks.
        //
        // TWO probes, not one, is the load-bearing part: the old ladder probed more than once precisely
        // so that a single blip on a lossy link could not buy a repair. The budget is a TIME window here
        // rather than the old `repeat(2)`, so it has to be sized to admit the second probe with margin:
        //   after probe 1 + gap  = 600 + 700  = 1300ms  → must be INSIDE  the window
        //   after probe 2 + gap  = 1900 + 700 = 2600ms  → must be OUTSIDE the window
        // i.e. anything in (1300, 2600]. 1900 sits mid-band — it leaves 600ms of headroom for a probe
        // that runs long (probeTunnel can add up to PROBE_ESTABLISH_GRACE_MS when a ladder starts right
        // after establish, which at 1400 would have silently dropped it to a ONE-probe condemn) while
        // keeping a third probe 700ms out of reach for a probe that spends its timeout. It is also
        // exactly the old 600+700+600 total.
        //
        // Probes that fail FAST rather than time out (an INCONCLUSIVE return, which measured nothing)
        // do fit a third attempt into the same window. That is the harmless direction: the budget is a
        // floor on how long a quiet tunnel gets, never a cap on how many chances it gets, and every
        // failure ever logged in the field was a timeout anyway.
        private const val AGGRESSIVE_T0_PATIENCE_MS = 1_900L
        private val AGGRESSIVE_PROBE_TIMEOUT_STEPS_MS = intArrayOf(600)
        private val AGGRESSIVE_TUNING = RecoveryTuning(
            t0PatienceMs = AGGRESSIVE_T0_PATIENCE_MS,
            t0ProbeTimeoutsMs = AGGRESSIVE_PROBE_TIMEOUT_STEPS_MS,
            aggressive = true,
        )

        private const val PROBE_DNS_TXID = 0x0C0D
        private const val PROBE_DNS_SERVER = "1.1.1.1"
        private const val PROBE_DNS_NAME = "cloudflare.com"
        private const val DNS_HEADER_SIZE = 12

        // A probe fired within this long of establish() races netd's per-UID rule installation and would
        // egress on the physical network, so it is delayed rather than answered. Measured false-positive
        // probes were 71-135ms after establish; honest ones >=250ms.
        private const val PROBE_ESTABLISH_GRACE_MS = 500L

        // TEMP (diagnosis): the TCP counterpart runs every Nth keepalive — roughly once a minute with
        // the screen on — so it costs nothing while still catching a lasting UDP/TCP divergence. Its
        // timeout is generous because it is not a liveness verdict and a slow answer is still an answer.
        private const val TCP_PROBE_EVERY_N_ROUNDS = 8
        private const val TCP_PROBE_TIMEOUT_MS = 5_000

        // How far past its own deadline a probe must run before we read it as "the process was frozen"
        // rather than "the tunnel is dead". Doze suspends stretch a 600ms probe to seconds or minutes;
        // ordinary jitter does not come close to 4x.
        private const val FROZEN_PROBE_FACTOR = 4
        private const val PROBE_FROZEN_RETRIES = 2

        // Steady-state keepalive while the screen is on: probe this often; this many consecutive failures
        // (one blip tolerated) trigger recovery. The probe doubles as a real upstream keepalive. Tunable.
        private const val KEEPALIVE_INTERVAL_MS = 8_000L

        // Screen off: keep watching, just cheaply. This does not run during CPU suspend at all, so it
        // costs nothing in deep Doze — it only bounds the dead window across awake moments.
        private const val KEEPALIVE_INTERVAL_SCREEN_OFF_MS = 60_000L
        private const val KEEPALIVE_FAILS_BEFORE_RECOVER = 2
    }
}
