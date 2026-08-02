package com.onthecrow.deltavpn.vpn.domain

import com.onthecrow.deltavpn.vpn.model.ResolvedRouting
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode
import com.onthecrow.deltavpn.vpn.model.SplitTunnelSettings

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
            SplitTunnelMode.ONLY_SELECTED -> {
                val chosen = settings.selectedPackages - push
                if (chosen.isEmpty()) {
                    // An allowlist of just ourselves is the worst possible outcome: every user app
                    // would leave the VPN while the tunnel still establishes and the health probe
                    // still passes (our own traffic IS tunnelled), so the app would report Connected
                    // while protecting nothing. With nothing chosen there is no allowlist to honour,
                    // so fail closed and tunnel everything, exactly as OFF does.
                    ResolvedRouting(disallow = push)
                } else {
                    ResolvedRouting(allow = chosen + selfPackage)
                }
            }
        }
    }
}
