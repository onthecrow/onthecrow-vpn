package com.onthecrow.deltavpn

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Pinned to phone proportions because that is the only shape the UI was designed for: every screen is
 * the shared mobile layout, `fillMaxSize` with a bottom bar and no desktop breakpoints, so a window the
 * user can stretch just spreads a one-column layout across a shape nothing accounts for.
 *
 * The height stays under 800 on purpose — a 13" laptop is 1440x900 logical points, and a taller window
 * would not fit once the menu bar and the dock have taken their share.
 */
private val WindowSize = DpSize(width = 420.dp, height = 780.dp)

fun main() = application {
    AppInitializer.initialize(JvmPlatform())
    Window(
        onCloseRequest = ::exitApplication,
        title = "Delta VPN",
        // Locks the size on every desktop OS: AWT refuses both the drag handles and the
        // maximise/zoom button once this is false.
        resizable = false,
        state = rememberWindowState(
            // This is the OUTER window, so the platform title bar comes out of it — the content area is
            // roughly 28dp shorter on macOS and 32dp on Windows.
            size = WindowSize,
            // Centred rather than cascaded: a window that can never be resized should at least open
            // where the user is looking.
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        App()
    }
}
