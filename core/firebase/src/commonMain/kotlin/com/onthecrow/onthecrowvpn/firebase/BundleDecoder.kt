package com.onthecrow.onthecrowvpn.firebase

import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig

internal fun decodeBundleOrNull(id: String, data: Map<String, Any?>?): ConfigBundle? {
    if (data == null) return null
    val name = data["name"] as? String
    val createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L
    val updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: createdAt
    val rawList = data["configs"] as? List<*> ?: return null
    val configs = rawList.mapNotNull { entry ->
        val raw = entry as? Map<*, *> ?: return@mapNotNull null
        val cId = (raw["id"] as? String) ?: return@mapNotNull null
        val cName = (raw["name"] as? String) ?: return@mapNotNull null
        val cUrl = (raw["url"] as? String) ?: return@mapNotNull null
        val cLocation = raw["location"] as? String
        val cType = raw["type"] as? String
        RemoteConfig(id = cId, name = cName, location = cLocation, url = cUrl, type = cType)
    }
    if (configs.isEmpty()) return null
    return ConfigBundle(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        configs = configs,
    )
}
