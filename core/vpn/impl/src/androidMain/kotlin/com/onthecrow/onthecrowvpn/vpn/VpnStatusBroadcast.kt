package com.onthecrow.onthecrowvpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.onthecrow.onthecrowvpn.xray.OtcLog

/**
 * Carries [ConnectionStatus] from the VpnService (running in the ":vpn" process) to the controller in
 * the main process via an explicit, app-private broadcast. The in-process [AndroidVpnRuntime] StateFlow
 * is no longer shared across the process boundary, so the service publishes through this instead.
 *
 * Strictly one-way and best-effort: this is a UI mirror, nothing more. Recovery deliberately does NOT
 * travel over it — the main process is usually dead while a VPN runs in the background, a
 * runtime-registered receiver cannot start a process, and delivery to a merely-cached process was
 * measured 39-68s late. The `:vpn` process recovers itself.
 */
internal object VpnStatusBroadcast {
    private const val ACTION = "com.onthecrow.onthecrowvpn.vpn.STATUS"
    private const val EXTRA_CODE = "code"
    private const val EXTRA_MESSAGE = "message"

    private const val CODE_DISCONNECTED = 0
    private const val CODE_PREPARING = 1
    private const val CODE_CONNECTING = 2
    private const val CODE_CONNECTED = 3
    private const val CODE_DISCONNECTING = 4
    private const val CODE_ERROR = 5

    /** Publish a status from the service process. */
    fun send(context: Context, status: ConnectionStatus) {
        OtcLog.log("BCAST", "send status=$status")
        val (code, message) = encode(status)
        val intent = Intent(ACTION)
            .setPackage(context.packageName)
            .putExtra(EXTRA_CODE, code)
            .putExtra(EXTRA_MESSAGE, message)
        context.sendBroadcast(intent)
    }

    /** Register a main-process listener; returns the receiver (kept for the app's lifetime). */
    fun register(context: Context, onStatus: (ConnectionStatus) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION) return
                onStatus(decode(intent.getIntExtra(EXTRA_CODE, CODE_DISCONNECTED), intent.getStringExtra(EXTRA_MESSAGE)))
            }
        }
        registerNotExported(context, receiver, IntentFilter(ACTION))
        return receiver
    }

    private fun registerNotExported(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun encode(status: ConnectionStatus): Pair<Int, String?> = when (status) {
        ConnectionStatus.Disconnected -> CODE_DISCONNECTED to null
        ConnectionStatus.PreparingPermission -> CODE_PREPARING to null
        ConnectionStatus.Connecting -> CODE_CONNECTING to null
        ConnectionStatus.Connected -> CODE_CONNECTED to null
        ConnectionStatus.Disconnecting -> CODE_DISCONNECTING to null
        is ConnectionStatus.Error -> CODE_ERROR to status.message
    }

    private fun decode(code: Int, message: String?): ConnectionStatus = when (code) {
        CODE_PREPARING -> ConnectionStatus.PreparingPermission
        CODE_CONNECTING -> ConnectionStatus.Connecting
        CODE_CONNECTED -> ConnectionStatus.Connected
        CODE_DISCONNECTING -> ConnectionStatus.Disconnecting
        CODE_ERROR -> ConnectionStatus.Error(message ?: "VPN error")
        else -> ConnectionStatus.Disconnected
    }
}
