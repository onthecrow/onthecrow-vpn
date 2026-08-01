package com.onthecrow.onthecrowvpn.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onthecrow.onthecrowvpn.ui.Accent
import com.onthecrow.onthecrowvpn.ui.OnthecrowTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Full-screen prominent-disclosure + affirmative-consent step, shown BEFORE the system
 * `VpnService.prepare()` dialog on the first connect (see [ConnectionViewModel.handleConnectClick]).
 *
 * This is Google Play's VpnService-policy requirement: the disclosure must appear in the normal usage
 * flow — not a settings entry, not the store listing, not the OS VPN dialog — and the four statements
 * below (device-level tunnel · user-supplied server · local-only config · Firebase diagnostics) must
 * be legible on camera in one take, because the Play Console VPN declaration needs a demo video of
 * exactly this screen. Kept text-heavy and high-contrast for that reason.
 *
 * Rendered as a full-bleed [Dialog] so it covers the app and a system back press counts as a decline.
 */
@Composable
internal fun VpnConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Full-screen: a tap can't land "outside", and the choice must be explicit either way.
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Before you connect",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "OnthecrowVPN needs your consent to set up a VPN on this device. " +
                            "Here is exactly what that means:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))

                    // "All traffic" was the wording here, and it is not true out of the box: the
                    // push-notification bypass ships ON, so Google Play Services goes around the tunnel
                    // by default. This screen is the Play prominent disclosure and is filmed for the
                    // Console declaration — an absolute the default configuration falsifies is exactly
                    // the kind of claim that gets a VPN app rejected, and it also breaks this repo's own
                    // rule that traffic must never leave the tunnel silently.
                    DisclosureItem(
                        title = "Device-level VPN",
                        body = "The app creates a system VPN and routes your device's network traffic " +
                            "through an encrypted tunnel. Google Play Services is sent directly, " +
                            "outside the tunnel, so notifications keep arriving — you can change that, " +
                            "and choose which apps use the VPN, in Settings.",
                    )
                    DisclosureItem(
                        title = "Your own server",
                        body = "Traffic goes only to the server you configure. The developer operates " +
                            "no servers and cannot see your traffic.",
                    )
                    DisclosureItem(
                        title = "Stored on your device",
                        body = "Your server configurations are stored locally on this device and are " +
                            "not uploaded anywhere.",
                    )
                    DisclosureItem(
                        title = "Crash & analytics data",
                        body = "Crash reports and basic analytics are sent to Firebase to keep the app " +
                            "reliable. No browsing activity is collected.",
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "By continuing you agree to the above. See the Privacy Policy in " +
                            "Settings for full details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        text = "Agree & Continue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Not now",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclosureItem(title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Small accent bullet, vertically nudged to sit on the title's cap height.
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(Accent),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun VpnConsentDialogPreview() {
    OnthecrowTheme(darkTheme = true) {
        // Preview renders the dialog content directly (a Dialog won't compose in the preview surface).
        Surface(color = MaterialTheme.colorScheme.background) {
            VpnConsentDialog(onAccept = {}, onDecline = {})
        }
    }
}
