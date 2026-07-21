package com.onthecrow.onthecrowvpn.xray

import com.onthecrow.onthecrowvpn.xray.protect.SocketProtector

/**
 * The single seam between xray's dialer callback and whatever can actually exclude a socket from the
 * tunnel.
 *
 * A process-global object because that is what the callback can reach: it arrives from Go, through
 * JNI, on a thread we did not create, holding no reference to anything of ours. Whoever is able to
 * protect sockets in this process installs itself here.
 *
 * Which implementation that is depends on where this object lives — and after the process split there
 * are two answers. In the app process it is the VpnService itself. In `:xray` it is
 * [com.onthecrow.onthecrowvpn.xray.protect.ChoosingSocketProtector], which works out at runtime
 * whether the engine process can protect its own sockets or has to hand each one to the host.
 */
object AndroidVpnSocketProtector {
    @Volatile
    private var protector: SocketProtector? = null

    internal fun install(protector: SocketProtector?) {
        this.protector = protector
    }

    /** Convenience for the VpnService, which already has the method and only needs to hand it over. */
    fun setProtector(protect: ((Int) -> Boolean)?) {
        protector = protect?.let { fn ->
            object : SocketProtector {
                override fun protect(fd: Int): Boolean = fn(fd)
            }
        }
    }

    /**
     * Returns false when nothing is installed, which is the honest answer and the one that matters: a
     * socket reported as protected when it is not sends xray's own upstream traffic back into the tun,
     * where it loops — while the app goes on showing Connected.
     */
    fun protect(fd: Int): Boolean = protector?.protect(fd) ?: false
}
