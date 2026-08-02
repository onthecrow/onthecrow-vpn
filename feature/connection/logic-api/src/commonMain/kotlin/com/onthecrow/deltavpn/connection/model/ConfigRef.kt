package com.onthecrow.deltavpn.connection.model

import kotlinx.serialization.Serializable

/**
 * Globally-unique reference to a single config: [RemoteConfig.id] is only unique within its source,
 * so selection (and anything else crossing sources) must carry the composite key.
 */
@Serializable
data class ConfigRef(
    val sourceId: String,
    val configId: String,
)
