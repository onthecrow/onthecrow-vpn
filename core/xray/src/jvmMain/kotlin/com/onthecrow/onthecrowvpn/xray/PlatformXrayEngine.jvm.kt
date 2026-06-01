package com.onthecrow.onthecrowvpn.xray

actual class PlatformXrayEngine : XrayEngine {
    private val summarizer = XrayConfigSummarizer()

    override suspend fun validate(rawConfig: String): XrayValidationResult {
        val trimmed = rawConfig.trim()
        if (trimmed.isBlank()) return XrayValidationResult.Invalid("Configuration is empty")
        val summary = if (trimmed.startsWith("{")) {
            summarizer.summarize(trimmed)
        } else {
            summarizer.summarizeShareText(trimmed)
        }
        return XrayValidationResult.Valid(trimmed, summary)
    }

    override suspend fun setTunFd(fd: Int) = Unit

    override suspend fun start(xrayJson: String): XrayRunResult {
        return XrayRunResult.Failure("VPN is not implemented on this platform yet")
    }

    override suspend fun stop(): XrayRunResult = XrayRunResult.Success
}
