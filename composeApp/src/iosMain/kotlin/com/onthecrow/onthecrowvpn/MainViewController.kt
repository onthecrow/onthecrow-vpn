package com.onthecrow.onthecrowvpn

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController {
    AppInitializer.initialize(IOSPlatform())
    App()
}
