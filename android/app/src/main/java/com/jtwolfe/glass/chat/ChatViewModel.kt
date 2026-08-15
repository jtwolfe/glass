package com.jtwolfe.glass.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jtwolfe.glass.GlassApplication
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.InboxConfig
import com.jtwolfe.glass.inbox.InboxSettings
import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.voice.XaiVoiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isListening: Boolean = false,
    val partialTranscript: String = "",
)

class ChatViewModel(
    application: Application,
    private val settings: InboxSettings,
    private val repository: ChatRepository,
    private val xaiAuthStore: XaiAuthStore,
    private val xaiVoiceClient: XaiVoiceClient = XaiVoiceClient(),
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _newAshleighMessages = Channel<V0Message>(Channel.BUFFERED)
    val newAshleighMessages = _newAshleighMessages.receiveAsFlow()

    private var pollJob: Job? = null
    private var afterCursor: String = EPOCH
    private var lastSpokenAt: String = EPOCH

    init {
        viewModelScope.launch {
            val local = repository.loadLocal()
            _state.update { it.copy(messages = local) }
            afterCursor = local.maxOfOrNull { it.at } ?: EPOCH
            lastSpokenAt = local
                .filter { it.from.equals(V0Message.FROM_ASHLEIGH, ignoreCase = true) }
                .maxOfOrNull { it.at } ?: EPOCH
            settings.config.collect { config ->
                _state.update { ui ->
                    ui.copy(
                        inbox = config,
                        status = if (config.isConfigured) {
                            "Inbox (${config.source})"
                        } else {
                            "Local-only — inbox URL/token unset"
                        },
                    )
                }
                restartPolling(config)
                if (config.isConfigured) {
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

    fun refresh() {
        val config = _state.value.inbox
        if (config.isConfigured) {
            viewModelScope.launch { refreshRemote(config) }
        }
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty() || _state.value.sending) return
        val outgoing = V0Message.outgoing(text)
        viewModelScope.launch {
            _state.update {
                it.copy(
                    draft = "",
                    sending = true,
                    error = null,
                    messages = it.messages + outgoing,
                )
            }
            persistLocal()
            val config = _state.value.inbox
            if (config.isConfigured) {
                runCatching { repository.sendRemote(config, outgoing) }
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
        if (config.isConfigured) {
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
        val bearer = xaiAuthStore.getFreshAccessToken() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { xaiVoiceClient.synthesize(bearer, text) }.getOrNull()
        }
    }

    /**
     * Transcribe audio. Prefers xAI STT when logged in, falls back to inbox STT.
     * Returns null if both fail (caller should use on-device recognition).
     */
    suspend fun transcribeAudio(audio: ByteArray): String? {
        if (audio.isEmpty()) return null

        // Prefer xAI STT when logged in (bearer never leaves the phone)
        val xaiBearer = xaiAuthStore.getFreshAccessToken()
        if (xaiBearer != null) {
            val xaiResult = withContext(Dispatchers.IO) {
                runCatching { xaiVoiceClient.transcribe(xaiBearer, audio) }.getOrNull()
            }
            if (!xaiResult.isNullOrBlank()) {
                return xaiResult
            }
        }

        // Fall back to inbox STT (503 returns null → use on-device)
        val config = _state.value.inbox
        if (config.isConfigured) {
            return runCatching { repository.transcribe(config, audio) }.getOrNull()
        }

        return null
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
        if (!config.isConfigured) return
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
                    return ChatViewModel(
                        application = app,
                        settings = app.inboxSettings,
                        repository = ChatRepository(app),
                        xaiAuthStore = app.xaiAuthStore,
                    ) as T
                }
            }
    }
}
