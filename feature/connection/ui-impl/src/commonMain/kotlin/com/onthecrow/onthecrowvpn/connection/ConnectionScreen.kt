package com.onthecrow.onthecrowvpn.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.onthecrow.onthecrowvpn.connection.model.ConfigRow
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.connection.model.SourceGroup
import com.onthecrow.onthecrowvpn.connection.model.SourceKind
import com.onthecrow.onthecrowvpn.ui.ConnectedGreen
import com.onthecrow.onthecrowvpn.ui.DisconnectedGray
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

/** Carries the error flag through the SnackbarHost so the host can style error snackbars. */
private class ConnectionSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val duration: SnackbarDuration = SnackbarDuration.Short
    override val withDismissAction: Boolean = false
}

@Composable
internal fun ConnectionScreen(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onEvent: (ConnectionEvent) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbar) {
        val notice = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(ConnectionSnackbarVisuals(notice.message, notice.isError))
        onEvent(ConnectionEvent.OnSnackbarShown)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val isError = (data.visuals as? ConnectionSnackbarVisuals)?.isError == true
                Snackbar(
                    containerColor = if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.inverseSurface
                    },
                    contentColor = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.inverseOnSurface
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isError) {
                            ErrorCrossIcon()
                            Spacer(Modifier.size(10.dp))
                        }
                        Text(data.visuals.message)
                    }
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top_bar") { TopBar(onEvent) }
            item(key = "connect_button") {
                ConnectButton(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth()
                        .padding(vertical = 12.dp),
                    onClick = { onEvent(ConnectionEvent.OnConnectClick) },
                )
            }
            if (!state.hasAnySource) {
                item(key = "empty_state") { EmptyState() }
            }
            state.groups.forEach { group ->
                val collapsed = group.sourceId in state.collapsedGroupKeys
                item(key = "header_${group.sourceId}") {
                    GroupHeader(
                        group = group,
                        collapsed = collapsed,
                        isRefreshing = group.sourceId in state.refreshingSourceIds,
                        onEvent = onEvent,
                    )
                }
                if (!collapsed) {
                    items(
                        items = group.rows,
                        key = { "row_${it.ref.sourceId}_${it.ref.configId}" },
                    ) { row ->
                        ConfigRowItem(
                            row = row,
                            swipeToDelete = group.kind == SourceKind.XRAY_LINK,
                            isSelected = state.selected == row.ref,
                            onEvent = onEvent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(onEvent: (ConnectionEvent) -> Unit) {
    // Clipboard must be read here — the composable is the only platform-free access point; the text
    // travels inside the event so the ViewModel stays testable.
    @Suppress("DEPRECATION") // LocalClipboard's suspend API has no common-code text accessor yet.
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Onthecrow VPN",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                AddMenuItem("Subscription ID", ConnectionEvent.AddKind.SUBSCRIPTION_ID) { kind ->
                    menuExpanded = false
                    onEvent(ConnectionEvent.OnAddFromClipboard(kind, clipboard.getText()?.text))
                }
                AddMenuItem("Subscription URL", ConnectionEvent.AddKind.SUBSCRIPTION_URL) { kind ->
                    menuExpanded = false
                    onEvent(ConnectionEvent.OnAddFromClipboard(kind, clipboard.getText()?.text))
                }
                AddMenuItem("Xray URL", ConnectionEvent.AddKind.XRAY_LINK) { kind ->
                    menuExpanded = false
                    onEvent(ConnectionEvent.OnAddFromClipboard(kind, clipboard.getText()?.text))
                }
            }
        }
    }
}

@Composable
private fun AddMenuItem(
    label: String,
    kind: ConnectionEvent.AddKind,
    onPick: (ConnectionEvent.AddKind) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onPick(kind) },
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "No configurations yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Copy a subscription ID, subscription URL or config link,\nthen add it via “+”",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun GroupHeader(
    group: SourceGroup,
    collapsed: Boolean,
    isRefreshing: Boolean,
    onEvent: (ConnectionEvent) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onEvent(ConnectionEvent.OnToggleGroupCollapsed(group.sourceId)) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (collapsed) "▸" else "▾",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                group.subtitle?.takeIf { it.isNotBlank() && it != group.title }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (group.isLoading || isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (group.kind != SourceKind.XRAY_LINK) {
                IconButton(onClick = { onEvent(ConnectionEvent.OnRefreshSourceClick(group.sourceId)) }) {
                    Text(
                        text = "↻",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (group.kind != SourceKind.XRAY_LINK) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Text(
                            text = "⋮",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onEvent(ConnectionEvent.OnDeleteSourceClick(group.sourceId))
                            },
                        )
                    }
                }
            }
        }
        group.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private enum class RevealValue { Hidden, Revealed }

/**
 * Swipe-to-reveal delete: dragging the card left parks it shifted by [REVEAL_WIDTH], exposing a red
 * Delete button behind; deletion happens ONLY by tapping that button (dragging right hides it again).
 * Hand-rolled on AnchoredDraggable because Material3's SwipeToDismissBox deletes on a full swipe (and
 * its alpha build even fired dismiss on plain clicks).
 */
@Composable
private fun ConfigRowItem(
    row: ConfigRow,
    swipeToDelete: Boolean,
    isSelected: Boolean,
    onEvent: (ConnectionEvent) -> Unit,
) {
    if (!swipeToDelete) {
        ConfigItemCard(
            config = row.config,
            isSelected = isSelected,
            onClick = { onEvent(ConnectionEvent.OnConfigSelected(row.ref)) },
        )
        return
    }

    val revealWidthPx = with(LocalDensity.current) { REVEAL_WIDTH.toPx() }
    val dragState = remember(revealWidthPx) {
        AnchoredDraggableState(
            initialValue = RevealValue.Hidden,
            anchors = DraggableAnchors {
                RevealValue.Hidden at 0f
                RevealValue.Revealed at -revealWidthPx
            },
        )
    }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxWidth()) {
        // The delete button lives BEHIND the card and is only reachable once the card is parked left.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(REVEAL_WIDTH)
                    .fillMaxHeight()
                    .clickable { onEvent(ConnectionEvent.OnDeleteSourceClick(row.ref.sourceId)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(dragState.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(dragState, Orientation.Horizontal),
        ) {
            ConfigItemCard(
                config = row.config,
                isSelected = isSelected,
                onClick = {
                    // A tap selects; if the delete button was revealed, also slide back shut.
                    onEvent(ConnectionEvent.OnConfigSelected(row.ref))
                    if (dragState.currentValue == RevealValue.Revealed) {
                        scope.launch { dragState.animateTo(RevealValue.Hidden) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ConfigItemCard(
    config: RemoteConfig,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // The selected tint must stay OPAQUE: a translucent green let the red delete backplate (behind
    // swipeable rows) bleed through. Composite the green over the normal card colour instead.
    val container =
        if (isSelected) {
            ConnectedGreen.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surfaceContainer)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleFor(config),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(12.dp))
            SelectionIndicator(isSelected = isSelected)
        }
    }
}

@Composable
private fun SelectionIndicator(isSelected: Boolean) {
    if (isSelected) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(ConnectedGreen),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
private fun ErrorCrossIcon() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✕",
            color = MaterialTheme.colorScheme.onError,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConnectButton(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val connected = state.connectionStatus is ConnectionStatus.Connected
    val color = if (connected) ConnectedGreen else DisconnectedGray
    Button(
        onClick = onClick,
        enabled = state.canConnect || connected,
        modifier = modifier.size(132.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = DisconnectedGray.copy(alpha = 0.45f),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = when (state.connectionStatus) {
                    is ConnectionStatus.Connected -> "Disconnect"
                    is ConnectionStatus.Disconnecting -> "Disconnecting"
                    is ConnectionStatus.Connecting,
                    is ConnectionStatus.PreparingPermission -> "Connecting"
                    else -> "Connect"
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

private val REVEAL_WIDTH = 88.dp

private fun subtitleFor(config: RemoteConfig): String {
    val scheme = config.url.substringBefore("://", missingDelimiterValue = "")
    return listOfNotNull(
        config.location?.takeIf { it.isNotBlank() }?.uppercase(),
        scheme.takeIf { it.isNotBlank() },
        config.type?.takeIf { it.isNotBlank() && !it.equals(scheme, ignoreCase = true) },
    ).joinToString(" · ")
}
