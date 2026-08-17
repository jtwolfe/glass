package com.jtwolfe.glass.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jtwolfe.glass.ConnectionState
import com.jtwolfe.glass.auth.XaiAuthBundle
import com.jtwolfe.glass.chat.ChatViewModel
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.settings.Agent
import com.jtwolfe.glass.settings.AgentRosterState

@Composable
fun GlassRoot(
    viewModel: ChatViewModel,
    xaiAuth: XaiAuthBundle?,
    pairing: PairingInvite?,
    connectionState: ConnectionState,
    isDefaultAssistant: Boolean,
    hasMicPermission: Boolean,
    xaiLoginLoading: Boolean,
    selectedVoiceId: String,
    roster: AgentRosterState,
    onRequestAssistantRole: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onXaiLogin: () -> Unit,
    onXaiLogout: () -> Unit,
    onSavePairing: (PairingInvite) -> Unit,
    onClearPairing: () -> Unit,
    onVoiceSelected: (String) -> Unit,
    onAgentSelected: (Agent) -> Unit,
    onRefreshAgents: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("chat") }

    when (screen) {
        "settings" -> SettingsScreen(
            xaiAuth = xaiAuth,
            pairing = pairing,
            connectionState = connectionState,
            isDefaultAssistant = isDefaultAssistant,
            xaiLoginLoading = xaiLoginLoading,
            selectedVoiceId = selectedVoiceId,
            roster = roster,
            selectedAgentId = state.selectedAgentId,
            onBack = { screen = "chat" },
            sessionUrl = state.sessionUrl,
            onSaveSessionUrl = viewModel::saveSessionUrl,
            onRequestAssistantRole = onRequestAssistantRole,
            onOpenAssistantSettings = onOpenAssistantSettings,
            onXaiLogin = onXaiLogin,
            onXaiLogout = onXaiLogout,
            onOpenPairing = { screen = "pairing" },
            onClearPairing = onClearPairing,
            onVoiceSelected = onVoiceSelected,
            onAgentSelected = onAgentSelected,
            onRefreshAgents = onRefreshAgents,
        )
        "pairing" -> PairingScreen(
            onBack = { screen = "settings" },
            onPaired = { invite ->
                onSavePairing(invite)
                screen = "settings"
            },
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
            onDismissSttError = viewModel::dismissSttError,
            onRequestMicPermission = onRequestMicPermission,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onLoginClick = {
                screen = "settings"
                onXaiLogin()
            },
        )
    }
}
