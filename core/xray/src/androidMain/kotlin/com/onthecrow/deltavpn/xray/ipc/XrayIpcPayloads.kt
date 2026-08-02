package com.onthecrow.deltavpn.xray.ipc

import com.onthecrow.deltavpn.connection.model.ConnectionConfigSummary
import com.onthecrow.deltavpn.xray.XrayRunResult
import com.onthecrow.deltavpn.xray.XrayValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The JSON carried inside [XrayIpc.TX_INVOKE].
 *
 * The domain types ([XrayValidationResult], [ConnectionConfigSummary]) are deliberately NOT annotated
 * `@Serializable` themselves: they belong to a common source set shared with iOS and desktop, and
 * making them serializable there to satisfy an Android-only transport would push a wire concern into
 * the domain. These mirrors convert at the boundary instead, which also means a change to a domain
 * type surfaces as a compile error here rather than as a silently-dropped field.
 */
internal object XrayIpcPayloads {
    val json = Json { ignoreUnknownKeys = true }
}

@Serializable
internal data class IpcRequest(
    val method: String,
    val arg: String? = null,
)

/**
 * `ok = false` means the CALL failed (the engine process threw, or is not there). It does not mean the
 * operation returned a negative result — a rejected config is a successful call carrying
 * [IpcValidation.valid] = false. Collapsing the two would report a broken engine as a bad config.
 */
@Serializable
internal data class IpcResponse(
    val ok: Boolean,
    val error: String? = null,
    val validation: IpcValidation? = null,
    val run: IpcRun? = null,
    /** The engine process's own pid, so the host can kill it if it stops answering. */
    val pid: Int? = null,
)

@Serializable
internal data class IpcValidation(
    val valid: Boolean,
    val xrayJson: String? = null,
    val summary: IpcSummary? = null,
    val message: String? = null,
)

@Serializable
internal data class IpcSummary(
    val title: String,
    val protocol: String,
    val address: String? = null,
    val port: Int? = null,
    val security: String? = null,
    val transport: String? = null,
    val sni: String? = null,
    val outboundCount: Int,
    val isAdvanced: Boolean,
)

@Serializable
internal data class IpcRun(
    val success: Boolean,
    val message: String? = null,
)

internal fun ConnectionConfigSummary.toIpc() = IpcSummary(
    title = title,
    protocol = protocol,
    address = address,
    port = port,
    security = security,
    transport = transport,
    sni = sni,
    outboundCount = outboundCount,
    isAdvanced = isAdvanced,
)

internal fun IpcSummary.toDomain() = ConnectionConfigSummary(
    title = title,
    protocol = protocol,
    address = address,
    port = port,
    security = security,
    transport = transport,
    sni = sni,
    outboundCount = outboundCount,
    isAdvanced = isAdvanced,
)

internal fun XrayValidationResult.toIpc(): IpcValidation = when (this) {
    is XrayValidationResult.Valid -> IpcValidation(
        valid = true,
        xrayJson = xrayJson,
        summary = summary.toIpc(),
    )
    is XrayValidationResult.Invalid -> IpcValidation(valid = false, message = message)
}

internal fun IpcValidation.toDomain(): XrayValidationResult = when {
    !valid -> XrayValidationResult.Invalid(message ?: "Configuration was rejected")
    xrayJson == null || summary == null ->
        XrayValidationResult.Invalid("Engine returned an incomplete result")
    else -> XrayValidationResult.Valid(xrayJson, summary.toDomain())
}

internal fun XrayRunResult.toIpc(): IpcRun = when (this) {
    XrayRunResult.Success -> IpcRun(success = true)
    is XrayRunResult.Failure -> IpcRun(success = false, message = message)
}

internal fun IpcRun.toDomain(): XrayRunResult =
    if (success) XrayRunResult.Success else XrayRunResult.Failure(message ?: "Engine reported a failure")
