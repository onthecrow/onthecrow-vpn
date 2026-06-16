package com.onthecrow.onthecrowvpn.vpn.log

import kotlin.concurrent.Volatile

/**
 * TEMP (diagnosis): a thin, platform-agnostic logging facade so common-code components (the VPN sync
 * worker, the connection ViewModel) can feed the same diagnostic log as the platform layers, without
 * those modules taking an Android dependency. The platform wires [setSink] at process start — on
 * Android to the unified file logger (`OtcLog`); elsewhere it stays null (no-op).
 *
 * Remove together with `OtcLog` once Doze/network recovery is confirmed fixed.
 */
object DebugLog {
    @Volatile
    private var sink: ((tag: String, message: String) -> Unit)? = null

    fun setSink(sink: ((tag: String, message: String) -> Unit)?) {
        this.sink = sink
    }

    fun log(tag: String, message: String) {
        sink?.invoke(tag, message)
    }
}
