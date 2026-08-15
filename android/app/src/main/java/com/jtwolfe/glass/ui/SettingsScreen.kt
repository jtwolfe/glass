package com.jtwolfe.glass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jtwolfe.glass.inbox.InboxConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    inbox: InboxConfig,
    isDefaultAssistant: Boolean,
    onBack: () -> Unit,
    onSaveInbox: (String, String) -> Unit,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(inbox.url) }
    var token by rememberSaveable { mutableStateOf(inbox.token) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Default assistant", style = MaterialTheme.typography.titleMedium)
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

            Text("Inbox (Quay)", style = MaterialTheme.typography.titleMedium)
            Text(
                "URL and bearer token are not stored in git. Set them here (DataStore) " +
                    "and/or in local.properties as glass.inbox.url / glass.inbox.token " +
                    "(BuildConfig). Runtime values override BuildConfig when non-empty. " +
                    "If both are unset, chat stays local-only on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Active source: ${inbox.source}" +
                    if (inbox.isConfigured) " (configured)" else " (local-only)",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Inbox base URL") },
                placeholder = { Text("https://inbox.example.invalid") },
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bearer token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(
                onClick = { onSaveInbox(url, token) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save inbox settings")
            }
            Text(
                "Paths: POST {base}/v0/messages  ·  GET {base}/v0/messages  ·  Authorization: Bearer",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
