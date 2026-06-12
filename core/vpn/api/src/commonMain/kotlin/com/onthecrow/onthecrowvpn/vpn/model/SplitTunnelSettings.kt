package com.onthecrow.onthecrowvpn.vpn.model

import kotlinx.serialization.Serializable

/**
 * Per-app VPN routing mode. Extensible — only [excludePushServices] (a BYPASS of Google push) is
 * surfaced in the UI today; the full per-app picker will drive [mode] + [selectedPackages] later.
 */
@Serializable
enum class SplitTunnelMode {
    /** Everything goes through the tunnel (subject only to [SplitTunnelSettings.excludePushServices]). */
    OFF,

    /** Denylist: [SplitTunnelSettings.selectedPackages] bypass the VPN; everything else goes through. */
    BYPASS_SELECTED,

    /** Allowlist: ONLY [SplitTunnelSettings.selectedPackages] go through the VPN; everything else bypasses. */
    ONLY_SELECTED,
}

@Serializable
data class SplitTunnelSettings(
    val mode: SplitTunnelMode = SplitTunnelMode.OFF,
    val selectedPackages: Set<String> = emptySet(),
    /** Route Google Play Services (FCM) directly so push works under the VPN, incl. in Doze. Default on. */
    val excludePushServices: Boolean = true,
)

/**
 * Effective per-app routing for `VpnService.Builder`. Android can't mix both, so at most one of
 * [disallow] (addDisallowedApplication) / [allow] (addAllowedApplication) is non-empty.
 */
data class ResolvedRouting(
    val disallow: Set<String> = emptySet(),
    val allow: Set<String> = emptySet(),
)
