package com.onthecrow.deltavpn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The single accent colour of the app — also the cyan Nimbus layer glowing around the connect button
 * while the tunnel is up. Declared BEFORE the schemes below: top-level vals initialise in file order,
 * so referencing it from them requires it to come first.
 */
val Accent = Color(0xFF4DD0E1)

private val LightColors = lightColorScheme(
    primary = Accent,
    // Dark ink on the bright accent — white would be unreadable on it.
    onPrimary = Color(0xFF00363F),
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
    primary = Accent,
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
    // Elevated dark surfaces tinted to match the app background (Material defaults are neutral purple
    // and clash with the teal-dark background). Tiers: background < element < card.
    surfaceContainerLowest = Color(0xFF0B0F11),
    surfaceContainerLow = Color(0xFF161D20),
    surfaceContainer = Color(0xFF1B2326),
    surfaceContainerHigh = Color(0xFF263033),
    surfaceContainerHighest = Color(0xFF313C40),
    outlineVariant = Color(0xFF3B4448),
)

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
