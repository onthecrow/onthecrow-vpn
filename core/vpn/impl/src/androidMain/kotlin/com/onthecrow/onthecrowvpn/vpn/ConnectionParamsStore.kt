package com.onthecrow.onthecrowvpn.vpn

import android.content.Context
import com.onthecrow.onthecrowvpn.xray.OtcLog
import java.io.File

/**
 * Persists the last-good [ConnectionParams] to internal storage so the `:vpn` process can re-establish
 * the tunnel by itself after a hard crash / system (LMK) kill: on a START_STICKY restart the service
 * has only a null intent, so the config must come from here, not from the (gone) in-memory state.
 *
 * Internal `filesDir` (not external): app-private, shared by both the main and `:vpn` processes, and it
 * survives process death. Written on every user CONNECT, cleared on a deliberate DISCONNECT/REVOKE and
 * on a fatal failure (so we never crash-restore-crash in a loop).
 */
internal class ConnectionParamsStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    fun save(params: ConnectionParams) {
        runCatching { file.writeText(encodeConnectionParams(params)) }
            .onFailure { OtcLog.log(TAG, "params save failed: ${it.message}") }
    }

    fun load(): ConnectionParams? = runCatching {
        if (!file.exists()) null else decodeConnectionParams(file.readText())
    }.getOrElse {
        OtcLog.log(TAG, "params load failed: ${it.message}")
        null
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { OtcLog.log(TAG, "params clear failed: ${it.message}") }
    }

    private companion object {
        const val FILE_NAME = "vpn_connection_params"
        const val TAG = "STORE"
    }
}
