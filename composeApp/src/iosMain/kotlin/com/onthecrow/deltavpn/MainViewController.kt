package com.onthecrow.deltavpn

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    AppInitializer.initialize(IOSPlatform())
    App()
}
