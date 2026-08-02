package com.onthecrow.deltavpn.analytics

/**
 * An [AnalyticsManager] that does nothing. Useful for tests, Compose previews, and any wiring that
 * needs the dependency without emitting events.
 */
object NoOpAnalyticsManager : AnalyticsManager {
    override fun appInitialized(platform: String) = Unit
    override fun sourceAdded(kind: SourceKind, sourceCount: Int) = Unit
    override fun sourceAddFailed(kind: SourceKind, reason: AddFailureReason) = Unit
    override fun configSelected(sourceKind: SourceKind, isSwitch: Boolean) = Unit
    override fun connectTapped(hadSelection: Boolean, alreadyRunning: Boolean) = Unit
    override fun vpnConsentShown() = Unit
    override fun vpnConsentResult(result: ConsentResult) = Unit
    override fun vpnPermissionResult(result: VpnPermissionOutcome) = Unit
    override fun connectResult(failureCategory: ConnectFailureCategory?) = Unit
    override fun vpnConnected(via: ConnectVia) = Unit
    override fun vpnError(category: VpnErrorCategory, terminal: Boolean) = Unit
    override fun vpnSessionEnd(reason: SessionEndReason, sessionDurationMs: Long) = Unit
    override fun vpnRecovery(
        trigger: RecoveryTrigger,
        outcome: RecoveryOutcome,
        attempts: Int,
        durationMs: Long,
        transport: Transport?,
        mode: RecoveryMode,
    ) = Unit
    override fun vpnEngineDeath(reason: EngineDeathReason) = Unit
    override fun vpnTunnelConfirmed(firstProbeMs: Long, via: ConfirmVia) = Unit
    override fun vpnTunRebuild(outcome: TunRebuildOutcome) = Unit
    override fun vpnKeepaliveHealth(window: KeepaliveWindow, deadCount: Int, inconclusiveCount: Int) = Unit
    override fun tunnelAutoRestart(reason: AutoRestartReason, result: AutoRestartResult) = Unit
    override fun subscriptionRevokedRemote(wasActive: Boolean) = Unit
    override fun sourceRefreshed(kind: SourceKind, failureReason: RefreshFailureReason?) = Unit
    override fun sourceDeleted(kind: SourceKind, wasActive: Boolean) = Unit
    override fun connectConfigValidation(invalidReason: ConfigInvalidReason?) = Unit
    override fun splitTunnelApplied(mode: SplitTunnelMode, appCount: Int) = Unit
    override fun splitTunnelOpened() = Unit
    override fun settingsPushBypassToggled(enabled: Boolean) = Unit
    override fun settingsAggressiveKeepaliveToggled(enabled: Boolean) = Unit
    override fun diagnosticsLogShared() = Unit
    override fun settingsOpened() = Unit
    override fun qsTileAction(action: QsTileAction, blockedReason: QsBlockedReason) = Unit
    override fun disconnectTapped(entryPoint: DisconnectEntryPoint) = Unit
}
