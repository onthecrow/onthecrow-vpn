package com.onthecrow.deltavpn.xray.protect

import android.net.VpnService
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.onthecrow.deltavpn.xray.OtcLog
import com.onthecrow.deltavpn.xray.ipc.XrayIpc
import java.util.concurrent.atomic.AtomicInteger

private const val LOG_TAG = "PROTECT"

/**
 * Protect a socket from the process that hosts the engine, without asking anyone.
 *
 * `VpnService.protect(int)` is, on every API level we support (34, 35, 36 — all checked in the
 * platform sources), a one-line delegation to the static `NetworkUtilsInternal.protectFromVpn(int)`.
 * It reads no field of the service, touches no Context, and the method's own documentation scopes
 * failure to "the application is not prepared or is revoked" — an app-level condition, not a
 * per-process or per-instance one. So an instance that was never started by the framework is enough to
 * reach it, and the engine process can protect its own sockets with no IPC at all.
 *
 * What that reading CANNOT establish is what netd does with the request, since it only ever receives a
 * descriptor. The inference is that it keys on the calling UID, which both our processes share. That
 * is why this is a strategy and not the only implementation: see [ChoosingSocketProtector].
 */
internal class LocalSocketProtector : SocketProtector {
    // Never started by the framework, so it has no Context. Safe only because protect() ignores it —
    // do not call anything else on this instance.
    private val vpnService = object : VpnService() {}

    override fun protect(fd: Int): Boolean = runCatching { vpnService.protect(fd) }.getOrElse {
        OtcLog.log(LOG_TAG, "local protect threw: ${it.javaClass.simpleName}: ${it.message}")
        false
    }
}

/**
 * Hand the descriptor to the process that owns the VpnService and let it protect it.
 *
 * The fallback for if [LocalSocketProtector] turns out not to work on some device. Costs a synchronous
 * binder round trip per outbound socket; the field logs put that at 12-35 sockets per hour in steady
 * state and 35 in the busiest single second, so even the burst is a rounding error against a
 * sub-millisecond transaction.
 */
internal class RemoteSocketProtector(private val host: IBinder) : SocketProtector {
    override fun protect(fd: Int): Boolean = runCatching {
        // dup, NOT adopt: adoptFd takes ownership and would close the live socket out from under xray
        // the moment this wrapper is closed.
        ParcelFileDescriptor.fromFd(fd).use { pfd ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(XrayIpc.HOST_DESCRIPTOR)
                pfd.writeToParcel(data, 0)
                host.transact(XrayIpc.TX_PROTECT, data, reply, 0)
                reply.readException()
                reply.readInt() == 1
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }.getOrElse {
        OtcLog.log(LOG_TAG, "remote protect threw: ${it.javaClass.simpleName}: ${it.message}")
        false
    }
}

/**
 * Try to protect locally; fall back to the host process, once, for the life of the process.
 *
 * The verdict is latched on the FIRST socket rather than re-decided per call. Re-deciding would make
 * the failure mode depend on which socket happened to fail, and a socket that is not protected is not
 * a slow socket — it is traffic leaving through the tunnel it was supposed to bypass, which loops.
 *
 * Deliberately NOT a compile-time or config-time choice: whether the local path works is a property of
 * the device's netd, and the honest way to find out is to try it and watch.
 */
/**
 * A socket that was NOT protected is not a slow socket — xray's own upstream then routes back into the
 * tun it is supposed to bypass and loops, which from the outside is indistinguishable from "the network
 * is not answering". So a failure is worth saying out loud, every time it changes.
 *
 * Reported rather than counted silently because of a field case this could not be told apart from: a
 * path change where every dial failed for 35 seconds. With the result unreported there was no way to
 * decide whether the network was not ready or we had handed xray an unprotected socket.
 */
internal class ChoosingSocketProtector(
    private val local: SocketProtector,
    /**
     * Resolved on use, not at construction. The host binder arrives with the first call FROM the app
     * process, which is necessarily after this object is built in `onCreate` — capturing it eagerly
     * would pin it to null and silently disable the fallback.
     */
    private val remoteProvider: () -> SocketProtector?,
) : SocketProtector {
    @Volatile
    private var decided: SocketProtector? = null

    private val failures = AtomicInteger()
    private val successes = AtomicInteger()

    /**
     * Log transitions, not every call: steady state is thousands of successes, and the interesting
     * events are the first failure and the recovery after one.
     */
    private fun report(ok: Boolean): Boolean {
        if (ok) {
            val hadFailures = failures.getAndSet(0)
            if (hadFailures > 0) {
                OtcLog.log(LOG_TAG, "protect recovered after $hadFailures consecutive failures")
            }
            successes.incrementAndGet()
        } else {
            val n = failures.incrementAndGet()
            // Every one of the first few, then sparsely — a persistent failure is one condition, not
            // one per socket.
            if (n <= 3 || n % 25 == 0) {
                OtcLog.log(LOG_TAG, "protect FAILED (consecutive=$n) — this socket will loop back into the tun")
            }
        }
        return ok
    }

    override fun protect(fd: Int): Boolean {
        decided?.let { return report(it.protect(fd)) }
        synchronized(this) {
            decided?.let { return report(it.protect(fd)) }
            if (local.protect(fd)) {
                OtcLog.log(LOG_TAG, "cross-process protect works locally — no IPC on the dial path")
                decided = local
                return true
            }
            val remote = remoteProvider()
            if (remote == null) {
                OtcLog.log(LOG_TAG, "local protect failed and no host binder is available — socket UNPROTECTED")
                // NOT latched: without a host binder there was no fallback to test, so this says
                // nothing about which path works. Deciding here would make a startup ordering accident
                // permanent for the life of the process.
                return false
            }
            val viaHost = remote.protect(fd)
            OtcLog.log(LOG_TAG, "local protect failed; falling back to the host process (ok=$viaHost)")
            // Latched even on failure: if neither path works, retrying the local one per socket only
            // doubles the cost of a situation that is already broken, and the log above says so once.
            decided = remote
            return viaHost
        }
    }
}
