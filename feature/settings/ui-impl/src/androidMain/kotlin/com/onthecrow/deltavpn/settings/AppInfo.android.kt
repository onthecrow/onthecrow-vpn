package com.onthecrow.deltavpn.settings

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.onthecrow.deltavpn.xray.OtcLog

@Composable
internal actual fun appVersionLabel(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("")
    }
}

@Composable
internal actual fun rememberLogSharer(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        {
            runCatching {
                // Flush first, and block briefly. The whole point of sharing the log is that it is
                // complete: without this the last lines — usually the interesting ones — would still
                // be sitting in the writer's queue.
                OtcLog.flushBlocking()
                val file = OtcLog.logFile(context)
                if (!file.exists()) file.createNewFile()
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "DeltaVPN diagnostics")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(share, "Share diagnostics")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

@Composable
internal actual fun rememberUrlOpener(): ((String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            Unit
        }
    }
}
