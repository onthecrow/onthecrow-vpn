package com.onthecrow.deltavpn.xray.ipc

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.onthecrow.deltavpn.xray.OtcLog

private const val LOG_TAG = "XRAYHOST"

/**
 * The tunnel owner's end of the wire: what `:xray` calls back into when it needs something only the
 * process holding the VpnService can do.
 *
 * Today that is exactly one thing — excluding a socket from the tunnel — and only when the engine
 * process turns out not to be able to do it itself. On a device where the local path works this binder
 * is handed over and never called.
 */
internal class XrayHostBinder(
    private val protect: (Int) -> Boolean,
    private val onProtectResult: (Boolean) -> Unit = {},
) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        when (code) {
            XrayIpc.TX_PROTECT -> {
                data.enforceInterface(XrayIpc.HOST_DESCRIPTOR)
                val ok = protectFromParcel(data)
                onProtectResult(ok)
                reply?.writeNoException()
                reply?.writeInt(if (ok) 1 else 0)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }

    private fun protectFromParcel(data: Parcel): Boolean = runCatching {
        // The kernel dup'd the engine's socket into this process when the transaction crossed. It is a
        // distinct descriptor pointing at the same socket, so protecting it protects the engine's — the
        // mark lives on the socket, not on the descriptor. Closed here regardless: the engine keeps its
        // own, and leaving this one open would leak a descriptor per dial in the process that also
        // owns the tun.
        ParcelFileDescriptor.CREATOR.createFromParcel(data).use { pfd -> protect(pfd.fd) }
    }.getOrElse {
        OtcLog.log(LOG_TAG, "host-side protect threw: ${it.javaClass.simpleName}: ${it.message}")
        false
    }
}
