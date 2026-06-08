package com.onthecrow.onthecrowvpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/**
 * macOS system-VPN controller (NetworkExtension), used by [PlatformVpnController] on macOS instead of
 * the osascript/utun sidecar. It owns a long-lived native bridge process (`:core:vpn:macos-bridge`,
 * embedded in the .app) and talks to it over stdio:
 *
 *   - we write commands to the bridge's stdin (`activate`, `connect <base64-xrayJson>`, `disconnect`),
 *   - the bridge streams JSON events on stdout which we translate into [ConnectionStatus] via [emit].
 *
 * The bridge is the single source of truth: it polls the real `NEVPNStatus`, so [emit] reflects the
 * system (including external toggles from System Settings). Raw xray JSON is forwarded verbatim — the
 * bridge (AppleTunnelManager) adds the tun inbound itself, matching iOS.
 */
internal class MacosNeController(
    private val emit: (ConnectionStatus) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var process: Process? = null

    /** Spawn the bridge (if needed) purely to observe the current system status at launch. */
    @Synchronized
    fun start() {
        runCatching { ensureBridge() }
    }

    @Synchronized
    fun connect(xrayJson: String): ConnectResult {
        return try {
            ensureBridge()
            // NOTE: do NOT activate the system extension here. Activation (OSSystemExtensionRequest)
            // is a one-time install step; re-issuing it per connect — especially while a tunnel is up
            // (profile switch) — fails with OSSystemExtensionErrorDomain error 4 and stalls the start.
            // The extension is installed once (see ensureActivated); connect only drives the tunnel.
            val payload = Base64.getEncoder().encodeToString(xrayJson.encodeToByteArray())
            send("connect $payload")
            emit(ConnectionStatus.Connecting)
            ConnectResult.Started
        } catch (t: Throwable) {
            val message = t.message ?: "Failed to start macOS VPN"
            emit(ConnectionStatus.Error(message))
            ConnectResult.Failed(message)
        }
    }

    /**
     * One-time install of the system extension (OSSystemExtensionRequest). Call this from a first-run
     * / "Install VPN helper" flow while disconnected — never per connect. No-op-safe if already
     * installed. (On this dev machine the extension is already activated, so connect works without it.)
     */
    @Synchronized
    fun ensureActivated() {
        runCatching { ensureBridge(); send("activate") }
    }

    @Synchronized
    fun disconnect() {
        runCatching { send("disconnect") }
        emit(ConnectionStatus.Disconnecting)
    }

    @Synchronized
    fun shutdown() {
        runCatching { process?.outputStream?.close() }
        runCatching { process?.destroy() }
        process = null
    }

    private fun ensureBridge() {
        process?.takeIf { it.isAlive }?.let { return }
        val bridge: File = DesktopVpnSupport.resolveBridge()
            ?: error("macOS VPN helper not found — build :core:vpn:macos-bridge")
        val proc = ProcessBuilder(bridge.absolutePath)
            .redirectErrorStream(false)
            .start()
        process = proc
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { onEvent(it) }
            }
            // The bridge exited (crash or our shutdown) — without it there is no live tunnel manager.
            emit(ConnectionStatus.Disconnected)
        }.apply { isDaemon = true; name = "macos-ne-bridge-reader"; start() }
    }

    private fun send(command: String) {
        val proc = process ?: error("bridge is not running")
        val stdin = proc.outputStream
        stdin.write((command + "\n").toByteArray())
        stdin.flush()
    }

    private fun onEvent(line: String) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
        when (str("type")) {
            "status" -> when (str("value")) {
                "connected" -> emit(ConnectionStatus.Connected)
                "connecting" -> emit(ConnectionStatus.Connecting)
                "disconnecting" -> emit(ConnectionStatus.Disconnecting)
                "disconnected" -> emit(ConnectionStatus.Disconnected)
            }
            "error" -> emit(ConnectionStatus.Error(str("message") ?: "VPN failed to start"))
            "sysext" -> when (str("state")) {
                "needs_approval" -> emit(
                    ConnectionStatus.Error(
                        "Approve the OnthecrowVPN system extension in System Settings → General → " +
                            "Login Items & Extensions, then connect again.",
                    ),
                )
                "failed" -> emit(
                    ConnectionStatus.Error(str("message") ?: "System extension activation failed"),
                )
                // "submitted" / "deactivating" / "activated" are informational — no status change.
            }
            // "log" — diagnostic only.
        }
    }
}
