package com.onthecrow.onthecrowvpn.connection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.onthecrow.onthecrowvpn.connection.model.ConfigRef
import com.onthecrow.onthecrowvpn.connection.model.ConfigRow
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.connection.model.SourceGroup
import com.onthecrow.onthecrowvpn.connection.model.SourceKind
import com.onthecrow.onthecrowvpn.ui.Accent
import com.onthecrow.onthecrowvpn.ui.OnthecrowTheme
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

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

    // Play-mandated VPN disclosure, shown on the first connect before the system prepare() dialog.
    if (state.showConsent) {
        VpnConsentDialog(
            onAccept = { onEvent(ConnectionEvent.OnConsentAccepted) },
            onDecline = { onEvent(ConnectionEvent.OnConsentDeclined) },
        )
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
        val listState = rememberLazyListState()
        // On a cold start, jump (no animation) to the group holding the already-selected config so the
        // user lands on what they'll connect to. `remember` (not saveable) resets only on a fresh
        // composition — so this fires on app open, not when resuming from the background. With
        // reverseLayout, scrollToItem brings the group to the bottom of the viewport (above Connect).
        var didInitialScroll by remember { mutableStateOf(false) }
        LaunchedEffect(state.selected, state.groups) {
            if (!didInitialScroll && state.selected != null) {
                val groupIndex = state.groups.indexOfFirst { g -> g.rows.any { it.ref == state.selected } }
                if (groupIndex >= 0) {
                    listState.scrollToItem(groupIndex)
                    didInitialScroll = true
                }
            }
        }

        // The list scrolls UNDER the bottom action bar (settings FAB · connect · add FAB) AND under the
        // transparent system bars. The container takes only horizontal (cutout) insets; the status-bar
        // and nav-bar insets become list/bar padding so content can scroll behind both bars.
        val layoutDirection = LocalLayoutDirection.current
        val navBarInset = contentPadding.calculateBottomPadding()
        val statusBarInset = contentPadding.calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
        ) {
            val density = LocalDensity.current
            // Measured height of the whole bottom bar INCLUDING its vertical padding and the nav-bar
            // inset it carries; used as the list's bottom inset so the last rows scroll clear of the bar.
            var bottomBarHeight by remember { mutableStateOf(0.dp) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                // reverseLayout: the list RESTS at the bottom (item 0 just above the connect button) and
                // scrolls UP — so on open the start point is at the bottom, not the top. Alignment.Bottom
                // makes short content hug the bottom too. The status-bar inset is in contentPadding (not
                // the Box) so items scroll under the transparent status bar; the bottom inset keeps the
                // last rows clear of the connect button.
                reverseLayout = true,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = statusBarInset + 8.dp,
                    bottom = bottomBarHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                if (!state.hasAnySource) {
                    item(key = "empty_state") { EmptyState() }
                }
                // Withhold groups until the persisted collapsed state is resolved, so each group's
                // first composition already reflects it — no collapse animation on launch.
                if (state.collapsedLoaded) {
                    items(
                        items = state.groups,
                        key = { "group_${it.sourceId}" },
                    ) { group ->
                        GroupCard(
                            group = group,
                            collapsed = group.sourceId in state.collapsedGroupKeys,
                            isRefreshing = group.sourceId in state.refreshingSourceIds,
                            selected = state.selected,
                            onEvent = onEvent,
                        )
                    }
                }
            }

            // Edge scrims under the transparent system bars: the app background fades from ~0.8 alpha at
            // each screen edge to transparent over 2× the bar height, so the status-bar / nav-bar content
            // stays legible over the list scrolling behind them. Drawn above the list, below the buttons.
            val scrimColor = MaterialTheme.colorScheme.background
            if (statusBarInset > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(statusBarInset * 2)
                        .background(
                            Brush.verticalGradient(listOf(scrimColor.copy(alpha = 0.8f), Color.Transparent)),
                        ),
                )
            }
            if (navBarInset > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(navBarInset * 2)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, scrimColor.copy(alpha = 0.8f))),
                        ),
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // onSizeChanged BEFORE padding → reports the full footprint (bar + vertical padding +
                    // nav-bar inset), which is exactly the list's bottom contentPadding.
                    .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } }
                    // Bottom padding carries the nav-bar inset so the buttons sit above the transparent
                    // nav bar while the list still scrolls under it.
                    .padding(top = 12.dp, bottom = 12.dp + navBarInset),
                horizontalArrangement = Arrangement.spacedBy(BottomBarSpacing, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsFab(onClick = { onEvent(ConnectionEvent.OnSettingsClick) })
                ConnectButton(
                    state = state,
                    // Draw LAST among the bar's children (zIndex changes draw order, not layout) so the
                    // Nimbus glow renders over the FABs. The bar itself is the last child of the root Box,
                    // so the glow already sits above the list and the edge scrims.
                    modifier = Modifier.zIndex(1f),
                    onClick = { onEvent(ConnectionEvent.OnConnectClick) },
                )
                AddConfigFab(onEvent = onEvent)
            }
        }
    }
}

/** Neutral, circular elevated FAB that opens the settings screen. */
@Composable
private fun SettingsFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(imageVector = MaterialSymbols.Settings, contentDescription = "Settings")
    }
}

/** Circular elevated FAB that opens the "add from clipboard" menu (subscription id / url / xray link). */
@Composable
private fun AddConfigFab(onEvent: (ConnectionEvent) -> Unit) {
    // Clipboard must be read here — the composable is the only platform-free access point; the text
    // travels inside the event so the ViewModel stays testable.
    @Suppress("DEPRECATION") // LocalClipboard's suspend API has no common-code text accessor yet.
    val clipboard = LocalClipboardManager.current
    var menuExpanded by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { BottomBarSpacing.roundToPx() }

    Box {
        FloatingActionButton(
            onClick = { menuExpanded = true },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Same tone as the settings FAB opposite: the two flank the connect button and should read
            // as one pair, so neither competes with it for attention.
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Icon(imageVector = MaterialSymbols.Add, contentDescription = "Add configuration")
        }
        if (menuExpanded) {
            // Custom popup so we can centre it on the FAB and place it exactly [BottomBarSpacing] above
            // (matching the FAB↔Connect gap) — DropdownMenu can't be positioned that precisely.
            Popup(
                popupPositionProvider = remember(gapPx) { CenteredAbovePopupPositionProvider(gapPx) },
                onDismissRequest = { menuExpanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.width(IntrinsicSize.Max)) {
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
    }
}

/**
 * Places the popup so its horizontal centre matches the anchor (the add FAB) centre, sitting [gapPx]
 * above it — clamped to the window. Used to keep the add menu centred on the "+" with the same gap as
 * between the FAB and the connect button.
 */
private class CenteredAbovePopupPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = anchorBounds.top - popupContentSize.height - gapPx
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(x.coerceIn(0, maxX), y.coerceAtLeast(0))
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

/**
 * One rounded container per group: a header strip (chevron, 2-line title, refresh, overflow) and,
 * when expanded, the config rows flush beneath it — slightly darker than the container and separated
 * by horizontal dividers, no gaps.
 */
@Composable
private fun GroupCard(
    group: SourceGroup,
    collapsed: Boolean,
    isRefreshing: Boolean,
    selected: ConfigRef?,
    onEvent: (ConnectionEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        GroupHeader(
            group = group,
            collapsed = collapsed,
            isRefreshing = isRefreshing,
            onEvent = onEvent,
        )
        AnimatedVisibility(visible = !collapsed) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.rows.forEach { row ->
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    ConfigElement(
                        row = row,
                        isSelected = selected == row.ref,
                        swipeToDelete = group.kind == SourceKind.XRAY_LINK,
                        onEvent = onEvent,
                    )
                }
            }
        }
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
    // Subscription groups are deletable (the whole group); the "Xray links" group is not (links are
    // deleted individually). Deletable groups get a long-press → Delete menu in addition to the ⋮ button.
    val deletable = group.kind != SourceKind.XRAY_LINK
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Same header height for every group, including "Xray links" which has no action icons.
                .heightIn(min = 60.dp)
                .combinedClickable(
                    onClick = { onEvent(ConnectionEvent.OnSetGroupCollapsed(group.sourceId, !collapsed)) },
                    onLongClick = if (deletable) {
                        { menuExpanded = true }
                    } else {
                        null
                    },
                )
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (collapsed) MaterialSymbols.ArrowDropDown else MaterialSymbols.ArrowDropUp,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
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
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else if (group.kind != SourceKind.XRAY_LINK) {
                IconButton(onClick = { onEvent(ConnectionEvent.OnRefreshSourceClick(group.sourceId)) }) {
                    Icon(
                        imageVector = MaterialSymbols.Autorenew,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (group.kind != SourceKind.XRAY_LINK) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = MaterialSymbols.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                modifier = Modifier.padding(start = 38.dp, end = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private enum class RevealValue { Hidden, Revealed }

/**
 * A flush config element inside a [GroupCard] — no card, no gaps; selection is shown by an opaque
 * green background (no separate indicator). Xray links additionally support swipe-to-reveal delete.
 */
@Composable
private fun ConfigElement(
    row: ConfigRow,
    isSelected: Boolean,
    swipeToDelete: Boolean,
    onEvent: (ConnectionEvent) -> Unit,
) {
    val background = if (isSelected) {
        // Opaque so the red delete backplate behind a swiped row never bleeds through the tint.
        Accent.copy(alpha = 0.20f).compositeOver(MaterialTheme.colorScheme.surfaceContainer)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    if (!swipeToDelete) {
        ElementRow(
            config = row.config,
            background = background,
            isSelected = isSelected,
            onClick = { onEvent(ConnectionEvent.OnConfigSelected(row.ref)) },
        )
        return
    }

    // Swipe-to-reveal delete: dragging left parks the row by REVEAL_WIDTH, exposing a red Delete
    // button behind; deletion happens only by tapping that button. Hand-rolled on AnchoredDraggable —
    // Material3's SwipeToDismissBox deletes on a full swipe (and even fired on plain clicks).
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
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
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
            ElementRow(
                config = row.config,
                background = background,
                isSelected = isSelected,
                onClick = {
                    onEvent(ConnectionEvent.OnConfigSelected(row.ref))
                    if (dragState.currentValue == RevealValue.Revealed) {
                        scope.launch { dragState.animateTo(RevealValue.Hidden) }
                    }
                },
                // Alternative to swipe: long-press → Delete menu, for the same per-link removal.
                onLongClick = { menuExpanded = true },
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onEvent(ConnectionEvent.OnDeleteSourceClick(row.ref.sourceId))
                    },
                )
            }
        }
    }
}

@Composable
private fun ElementRow(
    config: RemoteConfig,
    background: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            // Active-config marker: a square-cut accent bar down the row's full height, flush against
            // the left edge. Drawn rather than laid out, because the row's height comes from its
            // content — a child with fillMaxHeight would resolve against the LazyColumn's unbounded
            // height constraint instead.
            .then(
                if (isSelected) {
                    Modifier.drawBehind {
                        drawRect(color = Accent, size = Size(ACTIVE_MARKER_WIDTH.toPx(), size.height))
                    }
                } else {
                    Modifier
                },
            )
            .then(clickModifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
        }
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
    // Idle, the button sits in the same surface tone as the two FABs flanking it — the bottom bar reads
    // as one group, and Accent is reserved for "the tunnel is actually up".
    val idleColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val enabled = state.canConnect || connected
    val onIdle = MaterialTheme.colorScheme.onSurface
    val onAccent = MaterialTheme.colorScheme.onPrimary

    // The fill and the halo are SEQUENCED, not simultaneous. Connecting floods the button from the
    // centre outwards and only then lights the halo; disconnecting fades the halo first and only then
    // drains the fill back to the centre. Played together the two motions competed and read as noise;
    // one after the other reads as cause and effect.
    //
    // Initial value, not an animation, when the screen opens on an already-live tunnel — reopening the
    // app should show the finished state, not replay the connect sequence.
    val fill = remember { Animatable(if (connected) 1f else 0f) }
    var haloActive by remember { mutableStateOf(connected) }
    LaunchedEffect(connected) {
        if (connected) {
            fill.animateTo(1f, tween(FillDurationMillis, easing = FastOutSlowInEasing))
            haloActive = true
        } else {
            haloActive = false
            // Hold until the glow has actually gone: NimbusActivationMillis is its ramp-out.
            delay(NimbusActivationMillis.toLong())
            fill.animateTo(0f, tween(FillDurationMillis, easing = FastOutSlowInEasing))
        }
    }
    // A toggle mid-sequence just re-targets the same Animatable, so the fill carries on from wherever
    // it is instead of jumping.

    Button(
        onClick = onClick,
        enabled = enabled,
        // Nimbus glow ring marks an ACTIVE tunnel. It draws outside the button's bounds, so it must
        // come before any sizing/clipping and nothing above may clip (the bottom bar and root Box don't).
        //
        modifier = modifier
            .nimbusDefaults(active = haloActive, shape = CircleShape)
            .size(132.dp),
        shape = CircleShape,
        // Zero padding so the fill below can span the whole button rather than an inset content box.
        contentPadding = PaddingValues(0.dp),
        // The container must stay FULLY OPAQUE, including when disabled — hence compositeOver rather
        // than a plain alpha. Android tessellates an elevation shadow into a polygon and the Surface
        // paints it in front of anything we draw behind the button, so the moment the container lets
        // light through, that polygon shows through the face of the button. Painting the fill
        // underneath a transparent container is what exposed it; the fill now goes ABOVE the
        // container, in the content layer.
        colors = ButtonDefaults.buttonColors(
            containerColor = idleColor,
            // Tracks the fill itself, so the label's contrast flips exactly as the accent reaches it.
            contentColor = lerp(onIdle, onAccent, fill.value),
            disabledContainerColor = idleColor.copy(alpha = 0.45f)
                .compositeOver(MaterialTheme.colorScheme.surface),
            disabledContentColor = onIdle.copy(alpha = 0.38f),
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp,
            disabledElevation = 0.dp,
        ),
    ) {
        Box(
            // Spans the whole button (contentPadding is zero), so the disc is concentric with it and
            // at fill=1 lands exactly on the edge — a CircleShape button needs no clipping for this.
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (fill.value > 0f) {
                        drawCircle(color = Accent, radius = fill.value * size.minDimension / 2f)
                    }
                },
            contentAlignment = Alignment.Center,
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
                        is ConnectionStatus.Connected -> "Connected"
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
}

@Preview
@Composable
private fun ConnectionScreenPreview() {
    OnthecrowTheme(darkTheme = true) {
        ConnectionScreen(state = previewState(), onEvent = {})
    }
}

@Preview
@Composable
private fun ConnectionScreenEmptyPreview() {
    OnthecrowTheme(darkTheme = true) {
        ConnectionScreen(state = ConnectionState(), onEvent = {})
    }
}

private fun previewState(): ConnectionState {
    fun config(id: String, name: String, url: String) = RemoteConfig(id = id, name = name, url = url)
    fun row(sourceId: String, config: RemoteConfig) = ConfigRow(ConfigRef(sourceId, config.id), config)

    val subId = SourceGroup(
        sourceId = "sub1",
        kind = SourceKind.SUBSCRIPTION_ID,
        title = "onthecrow",
        subtitle = "361b3d70-9cf5-4494-82aa-b6ce1e5c453d",
        rows = listOf(
            row("sub1", config("c1", "Russia #1", "vless://x")),
            row("sub1", config("c2", "Russia #2", "hysteria2://x")),
            row("sub1", config("c3", "Finland #1", "vless://x")),
        ),
        addedAt = 1L,
    )
    val subId2 = SourceGroup(
        sourceId = "sub2",
        kind = SourceKind.SUBSCRIPTION_URL,
        title = "onthecrow::sub",
        subtitle = "https://361b3d70-9cf5-4494-82aa-b6ce1e5c453d",
        rows = listOf(
            row("sub1", config("c1", "Russia #1", "vless://x")),
            row("sub1", config("c2", "Russia #2", "hysteria2://x")),
            row("sub1", config("c3", "Finland #1", "vless://x")),
        ),
        addedAt = 1L,
    )
    val xray = SourceGroup(
        sourceId = SourceGroup.XRAY_LINKS_GROUP_ID,
        kind = SourceKind.XRAY_LINK,
        title = "Xray links",
        rows = listOf(row("link1", config("l1", "fi-direct-ded", "hysteria2://x"))),
        addedAt = 3L,
    )
    return ConnectionState(
        groups = listOf(subId, subId2, xray),
        selected = ConfigRef("sub1", "c3"),
        selectedConfig = subId.rows[2].config,
        collapsedLoaded = true,
        connectionStatus = ConnectionStatus.Connected,
    )
}

private val REVEAL_WIDTH = 88.dp

// Accent pill marking the active config, flush against the row's left edge (Happ-style).
private val ACTIVE_MARKER_WIDTH = 4.dp

// How long the connect button takes to flood with accent (and to drain back). Deliberately shorter
// than the halo's ramp — the fill is the lead-in, the glow is the payoff, and the pair should land
// well inside a second.
private const val FillDurationMillis = 400

// Gap between the bottom-bar buttons; reused as the add-menu popup's gap above the "+" FAB.
private val BottomBarSpacing = 20.dp

private fun subtitleFor(config: RemoteConfig): String {
    val scheme = config.url.substringBefore("://", missingDelimiterValue = "")
    return listOfNotNull(
        config.location?.takeIf { it.isNotBlank() }?.uppercase(),
        scheme.takeIf { it.isNotBlank() },
        config.type?.takeIf { it.isNotBlank() && !it.equals(scheme, ignoreCase = true) },
    ).joinToString(" · ")
}
