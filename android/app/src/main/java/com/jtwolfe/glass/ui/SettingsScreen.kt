package com.jtwolfe.glass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jtwolfe.glass.ConnectionState
import com.jtwolfe.glass.auth.XaiAuthBundle
import com.jtwolfe.glass.displayText
import com.jtwolfe.glass.inbox.InboxConfig
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.settings.Agent
import com.jtwolfe.glass.settings.VoiceSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    inbox: InboxConfig,
    xaiAuth: XaiAuthBundle?,
    pairing: PairingInvite?,
    connectionState: ConnectionState,
    isDefaultAssistant: Boolean,
    xaiLoginLoading: Boolean,
    selectedVoiceId: String,
    availableAgents: List<Agent>,
    selectedAgentId: String,
    onBack: () -> Unit,
    onSaveInbox: (String, String) -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onXaiLogin: () -> Unit,
    onXaiLogout: () -> Unit,
    onOpenPairing: () -> Unit,
    onClearPairing: () -> Unit,
    onVoiceSelected: (String) -> Unit,
    onAgentSelected: (Agent) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(inbox.url) }
    var token by rememberSaveable { mutableStateOf(inbox.token) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(inbox.url, inbox.token) {
        if (url.isEmpty()) url = inbox.url
        if (token.isEmpty()) token = inbox.token
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // xAI / Grok login section
            XaiLoginSection(
                xaiAuth = xaiAuth,
                loading = xaiLoginLoading,
                onLogin = onXaiLogin,
                onLogout = onXaiLogout,
            )

            HorizontalDivider()

            // Voice picker section (only when logged in)
            if (xaiAuth != null) {
                VoicePickerSection(
                    selectedVoiceId = selectedVoiceId,
                    onVoiceSelected = onVoiceSelected,
                )
                HorizontalDivider()
            }

            // Agent picker section
            AgentPickerSection(
                availableAgents = availableAgents,
                selectedAgentId = selectedAgentId,
                onAgentSelected = onAgentSelected,
            )

            HorizontalDivider()

            // Inbox pairing section
            PairingSection(
                pairing = pairing,
                connectionState = connectionState,
                onOpenPairing = onOpenPairing,
                onClearPairing = onClearPairing,
            )

            HorizontalDivider()

            // Assistant role section
            AssistantSection(
                isDefaultAssistant = isDefaultAssistant,
                onRequestAssistantRole = onRequestAssistantRole,
                onOpenAssistantSettings = onOpenAssistantSettings,
            )

            HorizontalDivider()

            // Advanced HTTPS fallback (hidden by default)
            AdvancedHttpsSection(
                expanded = showAdvanced,
                onToggle = { showAdvanced = !showAdvanced },
                inbox = inbox,
                url = url,
                token = token,
                onUrlChange = { url = it },
                onTokenChange = { token = it },
                onSave = { onSaveInbox(url, token) },
            )
        }
    }
}

@Composable
private fun XaiLoginSection(
    xaiAuth: XaiAuthBundle?,
    loading: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("xAI Login", style = MaterialTheme.typography.titleMedium)

        if (xaiAuth != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Logged in",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    xaiAuth.email?.let { email ->
                        Text(
                            email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (xaiAuth.isExpired) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Token expired — login again to refresh",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                "Voice uses Grok STT/TTS. Token stays on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Logout")
            }
        } else {
            Text(
                "Login with xAI to enable Grok voice features.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
            ) {
                Text(if (loading) "Opening browser..." else "Login with xAI")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePickerSection(
    selectedVoiceId: String,
    onVoiceSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select the voice for text-to-speech responses.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoiceSettings.AVAILABLE_VOICES.forEach { voiceId ->
                FilterChip(
                    selected = selectedVoiceId == voiceId,
                    onClick = { onVoiceSelected(voiceId) },
                    label = { Text(VoiceSettings.voiceDisplayName(voiceId)) },
                    leadingIcon = if (selectedVoiceId == voiceId) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun AgentPickerSection(
    availableAgents: List<Agent>,
    selectedAgentId: String,
    onAgentSelected: (Agent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Default Agent", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select which agent to chat with.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            availableAgents.forEach { agent ->
                val isSelected = agent.id == selectedAgentId
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAgentSelected(agent) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    border = if (isSelected) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else null,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = agent.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSection(
    isDefaultAssistant: Boolean,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("System Assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            if (isDefaultAssistant) {
                "Glass is the default assistant. Long-press home opens this app."
            } else {
                "Make Glass the default assistant to use long-press home."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRequestAssistantRole, modifier = Modifier.fillMaxWidth()) {
            Text(if (isDefaultAssistant) "Reassign Role" else "Set as Default")
        }
    }
}

@Composable
private fun PairingSection(
    pairing: PairingInvite?,
    connectionState: ConnectionState,
    onOpenPairing: () -> Unit,
    onClearPairing: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Plugin Connection", style = MaterialTheme.typography.titleMedium)

        if (connectionState != ConnectionState.UNPAIRED) {
            val statusColor = when (connectionState) {
                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.tertiaryContainer
                ConnectionState.OFFLINE_PAIRED -> MaterialTheme.colorScheme.secondaryContainer
                ConnectionState.UNPAIRED -> MaterialTheme.colorScheme.surfaceVariant
            }
            val onStatusColor = when (connectionState) {
                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
                ConnectionState.RECONNECTING -> MaterialTheme.colorScheme.onTertiaryContainer
                ConnectionState.OFFLINE_PAIRED -> MaterialTheme.colorScheme.onSecondaryContainer
                ConnectionState.UNPAIRED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = statusColor),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        connectionState.displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onStatusColor,
                    )
                }
            }
            OutlinedButton(
                onClick = onClearPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unpair")
            }
        } else {
            Text(
                "Scan the QR code from the plugin to connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onOpenPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Scan QR Code")
            }
        }
    }
}

@Composable
private fun AdvancedHttpsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    inbox: InboxConfig,
    url: String,
    token: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Advanced", style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "HTTP inbox fallback (optional). WebRTC is preferred.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bearer token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Inbox URL") },
                    singleLine = true,
                )
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save")
                }
            }
        }
    }
}
