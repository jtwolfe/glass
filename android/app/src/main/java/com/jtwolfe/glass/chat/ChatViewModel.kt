package com.jtwolfe.glass.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jtwolfe.glass.GlassApplication
import com.jtwolfe.glass.auth.TokenResult
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.InboxConfig
import com.jtwolfe.glass.inbox.InboxSettings
import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.pairing.PluginClient
import com.jtwolfe.glass.rtc.WebRtcPeerConnection
import com.jtwolfe.glass.settings.AgentSettings
import com.jtwolfe.glass.settings.VoiceSettings
import com.jtwolfe.glass.voice.SttResult
import com.jtwolfe.glass.voice.TtsResult
import com.jtwolfe.glass.voice.XaiVoiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<V0Message> = emptyList(),
    val draft: String = "",
    val inbox: InboxConfig = InboxConfig("", "", "unset"),
    val sending: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val sttError: SttError? = null,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val selectedAgentName: String = "Ashleigh",
    val selectedAgentId: String = "28b14c15-d85a-4fdf-9d64-770a4d0d4084",
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
    private val settings: InboxSettings,
    private val repository: ChatRepository,
    private val xaiAuthStore: XaiAuthStore,
    private val voiceSettings: VoiceSettings,
    private val agentSettings: AgentSettings,
    private val pluginClient: PluginClient? = null,
    private val webRtcConnectionProvider: (() -> WebRtcPeerConnection?)? = null,
    private val xaiVoiceClient: XaiVoiceClient = XaiVoiceClient(),
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _newAshleighMessages = Channel<V0Message>(Channel.BUFFERED)
    val newAshleighMessages = _newAshleighMessages.receiveAsFlow()

    private var pollJob: Job? = null
    private var afterCursor: String = EPOCH
    private var lastSpokenAt: String = EPOCH

    private val isWebRtcConnected: Boolean
        get() = webRtcConnectionProvider?.invoke()?.isConnected == true

    private val isPluginConnected: Boolean
        get() = pluginClient?.isConnected == true && pluginClient.isPaired

    private val canSendRemote: Boolean
        get() = isWebRtcConnected || isPluginConnected || _state.value.inbox.isHttpConfigured

    init {
        viewModelScope.launch {
            val local = repository.loadLocal()
            _state.update { it.copy(messages = local) }
            afterCursor = local.maxOfOrNull { it.at } ?: EPOCH
            lastSpokenAt = local
                .filter { it.from.equals(V0Message.FROM_ASHLEIGH, ignoreCase = true) }
                .maxOfOrNull { it.at } ?: EPOCH
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
            settings.config.collect { config ->
                _state.update { ui ->
                    ui.copy(
                        inbox = config,
                        status = when {
                            isWebRtcConnected -> "Connected"
                            isPluginConnected -> "Connected (TCP)"
                            config.isHttpConfigured -> "HTTP (${config.source})"
                            else -> "Offline — scan QR to connect"
                        },
                    )
                }
                restartPolling(config)
                if (canSendRemote) {
                    refreshRemote(config)
                }
            }
        }
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

    fun onAssistOpened() {
        refresh()
    }

    fun onPluginConnected() {
        val config = _state.value.inbox
        _state.update { ui ->
            ui.copy(
                status = "Plugin connected (v1 TCP)",
            )
        }
        restartPolling(config)
        viewModelScope.launch { refreshRemote(config) }
    }

    fun onPluginDisconnected() {
        val config = _state.value.inbox
        _state.update { ui ->
            ui.copy(
                status = when {
                    isWebRtcConnected -> "Plugin connected (WebRTC DataChannel)"
                    config.isHttpConfigured -> "Inbox HTTP (${config.source})"
                    else -> "Local-only — scan QR to connect via ntfy"
                },
            )
        }
        restartPolling(config)
    }

    fun onWebRtcConnected() {
        val config = _state.value.inbox
        _state.update { ui ->
            ui.copy(
                status = "Plugin connected (WebRTC DataChannel)",
            )
        }
        restartPolling(config)
        viewModelScope.launch { refreshRemote(config) }
    }

    fun onWebRtcDisconnected() {
        val config = _state.value.inbox
        _state.update { ui ->
            ui.copy(
                status = when {
                    isPluginConnected -> "Connected (TCP)"
                    config.isHttpConfigured -> "HTTP (${config.source})"
                    else -> "Offline — scan QR to connect"
                },
            )
        }
        restartPolling(config)
    }

    fun onReconnecting() {
        _state.update { ui ->
            ui.copy(status = "Reconnecting...")
        }
    }

    fun refresh() {
        val config = _state.value.inbox
        if (canSendRemote) {
            viewModelScope.launch { refreshRemote(config) }
        }
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return
        val outgoing = V0Message.outgoing(text)
        val agentId = _state.value.selectedAgentId
        viewModelScope.launch {
            _state.update {
                it.copy(
                    draft = "",
                    sending = true,
                    error = null,
                    sttError = null,
                    messages = it.messages + outgoing,
                )
            }
            persistLocal()
            val config = _state.value.inbox
            if (canSendRemote) {
                runCatching { repository.sendRemote(config, outgoing, agentId) }
                    .onFailure { err ->
                        _state.update {
                            it.copy(error = err.message ?: "Send failed — kept locally")
                        }
                    }
                refreshRemote(config)
            }
            _state.update { it.copy(sending = false) }
        }
    }

    fun saveInbox(url: String, token: String) {
        viewModelScope.launch {
            settings.save(url, token)
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Fetch reply audio. Prefers xAI TTS when logged in, falls back to inbox audio.
     */
    suspend fun fetchReplyAudio(id: String): ByteArray? {
        val config = _state.value.inbox
        if (id.isBlank()) return null

        // Try inbox audio endpoint first (it may have pre-rendered audio)
        if (config.isHttpConfigured) {
            val inboxAudio = runCatching { repository.fetchReplyAudio(config, id) }.getOrNull()
            if (inboxAudio != null && inboxAudio.isNotEmpty()) {
                return inboxAudio
            }
        }

        return null
    }

    /**
     * Synthesize speech using xAI TTS when logged in.
     * Returns null if not logged in or on error (caller should use on-device TTS).
     */
    suspend fun synthesizeXaiTts(text: String): ByteArray? {
        if (text.isBlank()) return null
        val bearer = when (val tokenResult = xaiAuthStore.getOrRefreshAccessToken()) {
            is TokenResult.Valid -> tokenResult.accessToken
            else -> return null
        }
        val voiceId = voiceSettings.voiceId.first()
        return withContext(Dispatchers.IO) {
            when (val result = xaiVoiceClient.synthesize(bearer, text, voiceId)) {
                is TtsResult.Success -> result.audio
                is TtsResult.Error -> null
            }
        }
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

    private suspend fun refreshRemote(config: InboxConfig) {
        runCatching { repository.pullReplies(config, afterCursor) }
            .onSuccess { remote ->
                if (remote.isNotEmpty()) {
                    afterCursor = remote.maxOf { it.at }
                }
                val newAshleigh = remote.filter { msg ->
                    msg.from.equals(V0Message.FROM_ASHLEIGH, ignoreCase = true) &&
                        msg.at > lastSpokenAt
                }
                if (newAshleigh.isNotEmpty()) {
                    lastSpokenAt = newAshleigh.maxOf { it.at }
                    newAshleigh.sortedBy { it.at }.forEach { msg ->
                        _newAshleighMessages.trySend(msg)
                    }
                }
                _state.update { ui ->
                    ui.copy(messages = merge(ui.messages, remote), error = null)
                }
                persistLocal()
            }
            .onFailure { err ->
                _state.update { it.copy(error = err.message ?: "Inbox unreachable") }
            }
    }

    private fun restartPolling(config: InboxConfig) {
        pollJob?.cancel()
        if (!canSendRemote) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(8_000)
                refreshRemote(config)
            }
        }
    }

    private suspend fun persistLocal() {
        repository.saveLocal(_state.value.messages)
    }

    private fun merge(local: List<V0Message>, remote: List<V0Message>): List<V0Message> {
        val seen = LinkedHashSet<String>()
        return (local + remote).filter { msg ->
            val key = msg.id?.takeIf { it.isNotBlank() } ?: "${msg.from}|${msg.at}|${msg.text}"
            seen.add(key)
        }.sortedBy { it.at }
    }

    companion object {
        private const val EPOCH = "1970-01-01T00:00:00Z"

        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as GlassApplication
                    val webRtcProvider: () -> WebRtcPeerConnection? = { app.webRtcConnection }
                    return ChatViewModel(
                        application = app,
                        settings = app.inboxSettings,
                        repository = ChatRepository(
                            context = app,
                            streamClient = app.inboxStreamClient,
                            pluginClient = app.pluginClient,
                            webRtcConnectionProvider = webRtcProvider,
                        ),
                        xaiAuthStore = app.xaiAuthStore,
                        voiceSettings = app.voiceSettings,
                        agentSettings = app.agentSettings,
                        pluginClient = app.pluginClient,
                        webRtcConnectionProvider = webRtcProvider,
                    ) as T
                }
            }
    }
}
