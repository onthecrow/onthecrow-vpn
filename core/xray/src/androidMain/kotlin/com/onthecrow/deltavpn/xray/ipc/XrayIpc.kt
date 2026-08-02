package com.onthecrow.deltavpn.xray.ipc

import android.os.IBinder

/**
 * The wire between the app process (which owns the tun and the VpnService) and `:xray` (which owns
 * libXray and nothing else).
 *
 * Hand-rolled rather than generated from AIDL, because AIDL is not available here: these modules use
 * the `com.android.kotlin.multiplatform.library` plugin, whose `androidLibrary { }` DSL exposes no
 * `buildFeatures.aidl` and registers no aidl tasks (verified — a probe .aidl produced nothing). The
 * alternatives were a whole extra module on the legacy plugin just to hold one interface file, or
 * this. The call surface is small enough that this is the cheaper answer.
 *
 * Everything except the file descriptors travels as JSON in a single [TX_INVOKE] transaction, keyed by
 * a method name — deliberately the same shape libXray itself settled on. Typed Parcel marshalling
 * would mean hand-writing read/write pairs for every argument and result, which is exactly the kind of
 * code that fails silently when one side is edited and the other is not.
 */
internal object XrayIpc {
    /** Both sides must agree on this or `transact` throws SecurityException. */
    const val ENGINE_DESCRIPTOR = "com.onthecrow.deltavpn.xray.IXrayEngine"
    const val HOST_DESCRIPTOR = "com.onthecrow.deltavpn.xray.IXrayHost"

    /** app → `:xray`. Request JSON in, response JSON out, plus an optional descriptor. */
    const val TX_INVOKE = IBinder.FIRST_CALL_TRANSACTION

    /**
     * app → `:xray`, ONEWAY. The engine process kills itself.
     *
     * Must be oneway: a blocking call whose receiver dies before replying raises DeadObjectException
     * on the caller, so the very act of succeeding would look like a failure.
     */
    const val TX_SUICIDE = IBinder.FIRST_CALL_TRANSACTION + 1

    /**
     * `:xray` → app. Exclude a socket from the tunnel.
     *
     * Only used when the engine process cannot call `VpnService.protect` for itself; see
     * [com.onthecrow.deltavpn.xray.protect.SocketProtectors]. Synchronous by necessity — xray's
     * dialer blocks on the answer before it will use the socket.
     */
    const val TX_PROTECT = IBinder.FIRST_CALL_TRANSACTION

    // Methods carried inside the TX_INVOKE envelope.
    const val METHOD_VALIDATE = "validate"
    const val METHOD_START = "start"
    const val METHOD_STOP = "stop"
    const val METHOD_SET_TUN_FD = "setTunFd"
    const val METHOD_PING = "ping"
}
