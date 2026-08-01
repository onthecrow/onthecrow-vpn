package com.onthecrow.onthecrowvpn.analytics.events

/**
 * Wire encoding for events: enum -> lowercased constant name, and raw counts/durations -> coarse
 * buckets. Bucketing happens **on-device before logging** on purpose — a raw count or a millisecond
 * duration is a re-identification fingerprint, so no exact number ever reaches an event parameter.
 */
internal fun Enum<*>.raw(): String = name.lowercase()

/** Total configured sources after the action: `0` / `1` / `2-5` / `6+`. */
internal fun sourceCountBucket(count: Int): String = when {
    count <= 0 -> "0"
    count == 1 -> "1"
    count <= 5 -> "2-5"
    else -> "6+"
}

/** Split-tunnel selected-app count: `0` / `1-5` / `6+`. Never the exact count or the package names. */
internal fun appCountBucket(count: Int): String = when {
    count <= 0 -> "0"
    count <= 5 -> "1-5"
    else -> "6+"
}

/** Connected-session length: `lt1m` / `1-10m` / `10-60m` / `gt60m`. */
internal fun sessionDurationBucket(durationMs: Long): String = when {
    durationMs < 60_000 -> "lt1m"
    durationMs < 10 * 60_000 -> "1-10m"
    durationMs <= 60 * 60_000 -> "10-60m"
    else -> "gt60m"
}

/** Short operation duration: `lt1s` / `1-5s` / `5-15s` / `15-30s` / `gt30s`. */
internal fun shortDurationBucket(durationMs: Long): String = when {
    durationMs < 1_000 -> "lt1s"
    durationMs < 5_000 -> "1-5s"
    durationMs < 15_000 -> "5-15s"
    durationMs <= 30_000 -> "15-30s"
    else -> "gt30s"
}

/** Recovery attempt count (uncapped counter): `1` / `2` / `3-5` / `6-10` / `11+`. */
internal fun attemptsBucket(attempts: Int): String = when {
    attempts <= 1 -> "1"
    attempts == 2 -> "2"
    attempts <= 5 -> "3-5"
    attempts <= 10 -> "6-10"
    else -> "11+"
}

/** Count of confirmed-DEAD keepalive probes in a session: `0` / `1-3` / `4-10` / `>10`. */
internal fun deadProbeBucket(count: Int): String = when {
    count <= 0 -> "0"
    count <= 3 -> "1-3"
    count <= 10 -> "4-10"
    else -> ">10"
}

/** Count of INCONCLUSIVE (Doze-frozen) keepalive probes in a session: `0` / `1-5` / `6-20` / `>20`. */
internal fun inconclusiveProbeBucket(count: Int): String = when {
    count <= 0 -> "0"
    count <= 5 -> "1-5"
    count <= 20 -> "6-20"
    else -> ">20"
}
