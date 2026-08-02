package com.onthecrow.deltavpn.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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

            // No section headers: with this few rows they label the obvious and cost more vertical
            // space than they earn. Rows are separated by [RowSpacing] alone.
            SwitchRow(
                title = "Allow notifications under VPN",
                // Says the quiet part: this traffic leaves the tunnel. The old copy sold only the
                // benefit ("push still arrives") and never told the user what it costs.
                subtitle = "Send Google Play Services outside the VPN so notifications keep arriving.",
                checked = state.excludePushServices,
                onCheckedChange = { onEvent(SettingsEvent.OnExcludePushChanged(it)) },
            )

            // Hidden rather than disabled where per-app routing doesn't exist: an entry that opens an
            // empty list with a working mode selector would let the user "configure" something inert.
            if (splitTunnelSupported) {
                Spacer(Modifier.size(RowSpacing))
                NavigationRow(
                    title = "Per-app routing",
                    subtitle = state.splitTunnelSummary,
                    onClick = { onEvent(SettingsEvent.OnSplitTunnelClick) },
                )
            }

            // Hidden where nothing reads it (iOS/desktop don't run this recovery ladder), for the same
            // reason as the split-tunnel entry above.
            if (aggressiveKeepaliveSupported) {
                Spacer(Modifier.size(RowSpacing))
                var showAggressiveKeepaliveInfo by rememberSaveable { mutableStateOf(false) }
                SwitchRow(
                    title = "Aggressive keepalive",
                    subtitle = "Restores the connection faster, but may reconnect more often than needed.",
                    checked = state.aggressiveKeepalive,
                    onCheckedChange = { onEvent(SettingsEvent.OnAggressiveKeepaliveChanged(it)) },
                    onInfoClick = { showAggressiveKeepaliveInfo = true },
                )
                if (showAggressiveKeepaliveInfo) {
                    AggressiveKeepaliveInfoDialog(onDismiss = { showAggressiveKeepaliveInfo = false })
                }
            }

            val openUrl = rememberUrlOpener()
            if (openUrl != null) {
                // A wider gap than between the rows above: this is a document, not a setting, and the
                // extra space is what says so now that the section headers are gone.
                Spacer(Modifier.size(GroupSpacing))
                NavigationRow(
                    title = "Privacy policy",
                    subtitle = "What the app stores, and what it never sends",
                    onClick = { openUrl(PRIVACY_POLICY_URL) },
                )
            }

            // Pushes the version to the bottom of whatever space is left.
            Spacer(Modifier.weight(1f))
            VersionFooter(onLogShared = { onEvent(SettingsEvent.OnLogShared) })
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
private fun VersionFooter(onLogShared: () -> Unit) {
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
                        onLongClick = {
                            onLogShared()
                            shareLogs()
                        },
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

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // When non-null, an info affordance follows the title. For a setting whose trade-off does not fit a
    // one-line subtitle: the row stays short and the detail lives one tap away, rather than a paragraph
    // nobody reads sitting under every switch.
    onInfoClick: (() -> Unit)? = null,
) {
    RowContainer(onClick = { onCheckedChange(!checked) }) {
        Column(modifier = Modifier.weight(1f)) {
            if (onInfoClick == null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(INFO_ICON_GAP))
                    // The touch target is 48dp, but it must not push the row around: `size` is what the
                    // Row measures (the glyph), `wrapContentSize(unbounded)` lets the child exceed that
                    // constraint, and `requiredSize` then forces the real 48dp for the ripple and the
                    // hit area. Sizing the Box at 48dp directly made the title line 48dp tall, which
                    // added ~12dp above the title and below it — this row has to keep the exact vertical
                    // rhythm of every other settings row.
                    Box(
                        modifier = Modifier
                            .size(INFO_ICON_SIZE)
                            .wrapContentSize(align = Alignment.Center, unbounded = true)
                            .requiredSize(INFO_TOUCH_TARGET)
                            .clip(CircleShape)
                            .clickable(onClick = onInfoClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SettingsSymbols.Info,
                            contentDescription = "About $title",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(INFO_ICON_SIZE),
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Between adjacent setting rows. */
private val RowSpacing = 20.dp

/** Between the settings and the trailing document link — a wider beat that stands in for a divider. */
private val GroupSpacing = 64.dp

private val INFO_ICON_GAP = 8.dp
private val INFO_ICON_SIZE = 20.dp
private val INFO_TOUCH_TARGET = 48.dp

/**
 * The long-form explanation behind the "Aggressive keepalive" info icon.
 *
 * Written for someone with no idea what a tunnel, a probe or a network handover is: it says what the
 * app does by default, what changes, and — the part that decides it for them — what they gain and what
 * it costs, in that order. No durations, no thresholds; the numbers belong in the log, not in a dialog
 * that has to survive a translation and a year of tuning.
 */
@Composable
private fun AggressiveKeepaliveInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
        title = { Text("Aggressive keepalive") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Connections hiccup all the time — when you walk out of Wi-Fi range, " +
                        "switch to mobile data, or your signal dips. Most of these fix themselves in " +
                        "a moment, so the app waits a little before rebuilding the connection.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Turn this on and the app stops waiting: at the first sign of trouble it " +
                        "rebuilds the connection right away.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                InfoDialogPoint(
                    label = "What you gain",
                    body = "When your connection really has dropped, the VPN comes back sooner " +
                        "instead of leaving you waiting.",
                )
                InfoDialogPoint(
                    label = "What it costs",
                    body = "On a weak or busy network the app will sometimes rebuild a connection " +
                        "that would have recovered on its own. Each rebuild briefly interrupts " +
                        "whatever you are doing and uses a little more battery.",
                )
                Text(
                    text = "If your connection is usually fine, leave this off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

@Composable
private fun InfoDialogPoint(label: String, body: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
