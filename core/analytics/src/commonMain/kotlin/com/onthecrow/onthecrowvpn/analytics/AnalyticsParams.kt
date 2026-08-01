package com.onthecrow.onthecrowvpn.analytics

/**
 * Typed parameter vocabularies for [AnalyticsManager].
 *
 * Every value a caller can pass is a closed enum (or a bounded number the manager buckets on-device).
 * There is deliberately **no free-form `String` parameter anywhere** in the analytics API: that makes
 * it structurally impossible to leak a server address, credential, config URL, destination, id, or an
 * error message into an event. Callers map their own sealed types to one of these constants with a
 * `when` at the call site — never by forwarding a `*.message` string. The wire value sent to Firebase
 * is the constant name lowercased (`SUBSCRIPTION_ID` -> `"subscription_id"`).
 */

/** Which kind of configuration source an action concerns. */
enum class SourceKind { SUBSCRIPTION_ID, SUBSCRIPTION_URL, XRAY_LINK }

/** Bounded category for why adding a source failed. Derived from the failure branch, never its message. */
enum class AddFailureReason {
    EMPTY_CLIPBOARD,
    WRONG_KIND_HINT,
    INVALID_CHARS,
    DUPLICATE,
    NOT_FOUND,
    FIREBASE_UNAVAILABLE,
    ACTIVATION_TIMEOUT,
    NETWORK_ERROR,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    NO_CONFIGS_FOUND,
    NO_VALID_CONFIGS,
    ENGINE_INVALID,
}

/** Outcome of the prominent VPN-consent disclosure. */
enum class ConsentResult { ACCEPTED, DECLINED }

/** Outcome of the OS `VpnService.prepare()` permission request. */
enum class VpnPermissionOutcome { GRANTED, DENIED, MISSING_AT_ESTABLISH }

/** Bounded category for why a connect attempt failed before the tunnel came up. */
enum class ConnectFailureCategory {
    CONFIG_VALIDATION,
    PERMISSION_MISSING,
    ENGINE_START_FAILURE,
    TUN_ESTABLISH_FAILURE,
    SPLIT_TUNNEL_NO_APPS,
    SERVICE_START_FAILURE,
    UNKNOWN,
}

/** What brought the tunnel up: a fresh user connect vs a recovery/restore re-establish. */
enum class ConnectVia { FRESH, RECOVERY, RESTORE }

/** Bounded category of a terminal connection error. Mapped from the sealed error TYPE, never its message. */
enum class VpnErrorCategory {
    PERMISSION_DENIED,
    CONFIG_INVALID,
    ENGINE_START_FAILED,
    TUN_ESTABLISH_FAILED,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    UNKNOWN,
}

/** Why a connected session ended. */
enum class SessionEndReason { USER, EXTERNAL_REVOKE, FATAL_ERROR }

/** What triggered a recovery-ladder run. Mapped from the internal reason, never the raw reason string. */
enum class RecoveryTrigger {
    NETWORK_MOVED,
    NETWORK_CONFIRMED,
    LINK_CHANGE,
    SCREEN_ON,
    DOZE_EXIT,
    ENGINE_DIED,
    KEEPALIVE_FAIL,
    OTHER,
}

/** How a recovery-ladder run resolved. `UNRECOVERED_BACKOFF` = not yet recovered, will retry (uncapped). */
enum class RecoveryOutcome {
    RECOVERED_PROBE,
    RECOVERED_ENGINE_RESTART,
    STOOD_DOWN_NO_NETWORK,
    ABANDONED_SLEPT,
    UNRECOVERED_BACKOFF,
}

/**
 * Which recovery profile the ladder ran under — the user-facing "aggressive keepalive" switch.
 * [PATIENT] is the default. Reported per run so the two can be compared on outcome, not on opinion.
 */
enum class RecoveryMode {
    PATIENT,
    AGGRESSIVE,
}

/** Coarse underlying-transport type — the ONLY safe network detail (never netId/IP/interface/SSID). */
enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, UNKNOWN }

/** Why the `:xray` engine process died, mapped from `ApplicationExitInfo.reason` (never its description). */
enum class EngineDeathReason {
    CRASH_NATIVE,
    CRASH_JVM,
    LOW_MEMORY,
    SIGNALED,
    ANR,
    EXIT_SELF,
    DEPENDENCY_DIED,
    EXCESSIVE_RESOURCE,
    INIT_FAILURE,
    PERMISSION_CHANGE,
    USER_REQUESTED,
    USER_STOPPED,
    OTHER,
}

/** Per-app split-tunnel routing mode. */
enum class SplitTunnelMode { OFF, EXCLUDE, INCLUDE }

/** Outcome of rebuilding the tun on a confirmed path change. */
enum class TunRebuildOutcome { REBUILT_OK, ESTABLISH_FAILED, ENGINE_NOT_BACK }

/** Session-level keepalive health verdict. */
enum class KeepaliveWindow { HEALTHY, DEGRADED }

/** Why a live tunnel was auto-restarted (settings-driven, not a failure). */
enum class AutoRestartReason { SELECTION_CHANGE, SPLIT_TUNNEL_CHANGE }

/** Outcome of a settings-driven auto-restart. */
enum class AutoRestartResult { RESTARTED, KEPT_ON_INVALID }

/** Bounded category for why refreshing a source failed. Never the network exception message. */
enum class RefreshFailureReason {
    NETWORK_ERROR,
    HTTP_ERROR,
    BODY_READ_FAILED,
    RESPONSE_TOO_LARGE,
    NO_CONFIGS,
    NO_VALID_CONFIGS,
}

/** Why a connect-time config re-validation was invalid. */
enum class ConfigInvalidReason { EMPTY, ENGINE_REJECTED }

/** Whether the first confirmed probe of a session came from a fresh connect or a recovery. */
enum class ConfirmVia { FRESH, RECOVERY }

/** Quick-Settings tile action. */
enum class QsTileAction { CONNECT, DISCONNECT, BLOCKED }

/** Why a Quick-Settings tile connect was blocked. `NONE` when the action was not blocked. */
enum class QsBlockedReason { NO_PERMISSION, NO_CONFIG, CONFIG_INVALID, NONE }

/** Which surface a deliberate disconnect came from. */
enum class DisconnectEntryPoint { IN_APP, QS_TILE }
