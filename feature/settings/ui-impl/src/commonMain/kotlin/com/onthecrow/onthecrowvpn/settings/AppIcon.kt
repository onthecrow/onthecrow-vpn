package com.onthecrow.onthecrowvpn.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws an installed app's launcher icon.
 *
 * Platform-specific because an app icon is a platform drawable — keeping it out of the shared model
 * lets [com.onthecrow.onthecrowvpn.vpn.model.InstalledApp] stay pure Kotlin. Implementations must
 * always occupy [modifier]'s space, even while the icon is still loading or missing, or rows reflow
 * mid-scroll.
 */
@Composable
internal expect fun AppIcon(packageName: String, modifier: Modifier)
