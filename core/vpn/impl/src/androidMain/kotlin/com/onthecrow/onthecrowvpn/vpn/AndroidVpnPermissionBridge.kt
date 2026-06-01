package com.onthecrow.onthecrowvpn.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AndroidVpnPermissionBridge {
    private val mutex = Mutex()
    private var launcher: ((Intent) -> Unit)? = null
    private var pendingResult: CompletableDeferred<VpnPermissionResult>? = null

    fun bind(launcher: (Intent) -> Unit) {
        this.launcher = launcher
    }

    suspend fun requestPermission(): VpnPermissionResult {
        val context = AndroidVpnEnvironment.applicationContext
        val permissionIntent = VpnService.prepare(context)
            ?: return VpnPermissionResult.Granted

        val activeLauncher = launcher
            ?: return VpnPermissionResult.Denied("VPN permission requester is not attached")

        val deferred = mutex.withLock {
            pendingResult?.complete(VpnPermissionResult.Denied("VPN permission request was superseded"))
            CompletableDeferred<VpnPermissionResult>().also { pendingResult = it }
        }
        activeLauncher(permissionIntent)
        return deferred.await()
    }

    fun onPermissionResult(resultCode: Int) {
        val result = if (resultCode == Activity.RESULT_OK) {
            VpnPermissionResult.Granted
        } else {
            VpnPermissionResult.Denied("VPN permission was denied")
        }
        pendingResult?.complete(result)
        pendingResult = null
    }
}
