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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onthecrow.onthecrowvpn.connection.model.ConnectionConfigSummary
import com.onthecrow.onthecrowvpn.ui.ConnectedGreen
import com.onthecrow.onthecrowvpn.ui.DisconnectedGray
import com.onthecrow.onthecrowvpn.vpn.ConnectionStatus

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
                    .padding(bottom = 176.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Onthecrow VPN",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.rawInput,
                    onValueChange = { onEvent(ConnectionEvent.OnInputChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Xray configuration") },
                    minLines = 5,
                    maxLines = 8,
                    isError = state.validationError != null,
                    supportingText = {
                        state.validationError?.let { Text(it) }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        enabled = state.canApply,
                        onClick = { onEvent(ConnectionEvent.OnApplyClick) },
                    ) {
                        if (state.isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Apply")
                        }
                    }
                }
                state.validatedConfig?.summary?.let { summary ->
                    ConfigSummaryCard(summary)
                }
            }

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

@Composable
private fun ConfigSummaryCard(summary: ConnectionConfigSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = endpointText(summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AssistChip(
                    onClick = {},
                    label = { Text(summary.protocol.uppercase()) },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                summary.transport?.let { MetadataChip(it) }
                summary.security?.let { MetadataChip(it) }
                summary.sni?.let { MetadataChip("SNI $it") }
            }
            if (summary.outboundCount > 1) {
                Text(
                    text = "${summary.outboundCount} outbounds detected; the first runnable outbound will be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (summary.isAdvanced) {
                Text(
                    text = "Advanced Xray config",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetadataChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            if (state.isBusy && !state.isValidating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = when {
                    connected -> "Connected"
                    state.isBusy && !state.isValidating -> "Connecting"
                    else -> "Connect"
                },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

private fun endpointText(summary: ConnectionConfigSummary): String {
    val address = summary.address ?: return "Endpoint is defined inside Xray JSON"
    val port = summary.port?.let { ":$it" }.orEmpty()
    return address + port
}
