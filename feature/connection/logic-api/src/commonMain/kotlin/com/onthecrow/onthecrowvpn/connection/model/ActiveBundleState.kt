package com.onthecrow.onthecrowvpn.connection.model

data class ActiveBundleState(
    val savedBundleId: String? = null,
    val bundle: ConfigBundle? = null,
    val selectedConfigId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
