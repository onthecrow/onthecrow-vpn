package com.onthecrow.onthecrowvpn.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.onthecrow.onthecrowvpn.errorreporting.ErrorDomain
import com.onthecrow.onthecrowvpn.errorreporting.ErrorReporter
import com.onthecrow.onthecrowvpn.vpn.domain.InstalledAppsProvider
import com.onthecrow.onthecrowvpn.vpn.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists apps by querying for a LAUNCHER activity rather than calling `getInstalledApplications`.
 *
 * Two reasons. Package visibility: since Android 11 an app sees only what it declares, and the
 * `<queries>` LAUNCHER filter in the manifest grants exactly this query — no `QUERY_ALL_PACKAGES`,
 * which is a Play-restricted permission needing its own justification. And relevance: an app with a
 * launcher icon is precisely the kind a user might route by hand, whereas the bare package list is
 * mostly system components nobody picks.
 */
internal class AndroidInstalledAppsProvider(
    private val errorReporter: ErrorReporter,
) : InstalledAppsProvider {

    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val context = AndroidVpnEnvironment.applicationContext
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            queryLaunchers(packageManager, launcherIntent)
                .asSequence()
                .map { it.activityInfo.applicationInfo }
                // An app with several launcher activities resolves more than once.
                .distinctBy { it.packageName }
                // Never offer ourselves: SplitTunnelResolver forces this package through the tunnel no
                // matter what is picked (the health probe runs in our own :vpn process and has to test
                // the tunnel), so a checkbox here would be a lie.
                .filter { it.packageName != context.packageName }
                .map { InstalledApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrElse {
            errorReporter.report(ErrorDomain.INSTALLED_APPS, it)
            emptyList()
        }
    }

    @Suppress("DEPRECATION") // The flags overload only exists from API 33.
    private fun queryLaunchers(packageManager: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
}
