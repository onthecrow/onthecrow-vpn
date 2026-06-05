package com.onthecrow.onthecrowvpn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
        requestIgnoreBatteryOptimizationsIfNeeded()

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

    // Doze defers network for non-exempt apps, which kills the VPN's server connection while the
    // screen is off. Asking the user to exempt us from battery optimization keeps the tunnel alive
    // through Doze. The VPN still works without it, so a denial is non-fatal.
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
