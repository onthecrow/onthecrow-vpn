package com.onthecrow.onthecrowvpn.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface NavigationProvider {
    @Composable
    fun Navigation(modifier: Modifier)
}
