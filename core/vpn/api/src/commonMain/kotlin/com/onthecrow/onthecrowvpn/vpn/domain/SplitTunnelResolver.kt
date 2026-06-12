package com.onthecrow.onthecrowvpn.vpn.domain

import com.onthecrow.onthecrowvpn.vpn.model.ResolvedRouting
import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelMode
import com.onthecrow.onthecrowvpn.vpn.model.SplitTunnelSettings

/** Pure mapping of [SplitTunnelSettings] to the effective `VpnService.Builder` per-app routing. */
object SplitTunnelResolver {
    /**
     * Google Play Services (FCM) + Google Services Framework (historic FCM channel). Bypassing these
     * lets push arrive under the VPN even in Doze (the tunnel's upstream is dead there).
     */
    val PUSH_PACKAGES = setOf("com.google.android.gms", "com.google.android.gsf")

    fun resolve(settings: SplitTunnelSettings, selfPackage: String): ResolvedRouting {
        val push = if (settings.excludePushServices) PUSH_PACKAGES else emptySet()
        return when (settings.mode) {
            SplitTunnelMode.OFF ->
                ResolvedRouting(disallow = push)

            SplitTunnelMode.BYPASS_SELECTED ->
                ResolvedRouting(disallow = settings.selectedPackages + push)

            // Allowlist: only listed apps tunnel. Our own package MUST tunnel (the health probe runs in
            // our :vpn process); push must bypass, so it's removed from the allow set even if listed.
            SplitTunnelMode.ONLY_SELECTED ->
                ResolvedRouting(allow = (settings.selectedPackages + selfPackage) - push)
        }
    }
}
