package com.onthecrow.onthecrowvpn.xray.protect

/**
 * Exclude one socket from the tunnel.
 *
 * xray dials its upstream from inside the VPN's own UID, so without this its packets are routed back
 * into the tun and loop forever. Every outbound socket goes through here, on xray's own (Go/JNI)
 * threads, before it is used.
 *
 * Implementations MUST NOT throw: an exception here crosses the JNI boundary back into Go and can take
 * the process down. They must also not block on anything the caller might already hold — the engine
 * calls this while `start()` is still in flight, which on the app side runs under the operation mutex.
 */
internal interface SocketProtector {
    /** @return true if the socket is now excluded from the tunnel. */
    fun protect(fd: Int): Boolean
}
