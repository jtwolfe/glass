package com.jtwolfe.glass.chat

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.pairing.PairingStore
import com.jtwolfe.glass.pairing.PluginMessage
import com.jtwolfe.glass.pairing.SendResult
import com.jtwolfe.glass.pairing.SessionClient
import com.jtwolfe.glass.pairing.SessionError
import com.jtwolfe.glass.voice.ReplySpeechQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.localChatStore by preferencesDataStore(name = "glass_local_chat")

interface ChatPersist {
    suspend fun load(): List<V0Message>
    suspend fun save(messages: List<V0Message>)
}

interface ChatWatermarks {
    val sessionId: String?
    val lastSpokenSeq: Long
    suspend fun persistLastSeen(sessionId: String, seq: Long)
    suspend fun clear()
}

/**
 * Sole mutator of the local chat thread. Application-scoped.
 * ViewModels collect [messages] / [agentError] and must not persist their own list.
 */
class ChatRepository(
    private val sessionClient: SessionClient? = null,
    private val persist: ChatPersist,
    private val watermarks: ChatWatermarks,
    private val offerSpeech: (text: String, seq: Long, sessionId: String) -> Unit = { _, _, _ -> },
    private val isForeground: () -> Boolean = { false },
    hydrateScope: CoroutineScope? = null,
) {
    constructor(
        context: Context,
        sessionClient: SessionClient? = null,
        pairingStore: PairingStore,
        speechQueue: ReplySpeechQueue,
        isForeground: () -> Boolean,
        hydrateScope: CoroutineScope,
    ) : this(
        sessionClient = sessionClient,
        persist = DataStoreChatPersist(context.applicationContext),
        watermarks = PairingChatWatermarks(pairingStore),
        offerSpeech = { text, seq, sid -> speechQueue.offer(text, seq, sid) },
        isForeground = isForeground,
        hydrateScope = hydrateScope,
    )

    private val mutex = Mutex()
    private var hydrated = false

    private val _messages = MutableStateFlow<List<V0Message>>(emptyList())
    val messages: StateFlow<List<V0Message>> = _messages.asStateFlow()

    private val _agentError = MutableStateFlow<SessionError?>(null)
    val agentError: StateFlow<SessionError?> = _agentError.asStateFlow()

    init {
        hydrateScope?.launch { hydrate() }
    }

    suspend fun hydrate() {
        mutex.withLock { ensureHydratedLocked() }
    }

    suspend fun appendOutgoing(msg: V0Message) {
        mutex.withLock {
            ensureHydratedLocked()
            val next = _messages.value + msg
            _messages.value = next
            persist.save(next)
        }
    }

    suspend fun acceptReply(msg: PluginMessage) {
        val sessionId = sessionClient?.lastHelloSessionId?.takeIf { it.isNotBlank() }
            ?: msg.sessionId
        val v0 = V0Message(
            id = msg.id,
            from = msg.from,
            text = msg.text,
            at = msg.at,
            seq = msg.seq,
            sessionId = sessionId,
        )
        val inserted = mutex.withLock {
            ensureHydratedLocked()
            val next = insertOrDedupeReply(_messages.value, v0) ?: return@withLock false
            _messages.value = next
            if (sessionId.isNotBlank()) {
                watermarks.persistLastSeen(sessionId, msg.seq)
            }
            persist.save(next)
            true
        }
        if (!inserted) return

        val currentSid = sessionClient?.lastHelloSessionId ?: watermarks.sessionId
        val lastSpoken = if (watermarks.sessionId == currentSid) {
            watermarks.lastSpokenSeq
        } else {
            PairingStore.DEFAULT_LAST_SPOKEN_SEQ
        }
        val live = msg.live && !msg.catchUp
        val speak = Watermark.shouldSpeak(
            seq = v0.seq,
            sessionId = sessionId,
            currentSessionId = currentSid,
            lastSpokenSeq = lastSpoken,
            live = live,
            foreground = isForeground(),
        )
        Log.d(TAG, "onReply seq=${msg.seq} live=${msg.live} catchUp=${msg.catchUp} speak=$speak")
        if (speak && sessionId.isNotBlank() && isForeground()) {
            offerSpeech(msg.text, msg.seq, sessionId)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            hydrated = true
            _messages.value = emptyList()
            persist.save(emptyList())
            watermarks.clear()
            _agentError.value = null
        }
    }

    suspend fun setAgentError(err: SessionError?) {
        _agentError.value = err
    }

    suspend fun dismissAgentError() {
        _agentError.value = null
    }

    suspend fun sendRemote(message: V0Message, agentId: String? = null): SendResult {
        val session = sessionClient ?: return SendResult.NotConnected
        if (!session.isHelloed) return SendResult.NotConnected
        return session.send(
            from = V0Message.FROM_JAMIE,
            text = message.text,
            at = message.at,
            agentId = agentId,
        )
    }

    private suspend fun ensureHydratedLocked() {
        if (hydrated) return
        _messages.value = persist.load()
        hydrated = true
    }

    private class DataStoreChatPersist(private val context: Context) : ChatPersist {
        private val messagesKey = stringPreferencesKey("messages_json")

        override suspend fun load(): List<V0Message> {
            val raw = context.localChatStore.data.map { it[messagesKey].orEmpty() }.first()
            return runCatching { V0Message.listFromJson(raw) }.getOrDefault(emptyList())
        }

        override suspend fun save(messages: List<V0Message>) {
            context.localChatStore.edit { it[messagesKey] = V0Message.listToJson(messages) }
        }
    }

    private class PairingChatWatermarks(
        private val store: PairingStore,
    ) : ChatWatermarks {
        override val sessionId: String? get() = store.sessionId
        override val lastSpokenSeq: Long get() = store.lastSpokenSeq
        override suspend fun persistLastSeen(sessionId: String, seq: Long) {
            store.persistLastSeen(sessionId, seq)
        }
        override suspend fun clear() {
            store.clearWatermark()
        }
    }

    companion object {
        private const val TAG = "ChatRepository"
    }
}

/** Insert in seq order among the same sessionId. Null if (sessionId, seq) already exists. */
internal fun insertOrDedupeReply(
    messages: List<V0Message>,
    incoming: V0Message,
): List<V0Message>? {
    val sid = incoming.sessionId
    val seq = incoming.seq
    if (sid.isNullOrBlank() || seq == null) {
        return messages + incoming
    }
    if (messages.any { it.sessionId == sid && it.seq == seq }) {
        return null
    }
    val insertAt = messages.indexOfFirst { row ->
        row.sessionId == sid && row.seq != null && row.seq > seq
    }
    return if (insertAt < 0) {
        messages + incoming
    } else {
        messages.toMutableList().apply { add(insertAt, incoming) }
    }
}
