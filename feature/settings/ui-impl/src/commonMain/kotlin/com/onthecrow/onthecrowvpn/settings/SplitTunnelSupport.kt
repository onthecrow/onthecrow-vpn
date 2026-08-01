package com.onthecrow.onthecrowvpn.settings

/**
 * Whether per-app routing exists on this platform.
 *
 * Android's `VpnService.Builder` is what makes it possible; nothing equivalent is wired up elsewhere,
 * and the installed-apps provider returns nothing there. Without this the settings row would open a
 * screen offering an empty list with a live mode selector — a control that appears to work, persists
 * a setting, and changes nothing.
 */
internal expect val splitTunnelSupported: Boolean

/**
 * Whether the recovery ladder this setting tunes exists on this platform.
 *
 * Only Android runs it (`OnthecrowVpnService`); iOS/macOS hand the tunnel to the Network Extension and
 * desktop to the sidecar, neither of which reads the preference. Hidden rather than shown-and-inert, for
 * the same reason as [splitTunnelSupported].
 */
internal expect val aggressiveKeepaliveSupported: Boolean
