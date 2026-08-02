package com.onthecrow.deltavpn

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.onthecrow.deltavpn.vpn.AndroidVpnPermissionBridge

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
        // Both bars fully transparent. The default `enableEdgeToEdge()` gives the NAVIGATION bar a
        // `SystemBarStyle.auto` scrim — ~90% white in light mode — which the system draws OVER the app.
        // That band was clipping the connect button's shadow and cutting a hard edge across the bottom
        // of every screen. The app already draws its own gradient scrims at both edges (see
        // ConnectionScreen), so the system one is redundant as well as wrong: ours fades out, is themed,
        // and sits UNDER the buttons instead of on top of them.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
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
