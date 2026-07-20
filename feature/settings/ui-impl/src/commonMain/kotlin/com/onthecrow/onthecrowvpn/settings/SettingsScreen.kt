package com.onthecrow.onthecrowvpn.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    state: SettingsState,
    modifier: Modifier = Modifier,
    onEvent: (SettingsEvent) -> Unit,
) {
    // A Surface, not a Modifier.background: only Surface publishes LocalContentColor. Painting the
    // background by hand leaves it at its default (black), which is why every Text without an
    // explicit colour used to render dark on a dark theme.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onEvent(SettingsEvent.OnBackClick) }) {
                    Text(
                        text = "‹",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            SectionLabel("Notifications")
            SwitchRow(
                title = "Allow notifications under VPN",
                subtitle = "Route Google Play Services directly so push (FCM) still arrives, even in Doze.",
                checked = state.excludePushServices,
                onCheckedChange = { onEvent(SettingsEvent.OnExcludePushChanged(it)) },
            )

            // Hidden rather than disabled where per-app routing doesn't exist: an entry that opens an
            // empty list with a working mode selector would let the user "configure" something inert.
            if (splitTunnelSupported) {
                Spacer(Modifier.size(20.dp))
                SectionLabel("Split tunneling")
                NavigationRow(
                    title = "Per-app routing",
                    subtitle = state.splitTunnelSummary,
                    onClick = { onEvent(SettingsEvent.OnSplitTunnelClick) },
                )
            }

            val openUrl = rememberUrlOpener()
            if (openUrl != null) {
                Spacer(Modifier.size(20.dp))
                NavigationRow(
                    title = "Privacy policy",
                    subtitle = "What the app stores, and what it never sends",
                    onClick = { openUrl(PRIVACY_POLICY_URL) },
                )
            }

            // Pushes the version to the bottom of whatever space is left.
            Spacer(Modifier.weight(1f))
            VersionFooter()
        }
    }
}

/**
 * Version, and — held down — the diagnostic log.
 *
 * Deliberately a long-press on an unlabelled row rather than a visible "send logs" button: it is for
 * the rare occasion someone is asked for diagnostics, not something to invite people to poke at. The
 * share sheet is the only way the log ever leaves the device.
 */
@Composable
private fun VersionFooter() {
    val version = appVersionLabel()
    if (version.isEmpty()) return
    val shareLogs = rememberLogSharer()
    Text(
        text = "Version $version",
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (shareLogs == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = shareLogs,
                    )
                },
            )
            .padding(vertical = 20.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The card every settings row sits in.
 *
 * A Surface rather than a Modifier.background, because Surface is what publishes LocalContentColor —
 * here `onSurface`, so a Text inside needs no explicit colour and cannot end up black by default.
 */
@Composable
private fun RowContainer(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    RowContainer(onClick = onClick) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = SettingsSymbols.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A Material 3 list subheader.
 *
 * `titleSmall` is the role M3 gives section headers in a list: 14sp at Medium weight, so it reads as
 * a heading against 14sp Normal body copy without competing with the 16sp row titles. Sentence case,
 * not caps — small all-caps is a Material 2 habit and hurts legibility at this size.
 *
 * Coloured `onBackground` because that is the surface it sits on (the rows have their own container
 * colour). M3's own subheader spec reaches for `onSurfaceVariant`, but that is the muted role used
 * for supporting text, and it made these read as dimmer than the content they introduce.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    RowContainer(onClick = { onCheckedChange(!checked) }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
