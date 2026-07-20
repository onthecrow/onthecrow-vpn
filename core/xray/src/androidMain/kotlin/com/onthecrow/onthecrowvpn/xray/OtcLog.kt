package com.onthecrow.onthecrowvpn.xray

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's telemetry: ONE process-aware log file that everything appends to.
 *
 * Both processes (main and ":vpn") write here, and so does xray-core itself — the service points its
 * error log at this same path — so a user reporting a problem shares a single file with the whole
 * story in it. Every line carries the originating process + pid and the live doze/screen state.
 *
 * Active in release builds: the point is to be able to ask a user for their log. What release does
 * change is xray's own verbosity — see [isDebugBuild], since its debug level alone is 95% of the
 * volume. Every line is written and flushed as it arrives; see [log].
 */
object OtcLog {
    const val FILE_NAME = "onthecrow-vpn.log"

    /** How often the size is actually checked, rather than on every single line. */
    private const val SIZE_CHECK_INTERVAL_MS = 60_000L

    /** Hard ceiling for the shared file. Truncated in place on the way past it. */
    private const val MAX_BYTES = 100L * 1024 * 1024

    // Single writer thread: serializes appends, keeps file IO off caller threads, preserves order.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "otc-log").apply { isDaemon = true }
    }

    // Touched only on the executor thread → no synchronization needed.
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var writerResolved = false
    private var procTag: String? = null
    private var logFile: File? = null
    private var lastSizeCheckAt = 0L

    /**
     * Debug builds only — and this gates VERBOSITY, not whether we log at all.
     *
     * xray at `debug` writes a line per proxied connection: measured at 110-183 lines/minute against
     * 7-8 for everything of ours put together. That detail is for chasing a protocol bug on a build
     * you are actively instrumenting, not for a log a user sends you.
     */
    val isDebugBuild: Boolean
        get() {
            if (debugResolved) return debugBuild
            val ctx = appContext() ?: return false
            debugBuild = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            debugResolved = true
            return debugBuild
        }

    @Volatile
    private var debugBuild = false

    @Volatile
    private var debugResolved = false

    /** The single file everything lands in — ours and xray's alike. */
    fun logFile(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)

    /**
     * Log one line. Cheap on the caller; the actual file write is queued to the writer thread.
     *
     * Flushed per line, deliberately. The volume that made this look expensive was xray's own debug
     * output, which it writes through its own handle and which our flush policy never touched — our
     * side is 7-8 lines a minute. At that rate buffering saved nothing measurable and cost the one
     * property this file exists for: that whatever the user shares is complete right up to the moment
     * the process died.
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
                    trimIfHuge()
                }
            }
        }
    }

    /**
     * Keep the file under [MAX_BYTES]. Truncated IN PLACE rather than deleted and recreated: xray
     * (Go) holds its own append handle on this same file, and deleting it would leave that handle
     * pointing at an unlinked inode — xray's lines would vanish silently for the life of the process.
     */
    private fun trimIfHuge() {
        val file = logFile ?: return
        // stat() per line is the one thing worth rate-limiting here; the file cannot grow 100 MB in
        // a minute.
        val now = System.currentTimeMillis()
        if (now - lastSizeCheckAt < SIZE_CHECK_INTERVAL_MS) return
        lastSizeCheckAt = now
        runCatching {
            if (file.length() <= MAX_BYTES) return
            writer?.flush()
            RandomAccessFile(file, "rw").use { it.setLength(0) }
            writer?.write(
                "${timeFormat.format(Date(System.currentTimeMillis()))} " +
                    "[${procTag} ----- ] ===== log truncated at ${MAX_BYTES}B =====",
            )
            writer?.newLine()
            writer?.flush()
        }
    }

    /**
     * Wait for the write queue to drain.
     *
     * Lines are written per call, but the write itself is HANDED OFF to the writer thread, so a line
     * logged a moment ago may still be in the queue. This submits a marker to that same
     * single-threaded executor and blocks on it: FIFO means everything queued earlier has landed by
     * the time the marker runs.
     *
     * It matters most immediately before a hard kill — the `:vpn` process self-terminates on
     * disconnect and on a recovery restart, and anything still queued dies with it. Sharing the log
     * calls it too, where it is a cheap barrier rather than a necessity.
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
            val file = logFile(ctx).also { logFile = it }
            BufferedWriter(FileWriter(file, /* append = */ true)).also { w ->
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
