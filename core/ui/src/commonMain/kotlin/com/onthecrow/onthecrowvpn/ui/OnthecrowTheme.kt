package com.onthecrow.onthecrowvpn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6C7A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBEEAF2),
    onPrimaryContainer = Color(0xFF002026),
    secondary = Color(0xFF5C6258),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7D8),
    onSecondaryContainer = Color(0xFF191D16),
    tertiary = Color(0xFF765A2B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA8),
    onTertiaryContainer = Color(0xFF2A1800),
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF171D20),
    surface = Color(0xFFFAFBFC),
    onSurface = Color(0xFF171D20),
    surfaceVariant = Color(0xFFDDE4E7),
    onSurfaceVariant = Color(0xFF41484B),
    outline = Color(0xFF71787B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2D0DA),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5B),
    onPrimaryContainer = Color(0xFFBEEAF2),
    secondary = Color(0xFFC4CBBE),
    onSecondary = Color(0xFF2E332B),
    secondaryContainer = Color(0xFF454A41),
    onSecondaryContainer = Color(0xFFE0E7D8),
    tertiary = Color(0xFFE4C18D),
    onTertiary = Color(0xFF432C05),
    tertiaryContainer = Color(0xFF5C4217),
    onTertiaryContainer = Color(0xFFFFDEA8),
    background = Color(0xFF101517),
    onBackground = Color(0xFFE0E3E5),
    surface = Color(0xFF101517),
    onSurface = Color(0xFFE0E3E5),
    surfaceVariant = Color(0xFF41484B),
    onSurfaceVariant = Color(0xFFC1C8CB),
    outline = Color(0xFF8B9295),
)

val ConnectedGreen = Color(0xFF2E7D57)
val DisconnectedGray = Color(0xFF697174)

@Composable
fun OnthecrowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
