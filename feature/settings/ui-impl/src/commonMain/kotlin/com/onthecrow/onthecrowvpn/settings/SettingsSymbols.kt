package com.onthecrow.onthecrowvpn.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Material Symbols used by the settings screens, hand-built from the 24dp SVGs so we don't pull in
 * material-icons-extended. Same convention as the connection screen's set: the source SVGs use the
 * `0 -960 960 960` viewBox (y in [-960, 0]), so each path is shifted down by 960 to fit
 * ImageVector's [0, 960] viewport. The fill is irrelevant — Icon() applies its own tint.
 */
internal object SettingsSymbols {
    val Search: ImageVector by lazy {
        symbol(
            "Search",
            "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580" +
                "q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38" +
                "t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580" +
                "q0 75 52.5 127.5T380-400Z",
        )
    }

    val Close: ImageVector by lazy {
        symbol(
            "Close",
            "M480-424 284-228q-11 11-28 11t-28-11q-11-11-11-28t11-28l196-196-196-196q-11-11-11-28t11-28" +
                "q11-11 28-11t28 11l196 196 196-196q11-11 28-11t28 11q11 11 11 28t-11 28L536-480l196 196" +
                "q11 11 11 28t-11 28q-11 11-28 11t-28-11L480-424Z",
        )
    }

    val ChevronRight: ImageVector by lazy {
        symbol(
            "ChevronRight",
            "M504-480 320-664q-11-11-11-28t11-28q11-11 28-11t28 11l212 212q6 6 8.5 13t2.5 15q0 8-2.5 15" +
                "t-8.5 13L376-240q-11 11-28 11t-28-11q-11-11-11-28t11-28l184-184Z",
        )
    }

    private fun symbol(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f,
        ).apply {
            addGroup(translationY = 960f)
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.White))
            clearGroup()
        }.build()
}
