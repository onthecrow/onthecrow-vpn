package com.onthecrow.onthecrowvpn.connection.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigBundle(
    val id: String,
    val name: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val configs: List<RemoteConfig>,
)

@Serializable
data class RemoteConfig(
    val id: String,
    val name: String,
    val location: String? = null,
    val url: String,
)
