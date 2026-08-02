package com.onthecrow.deltavpn

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    AppInitializer.initialize(JvmPlatform())
    Window(
        onCloseRequest = ::exitApplication,
        title = "DeltaVPN",
    ) {
        App()
    }
}
