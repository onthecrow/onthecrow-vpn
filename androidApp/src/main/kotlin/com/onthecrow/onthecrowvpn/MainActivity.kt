package com.onthecrow.onthecrowvpn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.onthecrow.onthecrowvpn.vpn.AndroidVpnPermissionBridge

class MainActivity : ComponentActivity() {
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        AndroidVpnPermissionBridge.onPermissionResult(result.resultCode)
    }

    // POST_NOTIFICATIONS is a runtime permission on Android 13+; without it the foreground-service
    // VPN notification is silently suppressed. The VPN works either way, so the result is a no-op.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not — the VPN runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidVpnPermissionBridge.bind(vpnPermissionLauncher::launch)
        requestNotificationPermissionIfNeeded()

        setContent {
            App()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
