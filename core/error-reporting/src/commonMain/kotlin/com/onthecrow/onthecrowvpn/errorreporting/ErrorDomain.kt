package com.onthecrow.onthecrowvpn.errorreporting

/**
 * The subsystem a reported error came from — a bounded, non-identifying label.
 *
 * It is attached to each Crashlytics non-fatal (as the `error_domain` custom key and in the synthetic
 * exception's message) so failures can be grouped and filtered by area without any raw data. Add a
 * constant when a genuinely new subsystem starts reporting; never encode an id, host, or free-form
 * detail here.
 */
enum class ErrorDomain {
    /** VpnService tunnel lifecycle: establish/teardown/monitoring, and the service's coroutine scope. */
    VPN_TUNNEL,

    /** libXray start/validate/run failures surfaced to the app process. */
    XRAY_ENGINE,

    /** Binder transactions to the `:xray` process (dead object, transaction failure). */
    XRAY_IPC,

    /** Excluding a socket from the tunnel via `protect()`. */
    SOCKET_PROTECT,

    /** Downloading / parsing a subscription URL. */
    SUBSCRIPTION_FETCH,

    /** Share-link conversion / summarisation / connect-time validation. */
    CONFIG_VALIDATION,

    /** Firestore bundle lookups. */
    FIRESTORE,

    /** DataStore reads/writes (config sources, split-tunnel, consent, connection params). */
    DATASTORE,

    /** Restoring the tunnel after boot / app update. */
    BOOT_RESTORE,

    /** The Quick Settings tile. */
    QUICK_SETTINGS_TILE,

    /** Process/DI/Firebase initialisation. */
    APP_INIT,

    /** Enumerating installed apps for the split-tunnel picker. */
    INSTALLED_APPS,

    /** Anything on a platform integration path not covered above. */
    PLATFORM,
}
