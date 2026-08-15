package com.jtwolfe.glass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jtwolfe.glass.chat.ChatViewModel

@Composable
fun GlassRoot(
    viewModel: ChatViewModel,
    isDefaultAssistant: Boolean,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("chat") }

    when (screen) {
        "settings" -> SettingsScreen(
            inbox = state.inbox,
            isDefaultAssistant = isDefaultAssistant,
            onBack = { screen = "chat" },
            onSaveInbox = viewModel::saveInbox,
            onRequestAssistantRole = onRequestAssistantRole,
            onOpenAssistantSettings = onOpenAssistantSettings,
        )
        else -> ChatScreen(
            state = state,
            isDefaultAssistant = isDefaultAssistant,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onOpenSettings = { screen = "settings" },
            onRequestAssistantRole = onRequestAssistantRole,
            onDismissError = viewModel::dismissError,
        )
    }
}
