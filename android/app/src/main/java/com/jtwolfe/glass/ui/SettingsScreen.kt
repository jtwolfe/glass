package com.jtwolfe.glass.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.jtwolfe.glass.auth.XaiAuthBundle
import com.jtwolfe.glass.inbox.InboxConfig
import com.jtwolfe.glass.pairing.PairingInvite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    inbox: InboxConfig,
    xaiAuth: XaiAuthBundle?,
    pairing: PairingInvite?,
    isDefaultAssistant: Boolean,
    xaiLoginLoading: Boolean,
    onBack: () -> Unit,
    onSaveInbox: (String, String) -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onXaiLogin: () -> Unit,
    onXaiLogout: () -> Unit,
    onOpenPairing: () -> Unit,
    onClearPairing: () -> Unit,
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
            // Assistant role section
            AssistantSection(
                isDefaultAssistant = isDefaultAssistant,
                onRequestAssistantRole = onRequestAssistantRole,
                onOpenAssistantSettings = onOpenAssistantSettings,
            )

            HorizontalDivider()

            // xAI / Grok login section
            XaiLoginSection(
                xaiAuth = xaiAuth,
                loading = xaiLoginLoading,
                onLogin = onXaiLogin,
                onLogout = onXaiLogout,
            )

            HorizontalDivider()

            // Inbox pairing section
            PairingSection(
                pairing = pairing,
                onOpenPairing = onOpenPairing,
                onClearPairing = onClearPairing,
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
private fun AssistantSection(
    isDefaultAssistant: Boolean,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Default Assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            if (isDefaultAssistant) {
                "Glass holds ROLE_ASSISTANT. Long-press home opens Ashleigh."
            } else {
                "Grant the assistant role so long-press home replaces Gemini."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRequestAssistantRole, modifier = Modifier.fillMaxWidth()) {
            Text("Request ROLE_ASSISTANT")
        }
        OutlinedButton(onClick = onOpenAssistantSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Open voice / assistant settings")
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
        Text("xAI / Grok Login", style = MaterialTheme.typography.titleMedium)

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
                "Voice uses xAI STT/TTS directly from the phone. " +
                    "The xAI bearer never leaves this device.",
                style = MaterialTheme.typography.bodySmall,
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
                "Login with your xAI account to enable Grok STT/TTS directly from the phone. " +
                    "Without login, voice falls back to on-device recognition.",
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

@Composable
private fun PairingSection(
    pairing: PairingInvite?,
    onOpenPairing: () -> Unit,
    onClearPairing: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Inbox Pairing", style = MaterialTheme.typography.titleMedium)

        if (pairing != null && pairing.isValid) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Paired (P2P stream active after QR dial)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Peer: ${pairing.peer.take(16)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Protocol: ${pairing.proto}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (pairing.hasCircuitRelay) {
                        Text(
                            "Circuit relay available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Text(
                "Chat uses P2P stream when connected. Falls back to HTTPS if stream drops.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onClearPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unpair")
            }
        } else {
            Text(
                "Pair with your inbox to enable secure P2P communication. " +
                    "Scan the QR code or enter the short code from your inbox setup.",
                style = MaterialTheme.typography.bodyMedium,
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
                Text("Pair Inbox")
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
            Text("Advanced: HTTPS Fallback", style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Legacy HTTPS transport (being replaced by P2P pairing). " +
                        "Only use this if instructed by support or for debugging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Active source: ${inbox.source}" +
                        if (inbox.isConfigured) " (configured)" else " (unset)",
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Inbox base URL") },
                    placeholder = { Text("https://inbox.example.invalid") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bearer token (GLASS_PHONE_TOKEN)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save HTTPS settings")
                }
                Text(
                    "Paths: POST /v0/messages · GET /v0/replies · Authorization: Bearer",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
