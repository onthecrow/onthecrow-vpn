package com.onthecrow.onthecrowvpn.connection.model

data class ConnectionConfigSummary(
    val title: String,
    val protocol: String,
    val address: String?,
    val port: Int?,
    val security: String?,
    val transport: String?,
    val sni: String?,
    val outboundCount: Int,
    val isAdvanced: Boolean,
)
