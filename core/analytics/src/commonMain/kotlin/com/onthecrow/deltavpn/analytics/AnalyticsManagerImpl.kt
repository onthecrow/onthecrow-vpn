package com.onthecrow.deltavpn.analytics

import com.onthecrow.deltavpn.analytics.events.AnalyticsEvent
import com.onthecrow.deltavpn.analytics.events.AppInitializedEvent
import com.onthecrow.deltavpn.analytics.events.ConfigSelectedEvent
import com.onthecrow.deltavpn.analytics.events.ConnectConfigValidationEvent
import com.onthecrow.deltavpn.analytics.events.ConnectResultEvent
import com.onthecrow.deltavpn.analytics.events.ConnectTappedEvent
import com.onthecrow.deltavpn.analytics.events.DiagnosticsLogSharedEvent
import com.onthecrow.deltavpn.analytics.events.DisconnectTappedEvent
import com.onthecrow.deltavpn.analytics.events.QsTileActionEvent
import com.onthecrow.deltavpn.analytics.events.SettingsOpenedEvent
import com.onthecrow.deltavpn.analytics.events.SettingsAggressiveKeepaliveToggledEvent
import com.onthecrow.deltavpn.analytics.events.SettingsPushBypassToggledEvent
import com.onthecrow.deltavpn.analytics.events.SourceAddFailedEvent
import com.onthecrow.deltavpn.analytics.events.SourceAddedEvent
import com.onthecrow.deltavpn.analytics.events.SourceDeletedEvent
import com.onthecrow.deltavpn.analytics.events.SourceRefreshedEvent
import com.onthecrow.deltavpn.analytics.events.SplitTunnelAppliedEvent
import com.onthecrow.deltavpn.analytics.events.SplitTunnelOpenedEvent
import com.onthecrow.deltavpn.analytics.events.SubscriptionRevokedRemoteEvent
import com.onthecrow.deltavpn.analytics.events.TunnelAutoRestartEvent
import com.onthecrow.deltavpn.analytics.events.VpnConnectedEvent
import com.onthecrow.deltavpn.analytics.events.VpnConsentResultEvent
import com.onthecrow.deltavpn.analytics.events.VpnConsentShownEvent
import com.onthecrow.deltavpn.analytics.events.VpnEngineDeathEvent
import com.onthecrow.deltavpn.analytics.events.VpnErrorEvent
import com.onthecrow.deltavpn.analytics.events.VpnKeepaliveHealthEvent
import com.onthecrow.deltavpn.analytics.events.VpnPermissionResultEvent
import com.onthecrow.deltavpn.analytics.events.VpnRecoveryEvent
import com.onthecrow.deltavpn.analytics.events.VpnSessionEndEvent
import com.onthecrow.deltavpn.analytics.events.VpnTunRebuildEvent
import com.onthecrow.deltavpn.analytics.events.VpnTunnelConfirmedEvent
import com.onthecrow.deltavpn.firebase.AnalyticsTracker

/**
 * Default [AnalyticsManager]. Turns each typed call into its [AnalyticsEvent] and forwards the event's
 * name + already-privacy-rendered params to the cross-platform [AnalyticsTracker]. Fire-and-forget:
 * the tracker itself no-ops unless Firebase is configured, so this is safe to call from any build.
 */
internal class AnalyticsManagerImpl(
    private val tracker: AnalyticsTracker,
) : AnalyticsManager {

    private fun track(event: AnalyticsEvent) = tracker.logEvent(event.name, event.params())

    override fun appInitialized(platform: String) = track(AppInitializedEvent(platform))

    override fun sourceAdded(kind: SourceKind, sourceCount: Int) =
        track(SourceAddedEvent(kind, sourceCount))

    override fun sourceAddFailed(kind: SourceKind, reason: AddFailureReason) =
        track(SourceAddFailedEvent(kind, reason))

    override fun configSelected(sourceKind: SourceKind, isSwitch: Boolean) =
        track(ConfigSelectedEvent(sourceKind, isSwitch))

    override fun connectTapped(hadSelection: Boolean, alreadyRunning: Boolean) =
        track(ConnectTappedEvent(hadSelection, alreadyRunning))

    override fun vpnConsentShown() = track(VpnConsentShownEvent())

    override fun vpnConsentResult(result: ConsentResult) = track(VpnConsentResultEvent(result))

    override fun vpnPermissionResult(result: VpnPermissionOutcome) =
        track(VpnPermissionResultEvent(result))

    override fun connectResult(failureCategory: ConnectFailureCategory?) =
        track(ConnectResultEvent(failureCategory))

    override fun vpnConnected(via: ConnectVia) = track(VpnConnectedEvent(via))

    override fun vpnError(category: VpnErrorCategory, terminal: Boolean) =
        track(VpnErrorEvent(category, terminal))

    override fun vpnSessionEnd(reason: SessionEndReason, sessionDurationMs: Long) =
        track(VpnSessionEndEvent(reason, sessionDurationMs))

    override fun vpnRecovery(
        trigger: RecoveryTrigger,
        outcome: RecoveryOutcome,
        attempts: Int,
        durationMs: Long,
        transport: Transport?,
        mode: RecoveryMode,
    ) = track(VpnRecoveryEvent(trigger, outcome, attempts, durationMs, transport, mode))

    override fun vpnEngineDeath(reason: EngineDeathReason) = track(VpnEngineDeathEvent(reason))

    override fun vpnTunnelConfirmed(firstProbeMs: Long, via: ConfirmVia) =
        track(VpnTunnelConfirmedEvent(firstProbeMs, via))

    override fun vpnTunRebuild(outcome: TunRebuildOutcome) = track(VpnTunRebuildEvent(outcome))

    override fun vpnKeepaliveHealth(window: KeepaliveWindow, deadCount: Int, inconclusiveCount: Int) =
        track(VpnKeepaliveHealthEvent(window, deadCount, inconclusiveCount))

    override fun tunnelAutoRestart(reason: AutoRestartReason, result: AutoRestartResult) =
        track(TunnelAutoRestartEvent(reason, result))

    override fun subscriptionRevokedRemote(wasActive: Boolean) =
        track(SubscriptionRevokedRemoteEvent(wasActive))

    override fun sourceRefreshed(kind: SourceKind, failureReason: RefreshFailureReason?) =
        track(SourceRefreshedEvent(kind, failureReason))

    override fun sourceDeleted(kind: SourceKind, wasActive: Boolean) =
        track(SourceDeletedEvent(kind, wasActive))

    override fun connectConfigValidation(invalidReason: ConfigInvalidReason?) =
        track(ConnectConfigValidationEvent(invalidReason))

    override fun splitTunnelApplied(mode: SplitTunnelMode, appCount: Int) =
        track(SplitTunnelAppliedEvent(mode, appCount))

    override fun splitTunnelOpened() = track(SplitTunnelOpenedEvent())

    override fun settingsPushBypassToggled(enabled: Boolean) =
        track(SettingsPushBypassToggledEvent(enabled))

    override fun settingsAggressiveKeepaliveToggled(enabled: Boolean) =
        track(SettingsAggressiveKeepaliveToggledEvent(enabled))

    override fun diagnosticsLogShared() = track(DiagnosticsLogSharedEvent())

    override fun settingsOpened() = track(SettingsOpenedEvent())

    override fun qsTileAction(action: QsTileAction, blockedReason: QsBlockedReason) =
        track(QsTileActionEvent(action, blockedReason))

    override fun disconnectTapped(entryPoint: DisconnectEntryPoint) =
        track(DisconnectTappedEvent(entryPoint))
}
