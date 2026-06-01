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
                XrayValidationResult.Invalid(testResult.error ?: "Xray rejected configuration")
            } else {
                XrayValidationResult.Valid(
                    xrayJson = xrayJson,
                    summary = summarizer.summarize(xrayJson, fallbackTitle = "Xray config"),
                )
            }
        }.getOrElse { error ->
            XrayValidationResult.Invalid(error.message ?: "Failed to validate Xray config")
        }
    }

    override suspend fun setTunFd(fd: Int) {
        val libClass = libXrayClass ?: return
        runCatching {
            findMethod(libClass, "setTunFd", Int::class.javaPrimitiveType)?.invoke(null, fd)
                ?: findMethod(libClass, "setTunFd", Integer.TYPE)?.invoke(null, fd)
                ?: findMethod(libClass, "setTunFd", Long::class.javaPrimitiveType)?.invoke(null, fd.toLong())
        }
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
            val response = callResponse(
                methodName = "runXrayFromJSON",
                argument = base64(request),
                libClass = libClass,
            )
            if (response.success) XrayRunResult.Success
            else XrayRunResult.Failure(response.error ?: "Xray failed to start")
        }.getOrElse { error ->
            XrayRunResult.Failure(error.message ?: "Xray failed to start")
        }
    }

    override suspend fun stop(): XrayRunResult {
        val libClass = libXrayClass ?: return XrayRunResult.Success
        return runCatching {
            val response = callResponse("stopXray", null, libClass)
            if (response.success) XrayRunResult.Success
            else XrayRunResult.Failure(response.error ?: "Xray failed to stop")
        }.getOrElse { error ->
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
        val controllerInterface = listOf(
            "libXray.DialerController",
            "libxray.DialerController",
        ).firstNotNullOfOrNull { className ->
            runCatching { Class.forName(className) }.getOrNull()
        } ?: return
        val proxy = Proxy.newProxyInstance(
            controllerInterface.classLoader,
            arrayOf(controllerInterface),
            ProtectFdInvocationHandler,
        )
        findMethod(libClass, "registerDialerController", controllerInterface)?.invoke(null, proxy)
        findMethod(libClass, "registerListenerController", controllerInterface)?.invoke(null, proxy)
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
            if (!method.name.equals("protectFd", ignoreCase = true)) {
                return defaultForReturnType(method.returnType)
            }
            val fd = when (val raw = args?.firstOrNull()) {
                is Int -> raw
                is Long -> raw.toInt()
                else -> return false
            }
            return AndroidVpnSocketProtector.protect(fd)
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
