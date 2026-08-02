package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.uicore.Reducer

internal class SplitTunnelReducer : Reducer<SplitTunnelState, SplitTunnelEvent> {
    override suspend fun reduce(
        state: SplitTunnelState,
        event: SplitTunnelEvent,
    ): SplitTunnelState = when (event) {
        is SplitTunnelEvent.OnModeChanged -> state.copy(mode = event.mode)

        is SplitTunnelEvent.OnAppToggled -> state.copy(
            selectedPackages = if (event.packageName in state.selectedPackages) {
                state.selectedPackages - event.packageName
            } else {
                state.selectedPackages + event.packageName
            },
        )

        is SplitTunnelEvent.OnQueryChanged -> state.copy(query = event.query)
        SplitTunnelEvent.OnQueryCleared -> state.copy(query = "")

        is SplitTunnelEvent.OnAppsLoaded -> state.copy(apps = event.apps, appsLoaded = true)

        // Never clobber a draft the user is in the middle of: a write from elsewhere (the settings
        // screen's push toggle shares this record) would otherwise wipe their unapplied selection.
        // hasChanges is false until the first emission has landed, so the very first one always seeds
        // the draft even if the user managed to touch the mode selector while it was still loading.
        is SplitTunnelEvent.OnSettingsLoaded -> if (state.hasChanges) {
            state.copy(
                savedMode = event.mode,
                savedPackages = event.selectedPackages,
                settingsLoaded = true,
            )
        } else {
            state.copy(
                mode = event.mode,
                selectedPackages = event.selectedPackages,
                savedMode = event.mode,
                savedPackages = event.selectedPackages,
                settingsLoaded = true,
            )
        }

        // Both are handled in the ViewModel (persist / navigate); the draft stays as it is, and the
        // reload that follows a successful write collapses it back to "no changes".
        SplitTunnelEvent.OnApplyClick, SplitTunnelEvent.OnBackClick -> state
    }
}
