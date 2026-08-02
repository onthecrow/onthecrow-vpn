package com.onthecrow.deltavpn.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Rasterised icons, kept for the process lifetime.
 *
 * Rows leave composition as the list scrolls, so without this every icon would be re-read from the
 * package manager and re-drawn each time it scrolls back into view. A few hundred small bitmaps is a
 * cheap trade for a list that does not stutter.
 */
private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

/** Comfortably above the ~40dp the row draws it at, even on xxxhdpi. */
private const val ICON_PX = 144

@Composable
internal actual fun AppIcon(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val icon by produceState(iconCache[packageName], packageName) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).rasterise(ICON_PX) }
                .getOrNull()
                ?.also { iconCache[packageName] = it }
        }
    }
    val bitmap = icon
    if (bitmap == null) {
        // Hold the space so the row doesn't jump when the icon lands.
        Box(modifier)
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    }
}

private fun Drawable.rasterise(sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, sizePx, sizePx)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
