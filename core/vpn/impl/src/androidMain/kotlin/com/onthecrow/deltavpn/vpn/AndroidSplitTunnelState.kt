package com.onthecrow.deltavpn.vpn

/**
 * Main-process holder of the resolved per-app routing, kept current by [SplitTunnelAndroidSync] and
 * read by [PlatformVpnController] when it builds the CONNECT intent. At most one of the two is
 * non-empty (Android can't mix disallow/allow).
 */
internal object AndroidSplitTunnelState {
    @Volatile
    var disallow: List<String> = emptyList()

    @Volatile
    var allow: List<String> = emptyList()
}
