package com.jtwolfe.glass.ui

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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.jtwolfe.glass.ConnectionState
import com.jtwolfe.glass.auth.XaiAuthBundle
import com.jtwolfe.glass.displayText
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.pairing.agentErrorBanner
import com.jtwolfe.glass.settings.Agent
import com.jtwolfe.glass.settings.AgentRosterState
import com.jtwolfe.glass.settings.VoiceSettings
import com.jtwolfe.glass.settings.WssSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    xaiAuth: XaiAuthBundle?,
    pairing: PairingInvite?,
    connectionState: ConnectionState,
    isDefaultAssistant: Boolean,
    xaiLoginLoading: Boolean,
    selectedVoiceId: String,
    roster: AgentRosterState,
    selectedAgentId: String,
    onBack: () -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onXaiLogin: () -> Unit,
    onXaiLogout: () -> Unit,
    onOpenPairing: () -> Unit,
    onClearPairing: () -> Unit,
    onVoiceSelected: (String) -> Unit,
    onAgentSelected: (Agent) -> Unit,
    onRefreshAgents: () -> Unit,
    sessionUrl: String,
    onSaveSessionUrl: (String) -> Unit,
) {
    var wssUrl by rememberSaveable { mutableStateOf(sessionUrl) }

    LaunchedEffect(sessionUrl) {
        if (wssUrl.isEmpty()) wssUrl = sessionUrl
    }
    LaunchedEffect(Unit) {
        onRefreshAgents()
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
                roster = roster,
                selectedAgentId = selectedAgentId,
                onAgentSelected = onAgentSelected,
            )

            HorizontalDivider()

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

            SessionUrlSection(
                url = wssUrl,
                onUrlChange = { wssUrl = it },
                onSave = { onSaveSessionUrl(wssUrl) },
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
    roster: AgentRosterState,
    selectedAgentId: String,
    onAgentSelected: (Agent) -> Unit,
) {
    val agents = roster.agents
    val lastError = roster.lastError
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Default Agent", style = MaterialTheme.typography.titleMedium)
        when {
            agents.isEmpty() && lastError == null -> {
                Text(
                    "No Grok Bot agents. Create one on the desktop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            agents.isEmpty() -> {
                Text(
                    "Couldn't load agents.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    agentErrorBanner(lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                Text(
                    if (roster.stale || lastError != null) {
                        "Last known roster — desktop unreachable."
                    } else {
                        "Select which agent to chat with."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    agents.forEach { agent ->
                        val isSelected = agent.id == selectedAgentId && selectedAgentId.isNotBlank()
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
private fun SessionUrlSection(
    url: String,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Session URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "Public: wss://glass.enphi.net/session (HTTPS reverse proxy). " +
                "LAN fallback: ws://192.168.1.200:8711/session. " +
                "Do not use 127.0.0.1 — that is this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Session URL") },
            placeholder = { Text(WssSettings.PLACEHOLDER) },
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
