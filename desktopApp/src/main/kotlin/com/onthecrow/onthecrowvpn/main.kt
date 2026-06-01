package com.onthecrow.onthecrowvpn

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    AppInitializer.initialize(JvmPlatform())
    Window(
        onCloseRequest = ::exitApplication,
        title = "OnthecrowVPN",
    ) {
        App()
    }
}
