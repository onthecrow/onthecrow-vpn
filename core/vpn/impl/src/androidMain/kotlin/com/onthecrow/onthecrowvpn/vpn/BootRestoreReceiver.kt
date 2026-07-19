package com.onthecrow.onthecrowvpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.onthecrow.onthecrowvpn.xray.OtcLog

/**
 * Brings the tunnel back after a reboot or an app update, if it was up when we lost it.
 *
 * Declared with `android:process=":vpn"` so it wakes the VPN process directly and never cold-starts the
 * main process — the tunnel does not need the UI, and pulling the whole app graph up would be slower
 * and more fragile.
 *
 * The persisted [ConnectionParams] are the authority: they exist only while a tunnel is meant to be
 * running, and a user disconnect or a fatal failure clears them. So "params exist" is exactly the
 * question "should we be connected right now?", and no separate flag is needed.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        AndroidVpnEnvironment.initialize(ctx)
        val saved = runCatching { ConnectionParamsStore(ctx).load() }.getOrNull()
        if (saved == null) {
            OtcLog.log(LOG_TAG, "$action — no persisted tunnel, standing down")
            return
        }
        OtcLog.log(LOG_TAG, "$action — restoring tunnel")
        // No payload: the service reads the persisted params itself, exactly as it does after a
        // recovery restart. Keeping one restore path means one thing to keep correct.
        val serviceIntent = Intent(ctx, OnthecrowVpnService::class.java)
            .setAction(OnthecrowVpnService.ACTION_CONNECT)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent)
            } else {
                ctx.startService(serviceIntent)
            }
        }.onFailure { OtcLog.log(LOG_TAG, "$action — restore FAILED: ${it.message}") }
    }

    private companion object {
        const val LOG_TAG = "BOOT"
    }
}
