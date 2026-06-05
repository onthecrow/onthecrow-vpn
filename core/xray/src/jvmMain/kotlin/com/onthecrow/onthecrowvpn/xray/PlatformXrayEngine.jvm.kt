package com.onthecrow.onthecrowvpn.xray

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class PlatformXrayEngine : XrayEngine {
    private val summarizer = XrayConfigSummarizer()
    private val sanitizer = XrayConfigSanitizer()

    override suspend fun validate(rawConfig: String): XrayValidationResult = withContext(Dispatchers.IO) {
        val trimmed = rawConfig.trim()
        if (trimmed.isBlank()) {
            return@withContext XrayValidationResult.Invalid("Configuration is empty")
        }

        val rawXrayJson = if (trimmed.startsWith("{")) {
            // Already an Xray JSON config.
            trimmed
        } else {
            // Share link (vless:// / hysteria2:// / …): convert via the bundled
            // libXray converter so the result matches the mobile path exactly.
            val converter = DesktopXrayBinaries.resolveConverter()
                ?: return@withContext XrayValidationResult.Invalid(
                    "VPN engine not found. Run scripts/build-libxray-desktop.sh first.",
                )
            when (val result = runConverter(converter, trimmed)) {
                is ConverterResult.Ok -> result.json
                is ConverterResult.Error -> return@withContext XrayValidationResult.Invalid(result.message)
            }
        }

        val xrayJson = sanitizer.sanitize(rawXrayJson)
        XrayValidationResult.Valid(
            xrayJson = xrayJson,
            summary = summarizer.summarize(xrayJson, fallbackTitle = "Xray config"),
        )
    }

    override suspend fun setTunFd(fd: Int) = Unit

    // The desktop tunnel is driven by PlatformVpnController (sidecar process),
    // not by this engine. start/stop stay no-ops here.
    override suspend fun start(xrayJson: String): XrayRunResult = XrayRunResult.Success

    override suspend fun stop(): XrayRunResult = XrayRunResult.Success

    private sealed interface ConverterResult {
        data class Ok(val json: String) : ConverterResult
        data class Error(val message: String) : ConverterResult
    }

    private fun runConverter(converter: File, link: String): ConverterResult {
        return runCatching {
            val process = ProcessBuilder(converter.absolutePath)
                .redirectErrorStream(false)
                .start()
            process.outputStream.use { it.write(link.toByteArray()); it.flush() }
            val stdout = process.inputStream.readBytes().decodeToString()
            val stderr = process.errorStream.readBytes().decodeToString()
            val exit = process.waitFor()
            if (exit != 0 || stdout.isBlank()) {
                ConverterResult.Error(stderr.trim().ifBlank { "Failed to parse configuration link" })
            } else {
                ConverterResult.Ok(stdout)
            }
        }.getOrElse { error ->
            ConverterResult.Error(error.message ?: "Converter failed")
        }
    }
}

private object DesktopXrayBinaries {
    private val archSlug: String by lazy {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        val a = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x64"
        when {
            osName.contains("mac") || osName.contains("darwin") -> "macos-$a"
            osName.contains("win") -> "windows-$a"
            else -> "unknown-$a"
        }
    }

    private val converterName: String
        get() = if (archSlug.startsWith("windows")) "onthecrow-convert.exe" else "onthecrow-convert"

    @Volatile
    private var cached: File? = null

    fun resolveConverter(): File? {
        cached?.let { if (it.exists()) return it }
        val found = locate() ?: return null
        // Dev binaries (local-libs) keep their +x bit; bundled ones may lose it and
        // sit in a read-only app bundle. If not runnable in place, extract once.
        val usable = if (found.canExecute()) {
            found
        } else {
            File(System.getProperty("java.io.tmpdir"), converterName).also { tmp ->
                runCatching { found.copyTo(tmp, overwrite = true); tmp.setExecutable(true) }
            }
        }
        cached = usable
        return usable
    }

    private fun locate(): File? {
        // Packaged: Compose flattens the os-arch subdir into the resources root.
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            val f = File(dir, converterName)
            if (f.exists()) return f
        }
        var base: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val root = base ?: return@repeat
            val f = File(root, "local-libs/libxray-desktop/$archSlug/$converterName")
            if (f.exists()) return f
            base = root.parentFile
        }
        return null
    }
}
