package com.jtwolfe.glass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jtwolfe.glass.auth.XaiAuthBundle
import com.jtwolfe.glass.chat.ChatViewModel
import com.jtwolfe.glass.pairing.PairingInvite

@Composable
fun GlassRoot(
    viewModel: ChatViewModel,
    xaiAuth: XaiAuthBundle?,
    pairing: PairingInvite?,
    isDefaultAssistant: Boolean,
    hasMicPermission: Boolean,
    xaiLoginLoading: Boolean,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onXaiLogin: () -> Unit,
    onXaiLogout: () -> Unit,
    onSavePairing: (PairingInvite) -> Unit,
    onClearPairing: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("chat") }

    when (screen) {
        "settings" -> SettingsScreen(
            inbox = state.inbox,
            xaiAuth = xaiAuth,
            pairing = pairing,
            isDefaultAssistant = isDefaultAssistant,
            xaiLoginLoading = xaiLoginLoading,
            onBack = { screen = "chat" },
            onSaveInbox = viewModel::saveInbox,
            onRequestAssistantRole = onRequestAssistantRole,
            onOpenAssistantSettings = onOpenAssistantSettings,
            onXaiLogin = onXaiLogin,
            onXaiLogout = onXaiLogout,
            onOpenPairing = { screen = "pairing" },
            onClearPairing = onClearPairing,
        )
        "pairing" -> PairingScreen(
            onBack = { screen = "settings" },
            onPaired = { invite ->
                onSavePairing(invite)
                screen = "settings"
            },
            relayConfigured = false, // TODO: Add relay settings
        )
        else -> ChatScreen(
            state = state,
            isDefaultAssistant = isDefaultAssistant,
            hasMicPermission = hasMicPermission,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onOpenSettings = { screen = "settings" },
            onRequestAssistantRole = onRequestAssistantRole,
            onDismissError = viewModel::dismissError,
            onRequestMicPermission = onRequestMicPermission,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
        )
    }
}
