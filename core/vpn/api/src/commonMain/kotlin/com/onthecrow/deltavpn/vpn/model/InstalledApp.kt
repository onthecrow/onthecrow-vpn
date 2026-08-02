package com.onthecrow.deltavpn.vpn.model

/**
 * One user-visible installed app.
 *
 * Carries no icon: the icon is a platform drawable and a UI concern, resolved per-platform where the
 * row is drawn. Keeping it out of here lets this model stay in the pure-Kotlin api module.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)
