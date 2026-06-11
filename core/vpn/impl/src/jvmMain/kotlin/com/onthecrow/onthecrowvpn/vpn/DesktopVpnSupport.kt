package com.onthecrow.onthecrowvpn.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

internal enum class DesktopOs { MACOS, WINDOWS, UNSUPPORTED }

internal object DesktopVpnSupport {
    private val json = Json { ignoreUnknownKeys = true }

    val os: DesktopOs by lazy {
        val name = System.getProperty("os.name")?.lowercase().orEmpty()
        when {
            name.contains("mac") || name.contains("darwin") -> DesktopOs.MACOS
            name.contains("win") -> DesktopOs.WINDOWS
            else -> DesktopOs.UNSUPPORTED
        }
    }

    /** e.g. macos-arm64, macos-x64, windows-x64 */
    val archSlug: String by lazy {
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        val a = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x64"
        }
        when (os) {
            DesktopOs.MACOS -> "macos-$a"
            DesktopOs.WINDOWS -> "windows-$a"
            DesktopOs.UNSUPPORTED -> "unknown-$a"
        }
    }

    private val sidecarName: String
        get() = if (os == DesktopOs.WINDOWS) "onthecrow-xray.exe" else "onthecrow-xray"

    private val wrapperName: String
        get() = if (os == DesktopOs.WINDOWS) "vpn-windows.ps1" else "vpn-macos.sh"

    /** Resolves the sidecar binary, trying packaged resources then dev fallbacks. */
    fun resolveSidecar(): File? = resolve(
        packagedName = sidecarName,
        devCandidates = listOf("local-libs/libxray-desktop/$archSlug/$sidecarName"),
    )

    /** Resolves the privileged wrapper script. */
    fun resolveWrapper(): File? = resolve(
        packagedName = wrapperName,
        devCandidates = listOf("scripts/desktop/$wrapperName"),
    )

    /** Resolves wintun.dll (Windows only) — must sit next to the sidecar exe. */
    fun resolveWintunDll(): File? = resolve(
        packagedName = "wintun.dll",
        devCandidates = listOf("local-libs/libxray-desktop/$archSlug/wintun.dll"),
    )

    /**
     * Resolves the native macOS NE bridge executable.
     *
     * Production: the bridge is embedded in the *running* app bundle at `Contents/Helpers/`. Its
     * enclosing main bundle (`OnthecrowVPN.app`) is what `OSSystemExtensionRequest` is attributed to,
     * and the system extension lives in the same bundle's `Contents/Library/SystemExtensions/`. The
     * whole `.app` is Developer-ID signed + notarized (scripts/package-macos-app.sh) with an embedded
     * provisioning profile, so the restricted NetworkExtension entitlement is honored **with SIP
     * enabled** — no separate `/Applications/OnthecrowVpnService.app` install needed.
     *
     * Dev fallback (SIP-off loop, scripts/build-macos-service-app.sh): a service `.app` built under the
     * repo, or the bare Kotlin/Native executable.
     */
    fun resolveBridge(): File? {
        appBundleContents()?.let { contents ->
            val embedded = File(contents, "Helpers/onthecrow-macos-bridge")
            if (embedded.exists()) return embedded
        }
        return resolve(
            packagedName = "Helpers/onthecrow-macos-bridge",
            devCandidates = listOf(
                "build/macos/OnthecrowVpnService.app/Contents/MacOS/onthecrow-macos-bridge",
                "core/vpn/macos-bridge/build/bin/macosArm64/releaseExecutable/onthecrow-macos-bridge.kexe",
            ),
        )
    }

    /**
     * `<app>/Contents` when running from a packaged macOS `.app`. Compose exposes the resources dir as
     * `<app>/Contents/app/resources`, so its grandparent is `Contents`. Returns null otherwise (dev run).
     */
    private fun appBundleContents(): File? {
        val resDir = System.getProperty("compose.application.resources.dir") ?: return null
        return File(resDir).parentFile?.parentFile?.takeIf { it.name == "Contents" }
    }

    private fun resolve(packagedName: String, devCandidates: List<String>): File? {
        // Packaged: Compose flattens the matching os-arch subdir into the resources
        // root, so files live there by their basename.
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            val f = File(dir, packagedName)
            if (f.exists()) return f
        }
        // Dev run (./gradlew :desktopApp:run): walk up from the working dir looking
        // for the repo root that holds these files.
        var base: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val root = base ?: return@repeat
            for (rel in devCandidates) {
                val f = File(root, rel)
                if (f.exists()) return f
            }
            base = root.parentFile
        }
        return null
    }

    /**
     * Extracts proxy server host(s) from the Xray JSON so they can be excluded
     * from the tunnel. Collects every `"address"` value anywhere in the config
     * (the tun inbound has none; the proxy outbound carries the real host),
     * dropping loopback / unspecified addresses.
     */
    fun extractServerHosts(xrayJson: String): List<String> {
        val root = runCatching { json.parseToJsonElement(xrayJson) }.getOrNull() ?: return emptyList()
        val hosts = LinkedHashSet<String>()
        collectAddresses(root, hosts)
        return hosts.filterNot { it.isBlank() || it == "127.0.0.1" || it == "0.0.0.0" || it == "::1" || it == "localhost" }
    }

    private fun collectAddresses(element: JsonElement, out: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "address" && value is JsonPrimitive && value.isString) {
                    out += value.content
                } else {
                    collectAddresses(value, out)
                }
            }
            is JsonArray -> element.forEach { collectAddresses(it, out) }
            else -> Unit
        }
    }
}
