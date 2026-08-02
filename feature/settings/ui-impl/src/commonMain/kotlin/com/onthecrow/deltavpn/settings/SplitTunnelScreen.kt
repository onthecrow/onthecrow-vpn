package com.onthecrow.deltavpn.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onthecrow.deltavpn.vpn.model.InstalledApp
import com.onthecrow.deltavpn.vpn.model.SplitTunnelMode

@Composable
internal fun SplitTunnelScreen(
    state: SplitTunnelState,
    modifier: Modifier = Modifier,
    onEvent: (SplitTunnelEvent) -> Unit,
) {
    // Surface, not Modifier.background: it is what publishes LocalContentColor (here onBackground),
    // without which every Text lacking an explicit colour falls back to black.
    Surface(
        modifier = modifier
            .fillMaxSize()
            // Top and sides only. The list has to scroll UNDER the navigation bar, so its bottom
            // inset is handled by contentPadding instead — padding it here would leave a band of
            // background colour below the content, which reads as a filled navigation bar.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            ),
        color = MaterialTheme.colorScheme.background,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(onBack = { onEvent(SplitTunnelEvent.OnBackClick) })

            ModeSelector(
                mode = state.mode,
                onModeChange = { onEvent(SplitTunnelEvent.OnModeChanged(it)) },
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = modeExplanation(state.mode),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.warnEmptyAllowlist) {
                Text(
                    text = "Nothing is selected, so no app would use the VPN.",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SearchField(
                query = state.query,
                onQueryChange = { onEvent(SplitTunnelEvent.OnQueryChanged(it)) },
                onClear = { onEvent(SplitTunnelEvent.OnQueryCleared) },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.visibleApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.apps.isEmpty()) "No apps found" else "Nothing matches “${state.query.trim()}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Bottom room is reserved permanently rather than only while the Apply button is
                    // up, so the list never reflows underneath the user as it slides in and out.
                    //
                    // safeDrawing rather than navigationBars: its bottom is the larger of the gesture
                    // bar and the keyboard, so searching does not bury the last rows behind the IME,
                    // and it animates as the keyboard opens.
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = ApplyBarHeight +
                            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.visibleApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in state.selectedPackages,
                            onToggle = { onEvent(SplitTunnelEvent.OnAppToggled(app.packageName)) },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.hasChanges,
            modifier = Modifier.align(Alignment.BottomCenter),
            // Travels its own full height, so it reads as coming from off-screen rather than fading
            // in place. Nothing pending, nothing on screen.
            enter = slideInVertically { height -> height } + fadeIn(),
            exit = slideOutVertically { height -> height } + fadeOut(),
        ) {
            Button(
                onClick = { onEvent(SplitTunnelEvent.OnApplyClick) },
                // Second line of defence only — the resolver already refuses to build a self-only
                // allowlist. Blocking it here is what makes the red warning above actionable rather
                // than something the user can simply tap past.
                enabled = !state.warnEmptyAllowlist,
                // The screen deliberately does not consume the bottom inset at its root (the list has
                // to scroll under the navigation bar), so the button has to claim it itself — and via
                // safeDrawing, or it would sit behind the keyboard the moment the user searches.
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 20.dp),
            ) {
                Text("Apply", style = MaterialTheme.typography.labelLarge)
            }
        }
      }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Text(
                text = "‹",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = "Split tunneling",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ModeSelector(
    mode: SplitTunnelMode,
    onModeChange: (SplitTunnelMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(SplitTunnelMode.OFF, SplitTunnelMode.BYPASS_SELECTED, SplitTunnelMode.ONLY_SELECTED)
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onModeChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(modeLabel(entry), maxLines = 1)
            }
        }
    }
}

private fun modeLabel(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.OFF -> "Off"
    SplitTunnelMode.BYPASS_SELECTED -> "Exclude"
    SplitTunnelMode.ONLY_SELECTED -> "Include"
}

// "Exclude"/"Include" alone don't say what is being excluded from what — the sentence underneath is
// what makes the choice unambiguous, so the two must always be shown together.
private fun modeExplanation(mode: SplitTunnelMode): String = when (mode) {
    SplitTunnelMode.OFF ->
        "Every app uses the VPN. Your selection is remembered but not applied."
    SplitTunnelMode.BYPASS_SELECTED ->
        "Selected apps bypass the VPN. Everything else goes through it."
    SplitTunnelMode.ONLY_SELECTED ->
        "Only selected apps go through the VPN. Everything else bypasses it."
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(SettingsSymbols.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(SettingsSymbols.Close, contentDescription = "Clear search")
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Height reserved under the list for the Apply button, so the last row is never hidden by it. */
private val ApplyBarHeight = 88.dp
