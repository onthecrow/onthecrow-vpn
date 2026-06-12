package com.onthecrow.onthecrowvpn.connection.model

enum class SourceKind { SUBSCRIPTION_ID, SUBSCRIPTION_URL, XRAY_LINK }

/** One config row inside a group, carrying its globally-unique [ref] so UI never recomputes keys. */
data class ConfigRow(
    val ref: ConfigRef,
    val config: RemoteConfig,
)

/**
 * A display group on the main screen. For SUBSCRIPTION_ID/SUBSCRIPTION_URL [sourceId] is the
 * underlying [ConfigSource.id]; all XRAY_LINK sources merge into a single pseudo-group with
 * [sourceId] = [SourceGroup.XRAY_LINKS_GROUP_ID] whose rows keep their real per-link source ids
 * inside [ConfigRow.ref] (deletion/selection of an individual link needs them).
 */
data class SourceGroup(
    val sourceId: String,
    val kind: SourceKind,
    val title: String,
    val subtitle: String? = null,
    val rows: List<ConfigRow> = emptyList(),
    /** Firestore source awaiting its first snapshot with no cache yet. */
    val isLoading: Boolean = false,
    /** Transient per-group problem (Firestore error/unavailable, id resolving to nothing). */
    val error: String? = null,
    val addedAt: Long = 0L,
) {
    companion object {
        const val XRAY_LINKS_GROUP_ID = "xray_links"
    }
}

/** Combined output of the config-sources orchestrator — everything the main screen renders. */
data class ConfigSourcesState(
    /** Already in display order: subscription-id groups, then subscription-url groups, then links. */
    val groups: List<SourceGroup> = emptyList(),
    val selected: ConfigRef? = null,
    val selectedConfig: RemoteConfig? = null,
    /**
     * One-shot signal: a remotely-revoked source contained the current selection. The orchestrator has
     * already removed the source and cleared the selection; consumers should stop the VPN
     * ([VpnController.revoke]) and inform the user. `false` on every subsequent state.
     */
    val revokedActiveSelection: Boolean = false,
    /** One-shot: titles of sources auto-removed by remote revocation (for the snackbar). */
    val revokedSourceTitles: List<String> = emptyList(),
)
