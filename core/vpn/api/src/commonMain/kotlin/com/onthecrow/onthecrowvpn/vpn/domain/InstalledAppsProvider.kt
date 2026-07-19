package com.onthecrow.onthecrowvpn.vpn.domain

import com.onthecrow.onthecrowvpn.vpn.model.InstalledApp

/**
 * The apps the user can route individually.
 *
 * Android-only in practice — every other platform returns an empty list, so the split-tunnel screen
 * simply has nothing to offer there. Deliberately a suspend call: on Android this reads the package
 * manager, which is slow enough to matter on a device with a few hundred apps.
 */
interface InstalledAppsProvider {
    suspend fun installedApps(): List<InstalledApp>
}
