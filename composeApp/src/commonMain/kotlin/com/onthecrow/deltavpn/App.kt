package com.onthecrow.deltavpn

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.onthecrow.deltavpn.navigation.NavigationProvider
import com.onthecrow.deltavpn.ui.OnthecrowTheme
import org.koin.compose.getKoin

@Composable
@Preview
fun App() {
    val darkTheme = isSystemInDarkTheme()
//    AppSystemBarsEffect(darkTheme)
    OnthecrowTheme(darkTheme = darkTheme) {
        val navigationProvider = getKoin().get<NavigationProvider>()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            navigationProvider.Navigation(modifier = Modifier.fillMaxSize())
        }
    }
}
