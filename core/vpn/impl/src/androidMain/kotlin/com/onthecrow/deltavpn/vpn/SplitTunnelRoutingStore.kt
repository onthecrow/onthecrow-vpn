package com.onthecrow.deltavpn.vpn

import android.content.Context
import com.onthecrow.deltavpn.errorreporting.ErrorDomain
import com.onthecrow.deltavpn.errorreporting.ErrorReporter
import com.onthecrow.deltavpn.vpn.model.ResolvedRouting
import com.onthecrow.deltavpn.xray.OtcLog
import java.io.File

/**
 * The single source of truth for per-app routing, readable by the `:vpn` process.
 *
 * Written by [SplitTunnelAndroidSync] in the main process whenever the settings change, and read by
 * the service at the moment it builds the tun — never carried in an intent or in a persisted
 * snapshot. That matters because the service re-establishes the tunnel on paths the main process is
 * not part of (recovery restart, crash self-heal, boot restore), and a routing snapshot taken at
 * connect time would be resurrected long after the user had changed it.
 *
 * Reading it at establish time also removes a race: the settings write, the sync that resolves it and
 * the worker that restarts the tunnel are three independent observers of one flow, so ordering
 * between them was only ever guaranteed by the teardown taking longer than a field assignment.
 *
 * Internal `filesDir`: app-private, visible to both processes, survives process death. One writer and
 * one reader, so a plain file is enough — SharedPreferences and DataStore are both unsafe across
 * processes.
 */
internal class SplitTunnelRoutingStore(
    context: Context,
    // Nullable so callers without one (SplitTunnelAndroidSync) keep working; the service passes the
    // injected reporter.
    private val errorReporter: ErrorReporter? = null,
) {
    private val file = File(context.filesDir, FILE_NAME)

    fun save(routing: ResolvedRouting) {
        runCatching {
            // Two lines, disallow then allow, each comma-separated. At most one is ever populated.
            file.writeText(routing.disallow.joinToString(",") + "\n" + routing.allow.joinToString(","))
        }.onFailure {
            OtcLog.log(TAG, "routing save failed: ${it.message}")
            errorReporter?.report(ErrorDomain.DATASTORE, it)
        }
    }

    fun load(): ResolvedRouting? = runCatching {
        if (!file.exists()) return@runCatching null
        val lines = file.readText().split("\n")
        ResolvedRouting(
            disallow = lines.getOrNull(0).toPackageSet(),
            allow = lines.getOrNull(1).toPackageSet(),
        )
    }.getOrElse {
        OtcLog.log(TAG, "routing load failed: ${it.message}")
        errorReporter?.report(ErrorDomain.DATASTORE, it)
        null
    }

    private fun String?.toPackageSet(): Set<String> =
        this.orEmpty().split(",").filter { it.isNotBlank() }.toSet()

    private companion object {
        const val FILE_NAME = "vpn_split_tunnel_routing"
        const val TAG = "STORE"
    }
}
