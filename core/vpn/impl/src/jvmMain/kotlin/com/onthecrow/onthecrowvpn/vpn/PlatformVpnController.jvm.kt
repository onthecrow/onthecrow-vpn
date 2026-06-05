package com.onthecrow.onthecrowvpn.vpn

import com.onthecrow.onthecrowvpn.xray.XrayConfigSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

actual class PlatformVpnController : VpnController {
    private val mutableStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = mutableStatus

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sanitizer = XrayConfigSanitizer()
    private val mutex = Mutex()

    private var session: Session? = null

    private class Session(
        val process: Process,
        val workDir: File,
        val output: StringBuilder,
    )

    init {
        // Reconcile at launch with the real system state. The elevated helper from a previous
        // run may still be alive (its root process can outlive the app) and watching the stop
        // sentinel. Signal it to tear down so the app and the OS agree on a clean Disconnected
        // state instead of leaving an invisible, unmanageable tunnel up.
        runCatching {
            val workDir = File(System.getProperty("java.io.tmpdir"), "onthecrowvpn-vpn")
            val ready = File(workDir, "ready")
            val stop = File(workDir, "stop")
            if (ready.exists() && !stop.exists()) {
                stop.writeText("stop")
            }
        }
    }

    override suspend fun connect(xrayJson: String): ConnectResult = mutex.withLock {
        if (DesktopVpnSupport.os == DesktopOs.UNSUPPORTED) {
            return@withLock failNow("VPN is not supported on this OS yet")
        }
        val sidecar = DesktopVpnSupport.resolveSidecar()
            ?: return@withLock failNow("VPN engine not found. Run scripts/build-libxray-desktop.sh")
        val wrapper = DesktopVpnSupport.resolveWrapper()
            ?: return@withLock failNow("VPN helper script not found")

        stopSessionLocked()
        mutableStatus.value = ConnectionStatus.Connecting

        return@withLock runCatching {
            val tunName = if (DesktopVpnSupport.os == DesktopOs.WINDOWS) "OnthecrowVPN" else "utun9"
            val runtimeJson = sanitizer.withTunInbound(xrayJson, name = tunName, mtu = 1500, logLevel = "warning")
            val serverHost = DesktopVpnSupport.extractServerHosts(runtimeJson).firstOrNull()
                ?: error("No proxy server address found in config")

            // Fresh session dir in temp (NOT TCC-protected on macOS; clean on Windows).
            // Binaries/wrappers are copied here so the elevated process runs them from a
            // location it can read (macOS TCC blocks root access to ~/Documents).
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
            val process = when (DesktopVpnSupport.os) {
                DesktopOs.WINDOWS -> launchWindows(workDir, sidecar, wrapper, runFile, tunName, serverHost, output)
                else -> launchMacos(workDir, sidecar, wrapper, runFile, tunName, serverHost, output)
            }
            session = Session(process, workDir, output)
            watchSession(process, workDir, output)
            ConnectResult.Started
        }.getOrElse { error ->
            val message = error.message ?: "Failed to start VPN"
            mutableStatus.value = ConnectionStatus.Error(message)
            ConnectResult.Failed(message)
        }
    }

    override suspend fun disconnect() = mutex.withLock {
        mutableStatus.value = ConnectionStatus.Disconnecting
        stopSessionLocked()
        mutableStatus.value = ConnectionStatus.Disconnected
    }

    private fun failNow(message: String): ConnectResult {
        mutableStatus.value = ConnectionStatus.Error(message)
        return ConnectResult.Failed(message)
    }

    private suspend fun stopSessionLocked() {
        val current = session ?: return
        session = null
        withContext(Dispatchers.IO) {
            runCatching { File(current.workDir, "stop").writeText("stop") }
            val ended = current.process.waitForCompat(5_000)
            if (!ended) runCatching { current.process.destroy() }
            // Keep the dir for inspection; it is wiped at the next connect.
        }
    }

    private fun watchSession(process: Process, workDir: File, output: StringBuilder) {
        scope.launch {
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
            val log = File(workDir, "sidecar.log")
            println("[OnthecrowVPN] ---- sidecar.log (${log.absolutePath}) ----")
            println(if (log.exists()) log.readText() else "(no log file)")
            println("[OnthecrowVPN] ---------------------------------")
        }
    }

    // ---- macOS (osascript + bash wrapper, utun) ----

    private fun launchMacos(
        workDir: File,
        sidecar: File,
        wrapper: File,
        runFile: File,
        tunName: String,
        serverHost: String,
        output: StringBuilder,
    ): Process {
        val localSidecar = File(workDir, "onthecrow-xray").also {
            sidecar.copyTo(it, overwrite = true); it.setExecutable(true)
        }
        val localWrapper = File(workDir, "vpn-macos.sh").also {
            wrapper.copyTo(it, overwrite = true); it.setExecutable(true)
        }
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val launcher = File(workDir, "launch.sh").apply {
            writeText(
                buildString {
                    appendLine("#!/bin/bash")
                    // Elevated cwd inherits ~/Documents which root can't access (TCC).
                    appendLine("cd ${q(workDir.absolutePath)} || cd /")
                    append("exec /bin/bash ")
                    append(q(localWrapper.absolutePath)); append(' ')
                    append(q(localSidecar.absolutePath)); append(' ')
                    append(q(runFile.absolutePath)); append(' ')
                    append(q(tunName)); append(' ')
                    append(q(serverHost)); append(' ')
                    append(q(workDir.absolutePath))
                    appendLine()
                },
            )
            setExecutable(true)
        }
        val appleScript = "do shell script \"/bin/bash '${launcher.absolutePath}'\" with administrator privileges"
        return startAndDrain(ProcessBuilder("osascript", "-e", appleScript), output)
    }

    // ---- Windows (UAC + PowerShell wrapper, Wintun) ----

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
        val localSidecar = File(workDir, "onthecrow-xray.exe").also { sidecar.copyTo(it, overwrite = true) }
        // wintun.dll MUST sit next to the sidecar exe.
        wintun.copyTo(File(workDir, "wintun.dll"), overwrite = true)
        val localWrapper = File(workDir, "vpn-windows.ps1").also { wrapper.copyTo(it, overwrite = true) }

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
}
