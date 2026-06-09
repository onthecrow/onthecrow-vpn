package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop VPN controller.
 *
 *  - **macOS**: a real NetworkExtension system VPN (registered in System Settings), driven through
 *    the native [MacosNeController] bridge. No admin password, no utun sidecar.
 *  - **Windows**: the elevated PowerShell + Wintun sidecar (unchanged).
 */
actual class PlatformVpnController : VpnController {
    private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = mutableStatus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sanitizer = XrayConfigSanitizer()
    private val mutex = Mutex()

    private val macos = MacosNeController { status -> mutableStatus.value = status }

    private var session: Session? = null

    private class Session(
        val process: Process,
        val workDir: File,
        val output: StringBuilder,
        var watchJob: Job? = null,
    )

    init {
        // On macOS, start observing the real system VPN status immediately (the profile may already
        // be connected from a previous app run). Windows has no equivalent reconcile.
        if (DesktopVpnSupport.os == DesktopOs.MACOS) {
            macos.start()
        }
    }

    override suspend fun connect(xrayJson: String): ConnectResult = mutex.withLock {
        when (DesktopVpnSupport.os) {
            DesktopOs.UNSUPPORTED -> failNow("VPN is not supported on this OS yet")
            // Raw config — the bridge (AppleTunnelManager) injects the tun inbound itself, like iOS.
            DesktopOs.MACOS -> macos.connect(xrayJson)
            DesktopOs.WINDOWS -> connectWindows(xrayJson)
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        if (DesktopVpnSupport.os == DesktopOs.MACOS) {
            macos.disconnect()
        } else {
            mutableStatus.value = ConnectionStatus.Disconnecting
            stopSessionLocked()
            mutableStatus.value = ConnectionStatus.Disconnected
        }
    }

    override suspend fun revoke() = mutex.withLock {
        if (DesktopVpnSupport.os == DesktopOs.MACOS) {
            // Stop AND remove the system VPN profile so it disappears from System Settings.
            macos.revoke()
        } else {
            // Windows/Linux: no persisted system profile — stopping the sidecar is the full teardown.
            mutableStatus.value = ConnectionStatus.Disconnecting
            stopSessionLocked()
            mutableStatus.value = ConnectionStatus.Disconnected
        }
    }

    // ---- Windows (UAC + PowerShell wrapper, Wintun) ----

    private suspend fun connectWindows(xrayJson: String): ConnectResult {
        val sidecar = DesktopVpnSupport.resolveSidecar()
            ?: return failNow("VPN engine not found. Run scripts/build-libxray-desktop.sh")
        val wrapper = DesktopVpnSupport.resolveWrapper()
            ?: return failNow("VPN helper script not found")

        stopSessionLocked()
        mutableStatus.value = ConnectionStatus.Connecting

        return runCatching {
            val tunName = "OnthecrowVPN"
            val runtimeJson = sanitizer.withTunInbound(xrayJson, name = tunName, mtu = 1500, logLevel = "warning")
            val serverHost = DesktopVpnSupport.extractServerHosts(runtimeJson).firstOrNull()
                ?: error("No proxy server address found in config")

            // Fresh session dir in temp (clean on Windows). Binaries/wrappers are copied here so the
            // elevated process runs them from a location it can read.
            val workDir = File(System.getProperty("java.io.tmpdir"), "onthecrowvpn-vpn")
            workDir.deleteRecursively()
            workDir.mkdirs()
            File(workDir, "dat").mkdirs()
            val configFile = File(workDir, "config.json").apply { writeText(runtimeJson) }
            val runFile = File(workDir, "run.json").apply {
                writeText(buildRunJson(tunName, File(workDir, "dat"), configFile))
            }

            println("[OnthecrowVPN] VPN session dir: ${workDir.absolutePath}")
            println("[OnthecrowVPN] os=${DesktopVpnSupport.os} server=$serverHost tun=$tunName")

            val output = StringBuilder()
            val process = launchWindows(workDir, sidecar, wrapper, runFile, tunName, serverHost, output)
            session = Session(process, workDir, output).also {
                it.watchJob = watchSession(process, workDir, output)
            }
            ConnectResult.Started
        }.getOrElse { error ->
            val message = error.message ?: "Failed to start VPN"
            mutableStatus.value = ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    private fun failNow(message: String): ConnectResult {
        mutableStatus.value = ConnectionStatus.Error(message)
        return ConnectResult.Failed(message)
    }

    /**
     * Windows only: this is a TUN full-tunnel VPN and does not use (or need) a system proxy. If one is
     * configured — e.g. left enabled by another proxy-based VPN like Happ (127.0.0.1:10809) — proxy-aware
     * apps (browsers) talk to that proxy instead of routing through the tunnel, so traffic bypasses us and
     * breaks outright if the proxy is a dead loopback one. We don't touch the user's settings (could be an
     * intentional/corporate proxy), we just advise. Returns null on macOS / when no proxy is enabled.
     */
    override fun connectNotice(): String? {
        if (DesktopVpnSupport.os != DesktopOs.WINDOWS) return null
        return runCatching {
            val proc = ProcessBuilder(
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitForCompat(2_000)
            val enabled = Regex("""ProxyEnable\s+REG_DWORD\s+0x([0-9a-fA-F]+)""").find(out)
                ?.groupValues?.get(1)?.toIntOrNull(16) == 1
            if (!enabled) return null
            val server = Regex("""ProxyServer\s+REG_SZ\s+(.+)""").find(out)?.groupValues?.get(1)?.trim()
            "A system proxy is enabled" + (server?.let { " ($it)" } ?: "") +
                " - your browser may bypass the VPN. Disable it (Settings > Network > Proxy) for full-tunnel routing."
        }.getOrNull()
    }

    private suspend fun stopSessionLocked() {
        val current = session ?: return
        session = null
        // Stop watching before we tear down, so the poller can't observe the process exit
        // and clobber the status with a spurious Error after an intentional disconnect.
        current.watchJob?.cancel()
        withContext(Dispatchers.IO) {
            runCatching { File(current.workDir, "stop").writeText("stop") }
            val ended = current.process.waitForCompat(5_000)
            if (!ended) runCatching { current.process.destroy() }
            // The sidecar runs elevated and is killed by the wrapper's Cleanup (via the stop sentinel),
            // not by us. Its onthecrow-xray.exe / wintun.dll stay locked for a moment after it dies, so
            // wait until the exe is releasable before the next connect wipes & re-copies the work dir —
            // otherwise switching configs fails with "failed to delete it" and leaves the dir half-written.
            waitForSidecarReleased(current.workDir, 8_000)
            // Keep the dir for inspection; it is wiped at the next connect.
        }
    }

    private fun watchSession(process: Process, workDir: File, output: StringBuilder): Job {
        return scope.launch {
            val ready = File(workDir, "ready")
            val errorFile = File(workDir, "error")
            while (true) {
                when {
                    errorFile.exists() -> {
                        val reason = errorFile.readText().trim().ifBlank { "VPN failed to start" }
                        dumpDiagnostics(workDir, output)
                        mutableStatus.value = ConnectionStatus.Error(reason)
                        return@launch
                    }
                    ready.exists() -> {
                        if (mutableStatus.value !is ConnectionStatus.Connected) {
                            mutableStatus.value = ConnectionStatus.Connected
                        }
                    }
                    !process.isAlive -> {
                        dumpDiagnostics(workDir, output)
                        if (mutableStatus.value is ConnectionStatus.Connected) {
                            mutableStatus.value = ConnectionStatus.Disconnected
                        } else {
                            val tail = File(workDir, "sidecar.log").takeIf { it.exists() }
                                ?.readText()?.trim()?.lines()?.lastOrNull { it.isNotBlank() }
                            mutableStatus.value = ConnectionStatus.Error(
                                tail?.let { "VPN engine exited: $it" } ?: "VPN engine exited unexpectedly",
                            )
                        }
                        return@launch
                    }
                }
                delay(300)
            }
        }
    }

    private fun dumpDiagnostics(workDir: File, output: StringBuilder) {
        runCatching {
            println("[OnthecrowVPN] ---- launcher output ----")
            println(output.toString().ifBlank { "(empty)" })
            // Wrapper steps (route exclusion, adapter wait) log here; the sidecar can't
            // share this file, so it's where Windows-side failures surface.
            val wrapperLog = File(workDir, "wrapper.log")
            if (wrapperLog.exists()) {
                println("[OnthecrowVPN] ---- wrapper.log (${wrapperLog.absolutePath}) ----")
                println(wrapperLog.readText())
            }
            val log = File(workDir, "sidecar.log")
            println("[OnthecrowVPN] ---- sidecar.log (${log.absolutePath}) ----")
            println(if (log.exists()) log.readText() else "(no log file)")
            println("[OnthecrowVPN] ---------------------------------")
        }
    }

    private fun launchWindows(
        workDir: File,
        sidecar: File,
        wrapper: File,
        runFile: File,
        tunName: String,
        serverHost: String,
        output: StringBuilder,
    ): Process {
        val wintun = DesktopVpnSupport.resolveWintunDll()
            ?: error("wintun.dll not found — download from wintun.net into local-libs/libxray-desktop/windows-x64/")
        val localSidecar = File(workDir, "onthecrow-xray.exe").also { copyResilient(sidecar, it) }
        // wintun.dll MUST sit next to the sidecar exe.
        copyResilient(wintun, File(workDir, "wintun.dll"))
        val localWrapper = File(workDir, "vpn-windows.ps1").also { copyResilient(wrapper, it) }

        fun ps(s: String) = "'" + s.replace("'", "''") + "'"
        val launcher = File(workDir, "launch.ps1").apply {
            writeText(
                buildString {
                    append("& ")
                    append(ps(localWrapper.absolutePath)); append(' ')
                    append(ps(localSidecar.absolutePath)); append(' ')
                    append(ps(runFile.absolutePath)); append(' ')
                    append(ps(tunName)); append(' ')
                    append(ps(serverHost)); append(' ')
                    append(ps(workDir.absolutePath))
                    appendLine()
                },
            )
        }
        // Outer (non-elevated) PowerShell triggers UAC and -Wait keeps it alive for the
        // whole session, so process liveness tracks the tunnel (like osascript on macOS).
        val inner =
            "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden " +
                "-ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','${launcher.absolutePath}')"
        return startAndDrain(
            ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", inner),
            output,
        )
    }

    private fun startAndDrain(builder: ProcessBuilder, output: StringBuilder): Process {
        val process = builder.redirectErrorStream(true).start()
        Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; start() }
        return process
    }

    private fun buildRunJson(tunName: String, datDir: File, configFile: File): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            {
              "tunName": "${esc(tunName)}",
              "tunPriority": 20,
              "dns": "1.1.1.1",
              "datDir": "${esc(datDir.absolutePath)}",
              "configPath": "${esc(configFile.absolutePath)}"
            }
        """.trimIndent()
    }

    private fun Process.waitForCompat(timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        return !isAlive
    }

    /**
     * Blocks until the old sidecar binary in [workDir] is no longer locked (the elevated
     * onthecrow-xray.exe holds it open until it fully exits), so the next connect can wipe and
     * re-copy the work dir cleanly. `delete()` succeeds only once the handle is released.
     */
    private fun waitForSidecarReleased(workDir: File, timeoutMillis: Long) {
        val exe = File(workDir, "onthecrow-xray.exe")
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!exe.exists() || exe.delete()) return
            Thread.sleep(150)
        }
    }

    /**
     * Copies [src] over [dst], tolerating a transient Windows file lock from a just-stopped sidecar.
     * The binaries never change between sessions, so if the destination still can't be overwritten
     * but an identical copy is already present, reuse it instead of failing the whole connect.
     */
    private fun copyResilient(src: File, dst: File) {
        repeat(10) {
            if (runCatching { src.copyTo(dst, overwrite = true) }.isSuccess) return
            if (dst.exists() && dst.length() == src.length()) return
            Thread.sleep(150)
        }
        if (!(dst.exists() && dst.length() == src.length())) src.copyTo(dst, overwrite = true)
    }
}
