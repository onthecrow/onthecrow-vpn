package com.onthecrow.onthecrowvpn.xray

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.cancellation.CancellationException

private const val LOG_TAG = "XRAY"

/**
 * The protect controllers are registered into a PROCESS-GLOBAL slice inside xray (Go), which is
 * iterated for every outbound socket before bind. Registering on each [PlatformXrayEngine.start] —
 * as this used to — appends another pair every time, so after N in-process re-dials each socket
 * creation costs 2N JNI round-trips in the hot path, and any handler returning `false` risks xray
 * treating the socket as unprotected, which routes it back into our own tun (an encapsulation loop
 * that presents exactly as "tunnel dead"). Register once per process instead.
 */
private val protectControllersRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * How many sockets xray has asked us to protect in this process. This is the **eviction oracle**: a
 * recovery that does not increment it did not open a fresh upstream connection — xray handed back the
 * pooled (and possibly dead) one. The recovery ladder logs the delta across each tier, which is the
 * only way to tell "re-dialled" from "re-used" without reading xray's own debug log.
 */
val protectFdCount = java.util.concurrent.atomic.AtomicLong(0)

actual class PlatformXrayEngine : XrayEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val summarizer = XrayConfigSummarizer(json)
    private val sanitizer = XrayConfigSanitizer(json)

    /**
     * The tun fd xray should adopt on its next start.
     *
     * libXray no longer exposes `setTunFd`: the fd travels inside the config's `env` block, read by
     * xray-core's `proxy/tun` through the `xray.tun.fd` platform flag. Keeping [setTunFd] in the
     * interface and remembering the value here means callers still say "here is the tun, now start"
     * and do not have to know that it is really a config detail.
     */
    @Volatile
    private var tunFd: Int? = null

    private val libXrayClass: Class<*>? by lazy {
        listOf(
            "libXray.LibXray",
            "libxray.LibXray",
            "LibXray",
        ).firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className) }.getOrNull()
        }
    }

    override suspend fun validate(rawConfig: String): XrayValidationResult {
        val trimmed = rawConfig.trim()
        if (trimmed.isBlank()) {
            return XrayValidationResult.Invalid("Configuration is empty")
        }
        if (libXrayClass == null) {
            return XrayValidationResult.Invalid(
                "libXray is not installed. Run scripts/build-libxray-android.sh first.",
            )
        }
        OtcLog.log(LOG_TAG, "validate: rawLen=${trimmed.length}")
        return runCatching {
            val converted = invoke(
                method = "convertShareLinksToXrayJson",
                payload = buildJsonObject { put("text", trimmed) },
            )
            val rawXrayJson = converted.data?.let { json.encodeToString(it) }
                ?: return@runCatching XrayValidationResult.Invalid("Xray returned empty config")
            val xrayJson = sanitizer.sanitize(rawXrayJson)
            // testXray only takes a path, so the environment it needs (the geo asset directory) has
            // to be inside the file we hand it.
            val configPath = writeConfigFile(withPlatformEnv(xrayJson, includeTunFd = false))
            val testResult = invoke(
                method = "testXray",
                payload = buildJsonObject { put("configPath", configPath.absolutePath) },
            )
            if (!testResult.success) {
                OtcLog.log(LOG_TAG, "validate: testXray REJECTED — ${testResult.error}")
                XrayValidationResult.Invalid(testResult.error ?: "Xray rejected configuration")
            } else {
                OtcLog.log(LOG_TAG, "validate: OK")
                XrayValidationResult.Valid(
                    xrayJson = xrayJson,
                    summary = summarizer.summarize(xrayJson, fallbackTitle = "Xray config"),
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            OtcLog.log(LOG_TAG, "validate: THREW ${error.stackTraceToString()}")
            XrayValidationResult.Invalid(error.message ?: "Failed to validate Xray config")
        }
    }

    override suspend fun setTunFd(fd: Int) {
        tunFd = fd
        OtcLog.log(LOG_TAG, "tun fd for the next start: $fd")
    }

    override suspend fun start(xrayJson: String): XrayRunResult {
        val libClass = libXrayClass ?: return XrayRunResult.Failure(
            "libXray is not installed. Run scripts/build-libxray-android.sh first.",
        )
        return runCatching {
            registerProtectControllers(libClass)
            val runtimeJson = withPlatformEnv(xrayJson, includeTunFd = true)
            OtcLog.log(LOG_TAG, "runXrayFromJson: configBytes=${runtimeJson.length} tunFd=$tunFd")
            val response = invoke(
                method = "runXrayFromJson",
                payload = buildJsonObject { put("configJSON", runtimeJson) },
            )
            if (response.success) {
                OtcLog.log(LOG_TAG, "runXrayFromJson: success")
                XrayRunResult.Success
            } else {
                OtcLog.log(LOG_TAG, "runXrayFromJson: FAILED error=${response.error}")
                XrayRunResult.Failure(response.error ?: "Xray failed to start")
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            OtcLog.log(LOG_TAG, "runXrayFromJson: THREW ${error.stackTraceToString()}")
            XrayRunResult.Failure(error.message ?: "Xray failed to start")
        }
    }

    override suspend fun stop(): XrayRunResult {
        if (libXrayClass == null) return XrayRunResult.Success
        return runCatching {
            val response = invoke(method = "stopXray")
            // Ask rather than assume. The whole in-process re-dial rests on the engine really being
            // stopped — xray refuses to start again while an instance is live — so a stop that only
            // *claims* to have worked would strand the tunnel with no way to tell from the outside.
            val running = isRunning()
            OtcLog.log(LOG_TAG, "stopXray: success=${response.success} running=$running error=${response.error}")
            when {
                running == true -> XrayRunResult.Failure("Xray is still running after stopXray")
                response.success -> XrayRunResult.Success
                else -> XrayRunResult.Failure(response.error ?: "Xray failed to stop")
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            OtcLog.log(LOG_TAG, "stopXray: THREW ${error.stackTraceToString()}")
            XrayRunResult.Failure(error.message ?: "Xray failed to stop")
        }
    }

    /** null when libXray cannot answer; callers treat that as "cannot verify", not as "stopped". */
    private fun isRunning(): Boolean? = runCatching {
        invoke(method = "getXrayState").data?.jsonObject?.get("running")?.jsonPrimitive?.booleanOrNull
    }.getOrNull()

    /**
     * Merge the platform environment xray needs into the config's top-level `env` block.
     *
     * Both values used to be arguments to libXray calls that no longer exist; xray-core reads them as
     * platform flags, and its config loader applies `env` with `os.Setenv` while building. Merged
     * rather than assigned, so a config that already carries an `env` keeps it.
     */
    private fun withPlatformEnv(xrayJson: String, includeTunFd: Boolean): String {
        val root = runCatching { json.parseToJsonElement(xrayJson).jsonObject }.getOrNull()
            ?: return xrayJson
        val env = buildJsonObject {
            (root["env"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("xray.location.asset", datDir().absolutePath)
            if (includeTunFd) tunFd?.let { put("xray.tun.fd", it.toString()) }
        }
        val merged = buildJsonObject {
            root.forEach { (key, value) -> if (key != "env") put(key, value) }
            put("env", env)
        }
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    /**
     * The single entry point libXray exposes now: one JSON envelope in, one JSON envelope out.
     *
     * Everything used to be a separate exported function taking base64; the whole surface collapsed
     * into `invoke`, and the encoding is plain JSON in both directions. Still reached by reflection so
     * a missing or mismatched AAR degrades to a readable error instead of a link failure at startup.
     */
    private fun invoke(method: String, payload: JsonObject? = null): InvokeResponse {
        val libClass = libXrayClass ?: error("libXray is not installed")
        val request = buildJsonObject {
            put("apiVersion", 1)
            put("method", method)
            if (payload != null) put("payload", payload)
        }
        val invokeMethod = findMethod(libClass, "invoke", String::class.java)
            ?: error("libXray.invoke(String) was not found — the bundled AAR is too old")
        val raw = invokeMethod.invoke(null, json.encodeToString(JsonObject.serializer(), request))
            as? String ?: error("libXray.invoke returned a non-string response")
        val root = json.parseToJsonElement(raw).jsonObject
        return InvokeResponse(
            success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false,
            data = root["data"],
            error = root["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }

    private fun registerProtectControllers(libClass: Class<*>) {
        if (!protectControllersRegistered.compareAndSet(false, true)) return
        val controllerInterface = listOf(
            "libXray.DialerController",
            "libxray.DialerController",
        ).firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className) }.getOrNull()
        } ?: run {
            OtcLog.log(LOG_TAG, "registerProtectControllers: DialerController interface NOT found")
            return
        }
        val proxy = Proxy.newProxyInstance(
            controllerInterface.classLoader,
            arrayOf(controllerInterface),
            ProtectFdInvocationHandler,
        )
        val dialer = findMethod(libClass, "registerDialerController", controllerInterface)?.also { it.invoke(null, proxy) }
        val listener = findMethod(libClass, "registerListenerController", controllerInterface)?.also { it.invoke(null, proxy) }
        OtcLog.log(LOG_TAG, "registerProtectControllers: dialer=${dialer != null} listener=${listener != null}")
    }

    private fun findMethod(
        libClass: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>?,
    ): Method? {
        return runCatching {
            libClass.getMethod(name, *parameterTypes.filterNotNull().toTypedArray())
        }.getOrNull()
    }

    private fun writeConfigFile(xrayJson: String): File {
        return File(datDir(), "validated-config.json").also { file ->
            file.parentFile?.mkdirs()
            file.writeText(xrayJson)
        }
    }

    private fun datDir(): File {
        val context = AndroidXrayEnvironment.applicationContext
        return File(context.filesDir, "xray").also { it.mkdirs() }
    }

    private data class InvokeResponse(
        val success: Boolean,
        val data: JsonElement?,
        val error: String?,
    )

    private object ProtectFdInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            // This runs on xray's (Go) threads via JNI. An exception thrown back across the JNI boundary
            // can crash the whole :vpn process, so NOTHING here may propagate — swallow everything and
            // return a safe default for the method's return type.
            return try {
                if (!method.name.equals("protectFd", ignoreCase = true)) {
                    return defaultForReturnType(method.returnType)
                }
                val fd = when (val raw = args?.firstOrNull()) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    else -> return false
                }
                OtcLog.log(LOG_TAG, "protectFd #${protectFdCount.incrementAndGet()} fd=$fd")
                AndroidVpnSocketProtector.protect(fd)
            } catch (t: Throwable) {
                OtcLog.log(LOG_TAG, "protectFd handler threw (swallowed): ${t.message}")
                defaultForReturnType(method.returnType)
            }
        }

        private fun defaultForReturnType(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}
