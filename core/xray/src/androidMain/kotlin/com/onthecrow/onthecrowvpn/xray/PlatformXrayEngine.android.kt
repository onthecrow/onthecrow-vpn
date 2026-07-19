package com.onthecrow.onthecrowvpn.xray

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val libClass = libXrayClass ?: return XrayValidationResult.Invalid(
            "libXray is not installed. Run scripts/build-libxray-android.sh first.",
        )
        OtcLog.log(LOG_TAG, "validate: rawLen=${trimmed.length}")
        return runCatching {
            val converted = callResponse(
                methodName = "convertShareLinksToXrayJson",
                argument = base64(trimmed),
                libClass = libClass,
            )
            val rawXrayJson = converted.data?.let { json.encodeToString(it) }
                ?: return@runCatching XrayValidationResult.Invalid("Xray returned empty config")
            val xrayJson = sanitizer.sanitize(rawXrayJson)
            val configPath = writeConfigFile(xrayJson)
            val testRequest = json.encodeToString(
                mapOf(
                    "datDir" to datDir().absolutePath,
                    "configPath" to configPath.absolutePath,
                )
            )
            val testResult = callResponse(
                methodName = "testXray",
                argument = base64(testRequest),
                libClass = libClass,
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
        val libClass = libXrayClass ?: run {
            OtcLog.log(LOG_TAG, "setTunFd($fd) skipped — libXray not loaded")
            return
        }
        runCatching {
            val via = when {
                findMethod(libClass, "setTunFd", Int::class.javaPrimitiveType)?.also { it.invoke(null, fd) } != null -> "int"
                findMethod(libClass, "setTunFd", Integer.TYPE)?.also { it.invoke(null, fd) } != null -> "Integer"
                findMethod(libClass, "setTunFd", Long::class.javaPrimitiveType)?.also { it.invoke(null, fd.toLong()) } != null -> "long"
                else -> "NONE"
            }
            OtcLog.log(LOG_TAG, "setTunFd($fd) via=$via")
        }.onFailure { OtcLog.log(LOG_TAG, "setTunFd($fd) FAILED: ${it.message}") }
    }

    override suspend fun start(xrayJson: String): XrayRunResult {
        val libClass = libXrayClass ?: return XrayRunResult.Failure(
            "libXray is not installed. Run scripts/build-libxray-android.sh first.",
        )
        return runCatching {
            registerProtectControllers(libClass)
            val request = json.encodeToString(
                mapOf(
                    "datDir" to datDir().absolutePath,
                    "configJSON" to xrayJson,
                )
            )
            OtcLog.log(LOG_TAG, "runXrayFromJSON: configBytes=${xrayJson.length}")
            val response = callResponse(
                methodName = "runXrayFromJSON",
                argument = base64(request),
                libClass = libClass,
            )
            if (response.success) {
                OtcLog.log(LOG_TAG, "runXrayFromJSON: success")
                XrayRunResult.Success
            } else {
                OtcLog.log(LOG_TAG, "runXrayFromJSON: FAILED error=${response.error}")
                XrayRunResult.Failure(response.error ?: "Xray failed to start")
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            OtcLog.log(LOG_TAG, "runXrayFromJSON: THREW ${error.stackTraceToString()}")
            XrayRunResult.Failure(error.message ?: "Xray failed to start")
        }
    }

    override suspend fun stop(): XrayRunResult {
        val libClass = libXrayClass ?: return XrayRunResult.Success
        return runCatching {
            val response = callResponse("stopXray", null, libClass)
            OtcLog.log(LOG_TAG, "stopXray: success=${response.success} error=${response.error}")
            if (response.success) XrayRunResult.Success
            else XrayRunResult.Failure(response.error ?: "Xray failed to stop")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            OtcLog.log(LOG_TAG, "stopXray: THREW ${error.stackTraceToString()}")
            XrayRunResult.Failure(error.message ?: "Xray failed to stop")
        }
    }

    private fun callResponse(
        methodName: String,
        argument: String?,
        libClass: Class<*>,
    ): LibXrayCallResponse {
        val method = if (argument == null) {
            findMethod(libClass, methodName)
        } else {
            findMethod(libClass, methodName, String::class.java)
        } ?: error("libXray method $methodName was not found")
        val result = if (argument == null) method.invoke(null) else method.invoke(null, argument)
        val encoded = result as? String ?: error("libXray method $methodName returned non-string response")
        return decodeResponse(encoded)
    }

    private fun decodeResponse(encoded: String): LibXrayCallResponse {
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
        val root = json.parseToJsonElement(decoded).jsonObject
        return LibXrayCallResponse(
            success = root["success"]?.jsonPrimitive?.booleanOrNull ?: false,
            data = root["data"],
            error = root["error"]?.jsonPrimitive?.contentOrNull,
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

    private fun base64(text: String): String {
        return Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
    }

    private data class LibXrayCallResponse(
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
