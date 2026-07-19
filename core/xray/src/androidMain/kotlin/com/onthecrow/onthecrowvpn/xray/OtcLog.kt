package com.onthecrow.onthecrowvpn.xray

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TEMP (diagnosis): one unified, process-aware file logger for the WHOLE connection/reconnect chain.
 *
 * Both the main UI process and the ":vpn" service process append to the SAME `vpn-debug.log`. Every
 * line is tagged with the originating process + pid and the live `doze`/`screen` state, and is flushed
 * to disk immediately — so the file is always complete when pulled, even right after the `:vpn` process
 * is force-killed on disconnect. Writes happen on one dedicated background thread (off the caller's
 * thread, FIFO so ordering matches call order; the timestamp is captured at call time).
 *
 * xray-core's own granular log goes to a separate `xray.log` (loglevel=debug, see [XrayConfigSanitizer]
 * and the VpnService). Pull BOTH files together when reporting a reconnect failure.
 *
 * Remove this (and every call site) once the Doze/network recovery is confirmed fixed.
 */
object OtcLog {
    private const val FILE_NAME = "vpn-debug.log"

    // Single writer thread: serializes appends, keeps file IO off caller threads, preserves order.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "otc-log").apply { isDaemon = true }
    }

    // Touched only on the executor thread → no synchronization needed.
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var writerResolved = false
    private var procTag: String? = null

    /**
     * Log one line. Cheap on the caller; the actual file write is queued to the writer thread.
     *
     * Deliberately active in RELEASE builds too: a reconnect failure that only reproduces after hours
     * of real-world use on a real network cannot be chased on a debug build, so the file has to be
     * there when it happens. It stays scoped to the VPN service and libXray — general app logging does
     * not go through here — and it writes only to the file, never to Logcat, so nothing is exposed to
     * other apps or to the system log.
     */
    fun log(tag: String, message: String) {
        val now = System.currentTimeMillis()
        val state = stateLabel()
        runCatching {
            executor.execute {
                val w = writerOrNull() ?: return@execute
                runCatching {
                    w.write("${timeFormat.format(Date(now))} [${procTag ?: "?"} $state $tag] $message")
                    w.newLine()
                    w.flush()
                }
            }
        }
    }

    /**
     * Drain every queued line to disk and block (briefly) until done. Call this right before a hard
     * process kill (the `:vpn` process self-terminates on disconnect) so no buffered line is lost —
     * the writer thread is FIFO, so once our sentinel runs, all prior lines are written + flushed.
     */
    fun flushBlocking(timeoutMs: Long = 800) {
        val done = CountDownLatch(1)
        val submitted = runCatching {
            executor.execute {
                runCatching { writer?.flush() }
                done.countDown()
            }
        }.isSuccess
        if (submitted) runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Lazily (and once) open the append writer on the executor thread; retries until a context exists. */
    private fun writerOrNull(): BufferedWriter? {
        if (writerResolved) return writer
        val ctx = appContext() ?: return null // no context yet — try again on the next line
        writerResolved = true
        procTag = procTag(ctx)
        writer = runCatching {
            val dir = ctx.getExternalFilesDir(null)
            BufferedWriter(FileWriter(File(dir, FILE_NAME), /* append = */ true)).also { w ->
                w.write(
                    "${timeFormat.format(Date(System.currentTimeMillis()))} " +
                        "[${procTag} ----- ] ===== process start pid=${Process.myPid()} =====",
                )
                w.newLine()
                w.flush()
            }
        }.getOrNull()
        return writer
    }

    private fun appContext(): Context? =
        runCatching { AndroidXrayEnvironment.applicationContext }.getOrNull()

    /** "main" or "vpn" — which process this logger instance lives in. */
    private fun procTag(ctx: Context): String {
        val name = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readText().trim { it <= ' ' }
            }
        }.getOrNull()
        val pid = Process.myPid()
        return when {
            name == null -> "p$pid"
            name.endsWith(":vpn") -> "vpn/$pid"
            name == ctx.packageName -> "main/$pid"
            else -> "$name/$pid"
        }
    }

    /** Live `doze`/`screen` state — the two conditions this whole investigation hinges on. */
    private fun stateLabel(): String {
        val ctx = appContext() ?: return "doze=? screen=?"
        return runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            "doze=${pm.isDeviceIdleMode} screen=${if (pm.isInteractive) "on" else "off"}"
        }.getOrElse { "doze=? screen=?" }
    }
}
