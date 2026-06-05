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
        val proto = provider.protocolConfiguration as? NETunnelProviderProtocol
        val xrayJson = proto?.providerConfiguration?.get("xrayJson") as? String
        if (xrayJson.isNullOrBlank()) {
            log("startTunnel: missing config")
            completionHandler(makeError(1, "Missing VPN configuration"))
            return
        }
        val remote = proto.serverAddress ?: "127.0.0.1"
        log("startTunnel: remote=$remote configLen=${xrayJson.length}")

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
                completionHandler(settingsError)
                return@setTunnelNetworkSettings
            }
            val fd = findUtunFd()
            log("startTunnel: utun fd=$fd")
            if (fd < 0) {
                completionHandler(makeError(2, "Could not locate the tunnel descriptor"))
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
                completionHandler(makeError(3, error))
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
}
