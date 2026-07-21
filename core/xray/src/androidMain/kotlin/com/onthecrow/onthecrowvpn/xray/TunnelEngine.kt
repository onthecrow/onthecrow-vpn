package com.onthecrow.onthecrowvpn.xray

import android.os.ParcelFileDescriptor
import com.onthecrow.onthecrowvpn.xray.ipc.RemoteXrayEngine
import com.onthecrow.onthecrowvpn.xray.ipc.XrayEngineHolder
import com.onthecrow.onthecrowvpn.xray.ipc.XrayHostBinder

/**
 * The engine as the tunnel owner needs it: hand it a descriptor and a config, and be able to throw the
 * whole thing away.
 *
 * Deliberately narrower than [XrayEngine]. That interface is shared with iOS and desktop and speaks in
 * descriptor NUMBERS, which mean nothing once the engine is in another process. This one speaks in
 * descriptors that can actually cross.
 */
interface TunnelEngine {
    /**
     * Start the engine on a dup of the tun. The descriptor is consumed — the caller's copy is closed
     * whether the start succeeds or not.
     */
    suspend fun startOnTun(tun: ParcelFileDescriptor, xrayJson: String): XrayRunResult

    suspend fun stop(): XrayRunResult

    /**
     * Throw the engine process away. Nothing is started in its place — the next [startOnTun] does
     * that, which is what makes this serve both recovery and teardown.
     *
     * @return true if it died cleanly on request, false if it had to be killed outright. Either way
     *   the engine is gone; the flag is diagnostic, not a failure.
     */
    suspend fun killEngineProcess(): Boolean

    /**
     * Drop the tunnel owner's hooks from the process-wide engine.
     *
     * Required on teardown, not optional: the engine outlives the service, and the `protect` callback
     * it holds captures the service instance. Leaving it attached leaks a destroyed VpnService and
     * lets an engine that is still dialling call `protect` on it.
     */
    fun release()
}

/**
 * Build an engine that runs in the `:xray` process.
 *
 * [protect] is how that process excludes its own outbound sockets from the tunnel when it cannot do so
 * itself — see [com.onthecrow.onthecrowvpn.xray.protect.ChoosingSocketProtector]. It is called from a
 * binder thread and must not block on anything the caller might hold.
 *
 * [onUnexpectedDeath] fires when the engine process dies without being asked — a crash, or the
 * low-memory killer. Also delivered on a binder thread. A death caused by [TunnelEngine.restart] does
 * not fire it.
 */
fun createTunnelEngine(
    protect: (Int) -> Boolean,
    onUnexpectedDeath: () -> Unit = {},
): TunnelEngine = RemoteTunnelEngine(
    XrayEngineHolder.engine.also { it.attachTunnelOwner(XrayHostBinder(protect), onUnexpectedDeath) },
)

/**
 * Wraps the shared engine proxy in the narrower contract the tunnel owner needs. Note this is a VIEW of
 * the process-wide instance, not an engine of its own — the config screens hold the same object.
 */
private class RemoteTunnelEngine(private val engine: RemoteXrayEngine) : TunnelEngine {
    override suspend fun startOnTun(tun: ParcelFileDescriptor, xrayJson: String): XrayRunResult =
        engine.startOnTun(tun, xrayJson)

    override suspend fun stop(): XrayRunResult = engine.stop()

    override suspend fun killEngineProcess(): Boolean = engine.killEngineProcess()

    override fun release() = engine.detachTunnelOwner()
}
