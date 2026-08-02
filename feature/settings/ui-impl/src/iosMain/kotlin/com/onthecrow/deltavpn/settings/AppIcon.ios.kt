package com.onthecrow.deltavpn.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Split tunneling is Android-only for now, so there is never a list to draw icons for here. Still
// occupies the slot, per the expect declaration's contract.
@Composable
internal actual fun AppIcon(packageName: String, modifier: Modifier) {
    Box(modifier)
}
