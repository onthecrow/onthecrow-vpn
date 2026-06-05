package com.onthecrow.onthecrowvpn.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onthecrow.onthecrowvpn.connection.model.ConfigBundle
import com.onthecrow.onthecrowvpn.connection.model.RemoteConfig
import com.onthecrow.onthecrowvpn.ui.ConnectedGreen
import com.onthecrow.onthecrowvpn.ui.DisconnectedGray
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun ConnectionScreen(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    onEvent: (ConnectionEvent) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onEvent(ConnectionEvent.OnSnackbarShown)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(contentPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .padding(bottom = if (state.bundle != null) 176.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Onthecrow VPN",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                IdentifierSlot(state = state, onEvent = onEvent)
                state.bundle?.let { bundle ->
                    BundleMetadataRows(bundle)
                    ConfigsList(
                        configs = bundle.configs,
                        selectedConfigId = state.selectedConfigId,
                        onSelect = { onEvent(ConnectionEvent.OnConfigSelected(it)) },
                    )
                }
            }

            if (state.selectedConfig != null) {
                ConnectButton(
                    state = state,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp),
                    onClick = { onEvent(ConnectionEvent.OnConnectClick) },
                )
            }
        }
    }
}

@Composable
private fun IdentifierSlot(
    state: ConnectionState,
    onEvent: (ConnectionEvent) -> Unit,
) {
    if (state.isEditingId) {
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        OutlinedTextField(
            value = state.idInput,
            onValueChange = { onEvent(ConnectionEvent.OnIdInputChanged(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text("Configuration ID") },
            singleLine = true,
            enabled = !state.isLoadingBundle,
            isError = state.bundleError != null,
            supportingText = { state.bundleError?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onEvent(ConnectionEvent.OnLoadClick) }),
            trailingIcon = {
                if (state.isLoadingBundle) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = { onEvent(ConnectionEvent.OnLoadClick) },
                        enabled = state.canLoad,
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (state.canLoad) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
            },
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = ConnectedGreen.copy(alpha = 0.18f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.idInput,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onEvent(ConnectionEvent.OnEditIdClick) }) {
                    Text(
                        text = "✎",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BundleMetadataRows(bundle: ConfigBundle) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bundle.name?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = "Config name: $name",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Created at: ${formatTimestamp(bundle.createdAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Updated at: ${formatTimestamp(bundle.updatedAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigsList(
    configs: List<RemoteConfig>,
    selectedConfigId: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        configs.forEach { config ->
            ConfigItemCard(
                config = config,
                isSelected = config.id == selectedConfigId,
                onClick = { onSelect(config.id) },
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
    val container =
        if (isSelected) ConnectedGreen.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.surfaceContainer
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

private fun subtitleFor(config: RemoteConfig): String {
    val scheme = config.url.substringBefore("://", missingDelimiterValue = "")
    return listOfNotNull(
        config.location?.takeIf { it.isNotBlank() }?.uppercase(),
        scheme.takeIf { it.isNotBlank() },
        config.type?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

private fun formatTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    val instant = Instant.fromEpochMilliseconds(epochMillis)
    val ldt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val day = ldt.dayOfMonth.toString().padStart(2, '0')
    val month = ldt.monthNumber.toString().padStart(2, '0')
    val year = ldt.year.toString()
    val hour = ldt.hour.toString().padStart(2, '0')
    val minute = ldt.minute.toString().padStart(2, '0')
    return "$day.$month.$year $hour:$minute"
}
