package com.onthecrow.deltavpn.connection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Material Symbols used by the connection screen, hand-built from the provided 24dp SVGs so we don't
 * pull in material-icons-extended. The source SVGs use the `0 -960 960 960` viewBox (y in [-960, 0]),
 * so each path is shifted down by 960 to fit ImageVector's [0, 960] viewport. The fill is irrelevant —
 * Icon() applies its own tint.
 */
internal object MaterialSymbols {
    val Add: ImageVector by lazy {
        symbol(
            "Add",
            "M440-440H240q-17 0-28.5-11.5T200-480q0-17 11.5-28.5T240-520h200v-200q0-17 11.5-28.5T480-760" +
                "q17 0 28.5 11.5T520-720v200h200q17 0 28.5 11.5T760-480q0 17-11.5 28.5T720-440H520v200" +
                "q0 17-11.5 28.5T480-200q-17 0-28.5-11.5T440-240v-200Z",
        )
    }

    val MoreVert: ImageVector by lazy {
        symbol(
            "MoreVert",
            "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5" +
                "T480-160Zm0-240q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480" +
                "q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5" +
                "T560-720q0 33-23.5 56.5T480-640Z",
        )
    }

    val ArrowDropDown: ImageVector by lazy {
        symbol(
            "ArrowDropDown",
            "M459-381 314-526q-3-3-4.5-6.5T308-540q0-8 5.5-14t14.5-6h304q9 0 14.5 6t5.5 14q0 2-6 14L501-381" +
                "q-5 5-10 7t-11 2q-6 0-11-2t-10-7Z",
        )
    }

    val ArrowDropUp: ImageVector by lazy {
        symbol(
            "ArrowDropUp",
            "M328-400q-9 0-14.5-6t-5.5-14q0-2 6-14l145-145q5-5 10-7t11-2q6 0 11 2t10 7l145 145q3 3 4.5 6.5" +
                "t1.5 7.5q0 8-5.5 14t-14.5 6H328Z",
        )
    }

    val Settings: ImageVector by lazy {
        symbol(
            "Settings",
            "m370-80-16-128q-13-5-24.5-12T307-235l-119 50L78-375l103-78q-1-7-1-13.5v-27q0-6.5 1-13.5L78-585" +
                "l110-190 119 50q11-8 23-15t24-12l16-128h220l16 128q13 5 24.5 12t22.5 15l119-50 110 190-103 78" +
                "q1 7 1 13.5v27q0 6.5-2 13.5l103 78-110 190-118-50q-11 8-23 15t-24 12L590-80H370Zm112-260" +
                "q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Z",
        )
    }

    val Autorenew: ImageVector by lazy {
        symbol(
            "Autorenew",
            "M240-478q0 16 2 31.5t7 30.5q5 17-1 32.5T227-361q-16 8-31.5 1.5T175-383q-8-23-11.5-47t-3.5-48" +
                "q0-134 93-228t227-94h7l-36-36q-11-11-11-28t11-28q11-11 28-11t28 11l104 104q12 12 12 28t-12 28" +
                "L507-628q-11 11-28 11t-28-11q-11-11-11-28t11-28l36-36h-7q-100 0-170 70.5T240-478Zm480-4" +
                "q0-16-2-31.5t-7-30.5q-5-17 1-32.5t21-22.5q16-8 31.5-1.5T785-577q8 23 11.5 47t3.5 48" +
                "q0 134-93 228t-227 94h-7l36 36q11 11 11 28t-11 28q-11 11-28 11t-28-11L349-172q-12-12-12-28" +
                "t12-28l104-104q11-11 28-11t28 11q11 11 11 28t-11 28l-36 36h7q100 0 170-70.5T720-482Z",
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
