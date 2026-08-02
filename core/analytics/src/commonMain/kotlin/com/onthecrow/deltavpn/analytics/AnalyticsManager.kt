package com.onthecrow.deltavpn.analytics

/**
 * The single entry point for product analytics in DeltaVPN.
 *
 * Each method corresponds to exactly one Firebase Analytics event; call the method (passing typed
 * parameters) at the point the event happens. The implementation ([AnalyticsManagerImpl]) builds the
 * matching event and forwards it to the cross-platform `AnalyticsTracker` — Firebase on Android, a
 * no-op/log on iOS and JVM — so callers stay platform-agnostic and never touch Firebase directly.
 *
 * ### Privacy contract (this is a VPN — read before adding a method)
 * Analytics may carry **outcomes, enums, booleans, and coarse buckets only**. It must NEVER carry a
 * server address/port, credential, config share-link/URL, traffic destination, the subscription/bundle
 * id (that would create a Data-safety *User ID*), a split-tunnel package name, or any raw
 * count/duration/timestamp. This contract is enforced structurally: no method here takes a free-form
 * `String` (the sole exception, [appInitialized]'s `platform`, is the OS version), counts and durations
 * are passed as numbers and **bucketed on-device** inside the events, and categories come in as closed
 * enums that callers must map from their own sealed types with a `when` — never by forwarding a
 * `*.message`. See `docs/analytics-events.md` for the full plan and the deny-list.
 *
 * ### Behaviour
 * Calls are fire-and-forget and cheap; they no-op unless Firebase is configured (release + a real
 * `google-services.json`), so calling from debug or a test is harmless. Firing is **not** gated on the
 * VPN-consent screen — several events (`app_initialized`, `vpn_consent_shown`, `vpn_consent_result`)
 * legitimately fire before/around consent and are needed to measure the funnel.
 *
 * Do **not** call any of these from the `:xray` process — Firebase is initialised only in the main app
 * process. Every VPN-service method below is meant to be called from the main process.
 */
interface AnalyticsManager {

    // ---- Activation funnel -------------------------------------------------

    /**
     * `app_initialized` — once per main-process start; the funnel-entry denominator.
     * @param platform coarse OS label (e.g. `"Android 34"`) — the OS version only, never a device name.
     */
    fun appInitialized(platform: String)

    /**
     * `source_added` — a configuration source was successfully added (activation step 1).
     * @param kind which add path succeeded (derived from the use-case branch, not the pasted content).
     * @param sourceCount total configured sources after the add (bucketed on-device).
     */
    fun sourceAdded(kind: SourceKind, sourceCount: Int)

    /**
     * `source_add_failed` — adding a source failed.
     * @param kind which add path was attempted.
     * @param reason bounded failure category — map from the failure branch, never from its message.
     */
    fun sourceAddFailed(kind: SourceKind, reason: AddFailureReason)

    /**
     * `config_selected` — the active configuration was chosen.
     * @param sourceKind the kind of source the selected config belongs to (a category, not an id).
     * @param isSwitch whether a different config was already selected before.
     */
    fun configSelected(sourceKind: SourceKind, isSwitch: Boolean)

    /**
     * `connect_tapped` — the Connect control was tapped.
     * @param hadSelection whether a config was selected at tap time.
     * @param alreadyRunning whether the tunnel was connected/busy, so the tap acted as Stop.
     */
    fun connectTapped(hadSelection: Boolean, alreadyRunning: Boolean)

    /** `vpn_consent_shown` — the prominent VPN disclosure was shown (first connect). */
    fun vpnConsentShown()

    /**
     * `vpn_consent_result` — the user accepted or dismissed the VPN disclosure.
     * @param result [ConsentResult.ACCEPTED] or [ConsentResult.DECLINED].
     */
    fun vpnConsentResult(result: ConsentResult)

    /**
     * `vpn_permission_result` — outcome of the OS `VpnService.prepare()` dialog.
     * @param result granted, denied, or found missing at establish time.
     */
    fun vpnPermissionResult(result: VpnPermissionOutcome)

    /**
     * `connect_result` — the connect attempt's outcome at the controller boundary.
     * @param failureCategory `null` if the attempt started; a bounded category if it failed before the
     *   tunnel came up (mapped from the sealed result/exception type, never a message).
     */
    fun connectResult(failureCategory: ConnectFailureCategory?)

    /**
     * `vpn_connected` — the tunnel reached Connected (the honest funnel success).
     * @param via whether this was a fresh user connect or a recovery/restore re-establish.
     */
    fun vpnConnected(via: ConnectVia)

    /**
     * `vpn_error` — a terminal connection failure (the counterpart of [vpnConnected]).
     * @param category bounded error category, mapped from the error TYPE (never `Error.message`).
     * @param terminal whether this was a terminal teardown vs a transient/retryable error.
     */
    fun vpnError(category: VpnErrorCategory, terminal: Boolean)

    // ---- Session & reliability (main process) ------------------------------

    /**
     * `vpn_session_end` — a session that reached Connected tore down.
     * @param reason user, external revoke, or fatal error (hardcoded per teardown path).
     * @param sessionDurationMs how long the session was up (bucketed on-device).
     */
    fun vpnSessionEnd(reason: SessionEndReason, sessionDurationMs: Long)

    /**
     * `vpn_recovery` — one recovery-ladder run's outcome (emit once per run, never per probe).
     * @param trigger what started the run (mapped from the internal reason, never the raw string).
     * @param outcome how it resolved (`UNRECOVERED_BACKOFF` = will retry; recovery is uncapped).
     * @param attempts engine-restart attempts so far (bucketed on-device).
     * @param durationMs elapsed ladder time (bucketed on-device).
     * @param transport optional coarse underlying-transport type — the only permitted network detail.
     * @param mode which recovery profile ran (the "aggressive keepalive" switch).
     */
    fun vpnRecovery(
        trigger: RecoveryTrigger,
        outcome: RecoveryOutcome,
        attempts: Int,
        durationMs: Long,
        transport: Transport?,
        mode: RecoveryMode,
    )

    /**
     * `vpn_engine_death` — the `:xray` engine process died (a potential clear-text leak window).
     * @param reason mapped from `ApplicationExitInfo.reason` (the int), never its description.
     */
    fun vpnEngineDeath(reason: EngineDeathReason)

    /**
     * `vpn_tunnel_confirmed` — the first probe of a session actually carried traffic (time-to-usable).
     * Emit once per session.
     * @param firstProbeMs elapsed time to the first confirmation (bucketed on-device).
     * @param via whether the confirmation came from a fresh connect or a recovery.
     */
    fun vpnTunnelConfirmed(firstProbeMs: Long, via: ConfirmVia)

    /**
     * `vpn_tun_rebuild` — outcome of rebuilding the tun on a confirmed path change.
     * @param outcome rebuilt, establish failed, or engine did not come back.
     */
    fun vpnTunRebuild(outcome: TunRebuildOutcome)

    /**
     * `vpn_keepalive_health` — one per session, rolled up from keepalive probes.
     * @param window the session-level verdict.
     * @param deadCount confirmed-DEAD probes (bucketed); [inconclusiveCount] Doze-frozen probes
     *   (bucketed) are reported separately so a Doze freeze is never scored as a failure.
     */
    fun vpnKeepaliveHealth(window: KeepaliveWindow, deadCount: Int, inconclusiveCount: Int)

    // ---- Config sources ----------------------------------------------------

    /**
     * `tunnel_auto_restart` — a live tunnel re-established because a setting changed (not a failure).
     * @param reason selection change vs split-tunnel routing change.
     * @param result restarted, or kept on the old config because the new one was invalid.
     */
    fun tunnelAutoRestart(reason: AutoRestartReason, result: AutoRestartResult)

    /**
     * `subscription_revoked_remote` — a source was removed remotely.
     * @param wasActive whether the revoked source held the active selection (an involuntary disconnect).
     */
    fun subscriptionRevokedRemote(wasActive: Boolean)

    /**
     * `source_refreshed` — a subscription source was re-fetched (xray links do not refresh).
     * @param kind the subscription kind ([SourceKind.SUBSCRIPTION_ID] or [SourceKind.SUBSCRIPTION_URL]).
     * @param failureReason `null` on success; a bounded reason on failure (never the network message).
     */
    fun sourceRefreshed(kind: SourceKind, failureReason: RefreshFailureReason?)

    /**
     * `source_deleted` — a source was removed.
     * @param kind the kind of source removed.
     * @param wasActive whether the delete forced a live disconnect.
     */
    fun sourceDeleted(kind: SourceKind, wasActive: Boolean)

    /**
     * `connect_config_validation` — the selected config was re-validated at connect time.
     * @param invalidReason `null` if valid; a bounded reason if invalid (a link can die between add
     *   and connect).
     */
    fun connectConfigValidation(invalidReason: ConfigInvalidReason?)

    // ---- Settings & surfaces ----------------------------------------------

    /**
     * `split_tunnel_applied` — a split-tunnel edit was committed.
     * @param mode the routing mode.
     * @param appCount selected-app count (bucketed on-device; never the package names or exact count).
     */
    fun splitTunnelApplied(mode: SplitTunnelMode, appCount: Int)

    /** `split_tunnel_opened` — the split-tunnel screen was opened (feature discovery). */
    fun splitTunnelOpened()

    /**
     * `settings_push_bypass_toggled` — the "allow notifications under VPN" switch changed.
     * @param enabled the new switch state.
     */
    fun settingsPushBypassToggled(enabled: Boolean)

    /**
     * `settings_aggressive_keepalive_toggled` — the "aggressive keepalive" switch changed.
     *
     * Pairs with [RecoveryMode] on [vpnRecovery]: this says how many users opt in, that says whether the
     * impatient ladder actually recovers more often than the default one.
     * @param enabled the new switch state.
     */
    fun settingsAggressiveKeepaliveToggled(enabled: Boolean)

    /** `diagnostics_log_shared` — the diagnostic-log share sheet was opened. */
    fun diagnosticsLogShared()

    /** `settings_opened` — the settings screen was opened. */
    fun settingsOpened()

    /**
     * `qs_tile_action` — a Quick-Settings tile action (a connect entry point outside the app UI).
     * @param action connect, disconnect, or blocked.
     * @param blockedReason why a connect was blocked; [QsBlockedReason.NONE] when not blocked.
     */
    fun qsTileAction(action: QsTileAction, blockedReason: QsBlockedReason = QsBlockedReason.NONE)

    /**
     * `disconnect_tapped` — a deliberate stop.
     * @param entryPoint which surface the stop came from.
     */
    fun disconnectTapped(entryPoint: DisconnectEntryPoint)
}
