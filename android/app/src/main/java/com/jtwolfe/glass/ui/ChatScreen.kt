package com.jtwolfe.glass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jtwolfe.glass.chat.ChatUiState
import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.ui.theme.AshleighBubble
import com.jtwolfe.glass.ui.theme.Ink
import com.jtwolfe.glass.ui.theme.JamieBubble
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    isDefaultAssistant: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestAssistantRole: () -> Unit,
    onDismissError: () -> Unit,
) {
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(err)
        onDismissError()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ashleigh")
                        Text(
                            text = state.status ?: "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            if (!isDefaultAssistant) {
                AssistantBanner(onRequestAssistantRole)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages, key = { "${it.from}|${it.at}|${it.text}" }) { msg ->
                    MessageBubble(msg)
                }
            }
            Composer(
                draft = state.draft,
                sending = state.sending,
                onDraftChange = onDraftChange,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun AssistantBanner(onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Not the default assistant yet. Long-press home needs ROLE_ASSISTANT.",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onRequest) { Text("Grant") }
    }
}

@Composable
private fun MessageBubble(message: V0Message) {
    val outgoing = message.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = if (outgoing) JamieBubble else AshleighBubble,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = if (outgoing) "Jamie" else "Ashleigh",
                style = MaterialTheme.typography.labelSmall,
                color = if (outgoing) Color(0xFFB8D4DE) else Ink,
            )
            Text(
                text = message.text,
                color = if (outgoing) Color.White else Ink,
            )
            Text(
                text = formatAt(message.at),
                style = MaterialTheme.typography.labelSmall,
                color = if (outgoing) Color(0xFFB8D4DE) else Ink.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message Ashleigh") },
            maxLines = 4,
            enabled = !sending,
        )
        Button(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatAt(at: String): String = runCatching {
    clock.format(Instant.parse(at))
}.getOrElse { at }
