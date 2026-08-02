package com.onthecrow.deltavpn.settings

import com.onthecrow.deltavpn.uicore.State
import com.onthecrow.deltavpn.vpn.model.InstalledApp
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode

/**
 * The screen edits a DRAFT and commits it on Apply, rather than persisting each tap.
 *
 * Persisting per tap would be worse than noisy: a split-tunnel change while connected rebuilds the
 * tun, so every checkbox would cost a reconnect. Holding [savedMode]/[savedPackages] alongside the
 * draft is also what tells us whether anything is actually pending — see [hasChanges], which drives
 * the Apply button in and out of view.
 */
internal data class SplitTunnelState(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val selectedPackages: Set<String> = emptySet(),
    val savedMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val savedPackages: Set<String> = emptySet(),
    val apps: List<InstalledApp> = emptyList(),
    val query: String = "",
    val appsLoaded: Boolean = false,
    /**
     * Whether [savedMode]/[savedPackages] are the REAL persisted values rather than the constructor
     * defaults. Without this, "has the user changed anything?" would be measured against invented
     * defaults before the first emission arrives, and an edit made in that window would both look
     * like a change and block the real values from ever seeding the draft.
     */
    val settingsLoaded: Boolean = false,
) : State {

    val loading: Boolean
        get() = !appsLoaded || !settingsLoaded

    val hasChanges: Boolean
        get() = settingsLoaded && (mode != savedMode || selectedPackages != savedPackages)

    /**
     * Apps matching the search box. A blank query — including one that is only whitespace — shows
     * everything. Matches the package name too, so "com.google" narrows the list the way someone
     * looking for a specific app would expect.
     */
    val visibleApps: List<InstalledApp>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return apps
            return apps.filter {
                it.label.contains(needle, ignoreCase = true) ||
                    it.packageName.contains(needle, ignoreCase = true)
            }
        }

    /** Selecting nothing in [SplitTunnelMode.ONLY_SELECTED] would route every other app around the VPN. */
    val warnEmptyAllowlist: Boolean
        get() = mode == SplitTunnelMode.ONLY_SELECTED && selectedPackages.isEmpty()
}
