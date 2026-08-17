package com.jtwolfe.glass.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jtwolfe.glass.ConnectionState
import com.jtwolfe.glass.GlassApplication
import com.jtwolfe.glass.auth.TokenResult
import com.jtwolfe.glass.displayText
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.pairing.SendResult
import com.jtwolfe.glass.pairing.SessionClient
import com.jtwolfe.glass.settings.AgentSettings
import com.jtwolfe.glass.settings.VoiceSettings
import com.jtwolfe.glass.settings.WssSettings
import com.jtwolfe.glass.settings.WssUrl
import com.jtwolfe.glass.voice.SttResult
import com.jtwolfe.glass.voice.XaiVoiceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<V0Message> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val sttError: SttError? = null,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val selectedAgentName: String = "Glass",
    val selectedAgentId: String = "",
    val sessionUrl: String = "",
)

data class SttError(
    val message: String,
    val isAuthError: Boolean = false,
    val httpCode: Int = 0,
)

sealed class TranscribeResult {
    data class Success(val text: String) : TranscribeResult()
    data class Error(val message: String, val httpCode: Int = 0, val isAuthError: Boolean = false) : TranscribeResult()
    data object NotLoggedIn : TranscribeResult()
}

class ChatViewModel(
    application: Application,
    private val repository: ChatRepository,
    private val xaiAuthStore: XaiAuthStore,
    private val voiceSettings: VoiceSettings,
    private val agentSettings: AgentSettings,
    private val wssSettings: WssSettings,
    private val sessionClient: SessionClient? = null,
    private val connectionStateProvider: (() -> ConnectionState)? = null,
    private val onReconnectRequest: ((pendingText: String?, force: Boolean) -> Unit)? = null,
    private val xaiVoiceClient: XaiVoiceClient = XaiVoiceClient(),
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var pendingSendText: String? = null

    private val connectionState: ConnectionState
        get() = connectionStateProvider?.invoke() ?: ConnectionState.UNPAIRED

    private val canSendRemote: Boolean
        get() = sessionClient?.isHelloed == true

    init {
        viewModelScope.launch {
            repository.messages.collect { msgs ->
                _state.update { it.copy(messages = msgs) }
            }
        }

        viewModelScope.launch {
            repository.agentError.collect { err ->
                if (err != null) {
                    _state.update { it.copy(error = err.banner) }
                }
            }
        }

        viewModelScope.launch {
            agentSettings.loadCachedAgents()
            agentSettings.selectedAgent.collect { agent ->
                _state.update { it.copy(
                    selectedAgentName = agent.name,
                    selectedAgentId = agent.id,
                ) }
            }
        }

        viewModelScope.launch {
            wssSettings.publicUrl.collect { url ->
                _state.update { it.copy(sessionUrl = url) }
            }
        }
    }

    fun updateConnectionStatus(state: ConnectionState) {
        _state.update { it.copy(status = state.displayText) }
    }

    fun onDraftChange(value: String) {
        _state.update { it.copy(draft = value) }
    }

    fun onListeningStateChange(isListening: Boolean, partial: String = "") {
        _state.update { it.copy(isListening = isListening, partialTranscript = partial) }
    }

    fun onVoiceTranscript(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(draft = trimmed) }
        send()
    }

    fun onSessionReady() {
        _state.update { ui ->
            ui.copy(
                status = connectionState.displayText,
                error = null,
            )
        }

        val pending = pendingSendText
        if (pending != null) {
            pendingSendText = null
            viewModelScope.launch {
                delay(100)
                _state.update { it.copy(draft = pending) }
                send()
            }
        }
    }

    fun onSessionDisconnected() {
        _state.update { ui ->
            ui.copy(status = connectionState.displayText)
        }
    }

    fun onReconnecting() {
        _state.update { ui ->
            ui.copy(status = ConnectionState.RECONNECTING.displayText)
        }
    }

    fun onReconnectFailed() {
        _state.update { ui ->
            ui.copy(
                status = connectionState.displayText,
                error = "Could not reconnect — tap to retry",
            )
        }
        pendingSendText = null
    }

    suspend fun clearLocal() {
        repository.clear()
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return

        val currentState = connectionState
        if (!canSendRemote) {
            if (currentState == ConnectionState.UNPAIRED) {
                _state.update {
                    it.copy(error = "Not connected — pair with plugin first")
                }
                return
            }
            pendingSendText = text
            _state.update {
                it.copy(
                    draft = "",
                    error = "Reconnecting to send...",
                )
            }
            onReconnectRequest?.invoke(text, true)
            return
        }

        val outgoing = V0Message.outgoing(text)
        val agentId = _state.value.selectedAgentId.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    draft = "",
                    sending = true,
                    error = null,
                    sttError = null,
                )
            }
            repository.appendOutgoing(outgoing)
            if (canSendRemote) {
                try {
                    when (val result = repository.sendRemote(outgoing, agentId)) {
                        is SendResult.Success -> Unit
                        is SendResult.NotConnected -> {
                            _state.update {
                                it.copy(error = "Send failed — kept locally")
                            }
                            onReconnectRequest?.invoke(null, true)
                        }
                        is SendResult.Error -> {
                            _state.update {
                                it.copy(error = result.message)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (err: Exception) {
                    _state.update {
                        it.copy(error = err.message ?: "Send failed — kept locally")
                    }
                }
            }
            _state.update { it.copy(sending = false) }
        }
    }

    fun saveSessionUrl(url: String) {
        viewModelScope.launch {
            val trimmed = url.trim()
            val parsed = if (trimmed.isEmpty()) null else WssUrl.parse(trimmed)
            if (trimmed.isNotEmpty() && parsed == null) {
                _state.update { it.copy(error = "Invalid session URL") }
                return@launch
            }
            val previous = wssSettings.current()
            wssSettings.save(trimmed)
            val canonical = parsed?.canonical.orEmpty()
            val paired = (getApplication<Application>() as? GlassApplication)?.pairingStore?.isPaired == true
            if (canonical != previous && paired) {
                if (sessionClient?.isHelloed == true) {
                    sessionClient.disconnect()
                }
                onReconnectRequest?.invoke(null, true)
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch { repository.dismissAgentError() }
    }

    fun setSttError(error: SttError?) {
        _state.update { it.copy(sttError = error) }
    }

    fun dismissSttError() {
        _state.update { it.copy(sttError = null) }
    }

    /**
     * Transcribe audio using xAI STT when logged in.
     * Returns TranscribeResult with either success text or specific error.
     *
     * @param audio Raw audio bytes (WAV format expected from XaiAudioRecorder)
     * @param filename Suggested filename for the audio file
     * @param contentType MIME type of the audio
     */
    suspend fun transcribeAudio(
        audio: ByteArray,
        filename: String = "speech.wav",
        contentType: String = "audio/wav",
    ): TranscribeResult {
        if (audio.isEmpty()) {
            return TranscribeResult.Error("No audio captured")
        }

        val tokenResult = xaiAuthStore.getOrRefreshAccessToken()
        val bearer = when (tokenResult) {
            is TokenResult.Valid -> tokenResult.accessToken
            is TokenResult.RefreshFailed -> {
                return TranscribeResult.Error(tokenResult.message)
            }
            is TokenResult.NoSession -> {
                return TranscribeResult.NotLoggedIn
            }
        }

        val sttResult = withContext(Dispatchers.IO) {
            xaiVoiceClient.transcribe(bearer, audio, filename, contentType)
        }

        return when (sttResult) {
            is SttResult.Success -> TranscribeResult.Success(sttResult.text)
            is SttResult.Error -> TranscribeResult.Error(
                message = sttResult.displayMessage,
                httpCode = sttResult.httpCode,
                isAuthError = sttResult.httpCode in 401..403,
            )
        }
    }

    val hasXaiAuth: Boolean get() = xaiAuthStore.isLoggedIn

    companion object {
        fun factory(
            application: Application,
            onReconnectRequest: ((pendingText: String?, force: Boolean) -> Unit)? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as GlassApplication
                    val connectionStateProvider: () -> ConnectionState = { app.connectionState.value }
                    return ChatViewModel(
                        application = app,
                        repository = app.chatRepository,
                        xaiAuthStore = app.xaiAuthStore,
                        voiceSettings = app.voiceSettings,
                        agentSettings = app.agentSettings,
                        wssSettings = app.wssSettings,
                        sessionClient = app.sessionClient,
                        connectionStateProvider = connectionStateProvider,
                        onReconnectRequest = onReconnectRequest,
                    ) as T
                }
            }
    }
}
