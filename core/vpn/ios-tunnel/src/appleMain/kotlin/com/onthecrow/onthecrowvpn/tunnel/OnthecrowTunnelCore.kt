package com.onthecrow.onthecrowvpn.tunnel

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import libxray.LibXrayRunXrayFromJSON
import libxray.LibXraySetTunFd
import libxray.LibXrayStopXray
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.numberWithInt
import platform.NetworkExtension.NEDNSSettings
import platform.NetworkExtension.NEIPv4Route
import platform.NetworkExtension.NEIPv4Settings
import platform.NetworkExtension.NEPacketTunnelNetworkSettings
import platform.NetworkExtension.NEPacketTunnelProvider
import platform.NetworkExtension.NEProviderStopReason
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.posix.getsockopt
import platform.posix.setenv
import platform.posix.socklen_tVar
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The full Packet Tunnel logic, written in Kotlin/Native as a *plain* class (not an
 * Obj-C subclass) so it can be imported and used from the tiny Swift principal class.
 *
 * Why a helper instead of subclassing NEPacketTunnelProvider directly in Kotlin:
 * Kotlin/Native registers subclasses of imported Obj-C classes only when the KN runtime
 * initializes — they are NOT in the static `__objc_classlist`. But NetworkExtension looks
 * up `NSExtensionPrincipalClass` via `NSClassFromString` at extension-process launch,
 * before any Kotlin code runs, so a KN-subclass principal class is never found and the
 * profile won't activate. A Swift principal class (registered at image load) that forwards
 * to this Kotlin helper sidesteps that entirely while keeping all logic in Kotlin.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class OnthecrowTunnelCore(
    private val provider: NEPacketTunnelProvider,
    private val logger: (String) -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun startTunnel(
        options: Map<Any?, *>?,
        completionHandler: (NSError?) -> Unit,
    ) {
        log("startTunnel: begin")
        // Clear any error from a previous attempt so the app only ever reads a fresh one.
        clearSharedError()
        val proto = provider.protocolConfiguration as? NETunnelProviderProtocol
        val xrayJson = proto?.providerConfiguration?.get("xrayJson") as? String
        if (xrayJson.isNullOrBlank()) {
            log("startTunnel: missing config")
            completionHandler(fail(1, "Missing VPN configuration"))
            return
        }
        // tunnelRemoteAddress is informational for a packet tunnel (routing is via includedRoutes),
        // but NetworkExtension rejects malformed values. Use the server host only if it's a clean
        // host/IP token, otherwise fall back to a placeholder so the tunnel can still come up.
        val remote = validTunnelRemote(proto.serverAddress)
        log("startTunnel: serverAddress=${proto.serverAddress} remote=$remote configLen=${xrayJson.length}")

        val settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress = remote)
        val ipv4 = NEIPv4Settings(addresses = listOf("10.77.0.2"), subnetMasks = listOf("255.255.255.255"))
        ipv4.includedRoutes = listOf(NEIPv4Route.defaultRoute())
        settings.IPv4Settings = ipv4
        settings.DNSSettings = NEDNSSettings(servers = listOf("1.1.1.1", "8.8.8.8"))
        settings.MTU = NSNumber.numberWithInt(1500)

        log("startTunnel: applying network settings")
        provider.setTunnelNetworkSettings(settings) { settingsError ->
            if (settingsError != null) {
                log("startTunnel: settings error: ${settingsError.localizedDescription}")
                reportFailure(settingsError.localizedDescription ?: "Failed to apply tunnel network settings")
                completionHandler(settingsError)
                return@setTunnelNetworkSettings
            }
            val fd = findUtunFd()
            log("startTunnel: utun fd=$fd")
            if (fd < 0) {
                completionHandler(fail(2, "Could not locate the tunnel descriptor (utun)"))
                return@setTunnelNetworkSettings
            }
            LibXraySetTunFd(fd)
            setenv("xray.tun.fd", fd.toString(), 1)

            val request = buildJsonObject {
                put("datDir", NSTemporaryDirectory())
                put("configJSON", xrayJson)
            }.toString()
            log("startTunnel: calling RunXrayFromJSON")
            val response = LibXrayRunXrayFromJSON(Base64.Default.encode(request.encodeToByteArray()))
            val error = libXrayError(response)
            if (error != null) {
                log("startTunnel: xray error: $error")
                completionHandler(fail(3, "Xray core failed to start: $error"))
            } else {
                log("startTunnel: connected")
                completionHandler(null)
            }
        }
    }

    fun stopTunnel(
        reason: NEProviderStopReason,
        completionHandler: () -> Unit,
    ) {
        log("stopTunnel: reason=$reason")
        LibXrayStopXray()
        completionHandler()
    }

    /**
     * NEPacketTunnelNetworkSettings.tunnelRemoteAddress requires an IP literal — a hostname (e.g.
     * "onthecrow.tech") is rejected ("Invalid ... tunnelRemoteAddress"). It is only informational
     * for a packet tunnel (routing is via includedRoutes; the extension's own sockets bypass the
     * tunnel automatically), so when the server is a domain we use a placeholder IP. Xray-core
     * still connects to the real host from the config (with its own DNS/SNI).
     */
    private fun validTunnelRemote(raw: String?): String {
        val v = raw?.trim().orEmpty()
        return if (isIpLiteral(v)) v else PLACEHOLDER_REMOTE
    }

    private fun isIpLiteral(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains(':')) {
            // IPv6: hex groups and colons only.
            return s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
        }
        // IPv4: four 0..255 octets.
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.all(Char::isDigit) && (p.toIntOrNull() ?: -1) in 0..255
        }
    }

    private fun log(message: String) {
        // Route through the Swift-provided logger: Kotlin/Native cannot safely call the
        // variadic NSLog(format, ...) — doing so crashes on the first arg marshal.
        logger(message)
    }

    /** Scans file descriptors for the utun control socket the OS handed this extension. */
    private fun findUtunFd(): Int {
        memScoped {
            val nameLen = 16
            val buf = allocArray<ByteVar>(nameLen)
            val len = alloc<socklen_tVar>()
            for (fd in 0..1024) {
                len.value = nameLen.toUInt()
                // SYSPROTO_CONTROL = 2, UTUN_OPT_IFNAME = 2
                if (getsockopt(fd, 2, 2, buf, len.ptr) == 0) {
                    val name = buf.toKString()
                    if (name.startsWith("utun")) return fd
                }
            }
        }
        return -1
    }

    /** Returns the error from a libXray base64 CallResponse, or null on success. */
    private fun libXrayError(base64Response: String): String? {
        val decoded = runCatching { Base64.Default.decode(base64Response).decodeToString() }.getOrNull()
            ?: return "Malformed libXray response"
        val root = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            ?: return "Malformed libXray response"
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false
        return if (success) null else (root["error"]?.jsonPrimitive?.contentOrNull ?: "Xray failed to start")
    }

    private fun makeError(code: Int, message: String): NSError =
        NSError.errorWithDomain("com.onthecrow.onthecrowvpn.tunnel", code.toLong(), mapOf("NSLocalizedDescription" to message))

    /** Record [message] in the shared App Group store (for the app to surface) and build the NSError. */
    private fun fail(code: Int, message: String): NSError {
        reportFailure(message)
        return makeError(code, message)
    }

    /**
     * Persist a human-readable failure reason where the main app can read it. Uses the App Group
     * shared UserDefaults; if the App Group capability isn't enabled this is a harmless no-op and
     * the app falls back to a generic message.
     */
    private fun reportFailure(message: String) {
        runCatching { NSUserDefaults(suiteName = APP_GROUP_ID)?.setObject(message, forKey = ERROR_KEY) }
    }

    private fun clearSharedError() {
        runCatching { NSUserDefaults(suiteName = APP_GROUP_ID)?.removeObjectForKey(ERROR_KEY) }
    }

    private companion object {
        private const val APP_GROUP_ID = "group.com.onthecrow.onthecrowvpn"
        private const val ERROR_KEY = "lastTunnelError"
        // RFC 5737 TEST-NET-1 — a valid, never-routed IP used purely as informational metadata.
        private const val PLACEHOLDER_REMOTE = "192.0.2.1"
    }
}
