package com.onthecrow.onthecrowvpn.macosbridge

import com.onthecrow.onthecrowvpn.vpn.AppleTunnelManager
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.read
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.run
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusReasserting
import platform.SystemExtensions.OSSystemExtensionManager
import platform.SystemExtensions.OSSystemExtensionProperties
import platform.SystemExtensions.OSSystemExtensionReplacementAction
import platform.SystemExtensions.OSSystemExtensionRequest
import platform.SystemExtensions.OSSystemExtensionRequestDelegateProtocol
import platform.SystemExtensions.OSSystemExtensionRequestResult
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.fflush
import platform.posix.getenv
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.system.exitProcess

/**
 * Native macOS helper, embedded (signed) inside the .app bundle and spawned by the JVM desktop app.
 *
 * It is the JVM↔NetworkExtension bridge: the JVM can't call the Obj-C `NETunnelProviderManager` /
 * `OSSystemExtensionRequest` APIs, so this tiny Kotlin/Native process does, reusing the same
 * [AppleTunnelManager] the iOS app uses. Communication is line-based over stdio:
 *
 *   stdin  (commands):   activate | deactivate | connect <base64-xrayJson> | disconnect | revoke
 *   stdout (events, JSON): {"type":"status","value":...} | {"type":"error","message":...}
 *                          {"type":"sysext","state":...[,"message":...]} | {"type":"log",...}
 *
 * The process runs the main run loop forever (NE + sysext callbacks need it) and exits when stdin
 * closes (the JVM died / asked us to stop), tearing the tunnel down on the way out.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
fun main() {
    val bundleId = getenv("ONTHECROW_SYSEXT_ID")?.toKString()?.takeIf { it.isNotBlank() }
        ?: DEFAULT_SYSEXT_ID

    val tunnel = AppleTunnelManager(providerBundleId = bundleId)
    val activator = SysextActivator(bundleId, ::emit)
    val scope = CoroutineScope(Dispatchers.Main)

    // Status poller — reads the real OS status and emits it on change (same single-source-of-truth
    // model as iOS). A best-effort shared-error read accompanies a drop so the JVM can show why.
    scope.launch {
        tunnel.loadExisting()
        var last: String? = null
        while (isActive) {
            val current = tunnel.currentStatus()?.let(::mapStatus) ?: STATUS_DISCONNECTED
            if (current != last) {
                last = current
                emitStatus(current)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    // Commands are read off the main thread (blocking stdin) and funneled through a channel so the
    // main run loop — where the NE APIs must be called — processes them one at a time, in order.
    val commands = Channel<String>(Channel.UNLIMITED)
    scope.launch {
        for (command in commands) handle(command, tunnel, activator)
    }
    scope.launch(Dispatchers.Default) {
        pumpStdin { line ->
            val command = line.trim()
            if (command.isNotEmpty()) commands.trySend(command)
        }
        // stdin closed → the JVM is gone or asked us to stop. Tear down and exit.
        commands.close()
        withContext(Dispatchers.Main) { tunnel.disconnect() }
        exitProcess(0)
    }

    NSRunLoop.mainRunLoop.run()
}

/**
 * Read stdin and invoke [onLine] for each newline-delimited line. Kotlin/Native's `readLine()`
 * returned the whole buffered chunk as one "line" here, so we split on '\n' ourselves over raw
 * bytes (the protocol is ASCII — command keywords + base64 — so per-chunk decoding is safe).
 */
@OptIn(ExperimentalForeignApi::class)
private fun pumpStdin(onLine: (String) -> Unit) {
    val chunk = ByteArray(4096)
    var acc = ByteArray(0)
    while (true) {
        val n = chunk.usePinned { read(0, it.addressOf(0), chunk.size.convert()) }
        if (n <= 0L) break // EOF or error
        acc += chunk.copyOfRange(0, n.toInt())
        while (true) {
            val nl = acc.indexOf('\n'.code.toByte())
            if (nl < 0) break
            onLine(acc.copyOfRange(0, nl).decodeToString())
            acc = acc.copyOfRange(nl + 1, acc.size)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private suspend fun handle(command: String, tunnel: AppleTunnelManager, activator: SysextActivator) {
    when {
        command == "activate" -> activator.activate()
        command == "deactivate" -> activator.deactivate()
        command == "disconnect" -> tunnel.disconnect()
        command == "revoke" -> {
            // Remote revocation: stop AND remove the system profile so it leaves System Settings.
            tunnel.removeProfile()
            emitStatus(STATUS_DISCONNECTED)
        }
        command.startsWith("connect ") -> {
            val payload = command.removePrefix("connect ").trim()
            val xrayJson = runCatching { Base64.decode(payload).decodeToString() }.getOrNull()
            if (xrayJson.isNullOrBlank()) {
                emitError("Malformed connect payload")
                return
            }
            tunnel.clearSharedError()
            val logPath = xrayErrorLogPath()
            emitLog("connect: configuring NETunnelProviderManager… (xray log: ${logPath ?: "n/a"})")
            val failure = runCatching {
                tunnel.connect(xrayJson, onWarning = { emitLog(it) }, debugLogPath = logPath)
            }.getOrElse { it.message ?: "Failed to start VPN" }
            if (failure != null) emitError(failure) else emitLog("connect: startVPNTunnel requested")
        }
        else -> emitLog("unknown command: $command")
    }
}

// ---- System-extension activation (OSSystemExtensionRequest) ----

@OptIn(ExperimentalForeignApi::class)
private class SysextActivator(
    private val bundleId: String,
    private val emit: (JsonObject) -> Unit,
) : NSObject(), OSSystemExtensionRequestDelegateProtocol {

    fun activate() {
        val request = OSSystemExtensionRequest.activationRequestForExtension(bundleId, dispatch_get_main_queue())
        request.delegate = this
        OSSystemExtensionManager.sharedManager.submitRequest(request)
        emitState("submitted", null)
    }

    fun deactivate() {
        val request = OSSystemExtensionRequest.deactivationRequestForExtension(bundleId, dispatch_get_main_queue())
        request.delegate = this
        OSSystemExtensionManager.sharedManager.submitRequest(request)
        emitState("deactivating", null)
    }

    override fun requestNeedsUserApproval(request: OSSystemExtensionRequest) =
        emitState("needs_approval", null)

    override fun request(request: OSSystemExtensionRequest, didFinishWithResult: OSSystemExtensionRequestResult) =
        emitState("activated", null)

    override fun request(request: OSSystemExtensionRequest, didFailWithError: NSError) =
        emitState("failed", didFailWithError.localizedDescription)

    override fun request(
        request: OSSystemExtensionRequest,
        actionForReplacingExtension: OSSystemExtensionProperties,
        withExtension: OSSystemExtensionProperties,
    ): OSSystemExtensionReplacementAction =
        OSSystemExtensionReplacementAction.OSSystemExtensionReplacementActionReplace

    private fun emitState(state: String, message: String?) {
        emit(
            buildJsonObject {
                put("type", "sysext")
                put("state", state)
                if (message != null) put("message", message)
            },
        )
    }
}

// ---- stdout protocol ----

@OptIn(ExperimentalForeignApi::class)
private fun emit(obj: JsonObject) {
    println(obj.toString())
    fflush(null) // pipes are block-buffered; flush so the JVM sees each event immediately
}

private fun emitStatus(value: String) = emit(buildJsonObject { put("type", "status"); put("value", value) })
private fun emitError(message: String) = emit(buildJsonObject { put("type", "error"); put("message", message) })
private fun emitLog(message: String) = emit(buildJsonObject { put("type", "log"); put("message", message) })

private fun mapStatus(status: NEVPNStatus): String = when (status) {
    NEVPNStatusConnected -> STATUS_CONNECTED
    NEVPNStatusConnecting, NEVPNStatusReasserting -> STATUS_CONNECTING
    NEVPNStatusDisconnecting -> STATUS_DISCONNECTING
    else -> STATUS_DISCONNECTED
}

/**
 * Path to xray's error log inside the shared App Group container (readable by app + extension).
 *
 * Temporarily disabled: pointing xray's `log.error` at the App Group container made the sandboxed
 * extension fail to start xray. Re-enable (return the computed path) once the extension can write
 * there — for now we keep the runtime config at warning level so the tunnel reaches `connected`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun xrayErrorLogPath(): String? {
    @Suppress("UNUSED_VARIABLE")
    val container = NSFileManager.defaultManager
        .containerURLForSecurityApplicationGroupIdentifier("group.com.onthecrow.onthecrowvpn")
    return null
}

private const val POLL_INTERVAL_MS = 500L
private const val STATUS_CONNECTED = "connected"
private const val STATUS_CONNECTING = "connecting"
private const val STATUS_DISCONNECTING = "disconnecting"
private const val STATUS_DISCONNECTED = "disconnected"

// Child of the desktop app bundle id (desktopApp packageName = "com.onthecrow.onthecrowvpn"), as the
// system requires for OSSystemExtensionRequest. Overridable via the ONTHECROW_SYSEXT_ID env var the
// JVM controller may pass when spawning this helper.
private const val DEFAULT_SYSEXT_ID = "com.onthecrow.onthecrowvpn.SystemExtension"
