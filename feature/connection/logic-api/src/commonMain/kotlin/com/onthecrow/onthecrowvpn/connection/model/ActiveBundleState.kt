package com.onthecrow.onthecrowvpn.connection.model

data class ActiveBundleState(
    val savedBundleId: String? = null,
    val bundle: ConfigBundle? = null,
    val selectedConfigId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    /**
     * One-shot signal: the bundle we were holding was deleted remotely (Firestore `NotFound` for the
     * id we had cached). The orchestrator has just wiped local persistence; consumers should stop and
     * forget the VPN ([VpnController.revoke]) and inform the user. `false` on every subsequent state.
     */
    val revoked: Boolean = false,
)
