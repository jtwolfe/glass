package com.jtwolfe.glass.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jtwolfe.glass.inbox.InboxClient
import com.jtwolfe.glass.inbox.InboxConfig
import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.p2p.InboxStreamClient
import com.jtwolfe.glass.p2p.StreamResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.localChatStore by preferencesDataStore(name = "glass_local_chat")

class ChatRepository(
    private val context: Context,
    private val client: InboxClient = InboxClient(),
    private val streamClient: InboxStreamClient? = null,
) {
    private val messagesKey = stringPreferencesKey("messages_json")

    suspend fun loadLocal(): List<V0Message> {
        val raw = context.localChatStore.data.map { it[messagesKey].orEmpty() }.first()
        return runCatching { V0Message.listFromJson(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveLocal(messages: List<V0Message>) {
        context.localChatStore.edit { it[messagesKey] = V0Message.listToJson(messages) }
    }

    suspend fun pullReplies(config: InboxConfig, after: String): List<V0Message> {
        // Use P2P stream if connected, otherwise fall back to HTTPS
        val stream = streamClient
        if (stream != null && stream.isConnected && stream.isPaired) {
            val response = stream.getReplies(config.token, after)
            if (response is StreamResponse.Success && response.status in 200..299) {
                return V0Message.listFromEnvelope(response.body)
            }
        }
        return client.fetchReplies(config, after)
    }

    suspend fun sendRemote(config: InboxConfig, message: V0Message): V0Message {
        // Use P2P stream if connected, otherwise fall back to HTTPS
        val stream = streamClient
        if (stream != null && stream.isConnected && stream.isPaired) {
            val response = stream.postMessage(
                token = config.token,
                from = message.from,
                text = message.text,
                at = message.at,
            )
            if (response is StreamResponse.Success && response.status in 200..299) {
                return runCatching {
                    val json = JSONObject(response.body)
                    V0Message.fromJson(json) ?: message
                }.getOrDefault(message)
            }
        }
        return client.postMessage(config, message)
    }

    suspend fun transcribe(config: InboxConfig, audio: ByteArray): String? =
        client.transcribe(config, audio)

    suspend fun fetchReplyAudio(config: InboxConfig, id: String): ByteArray? =
        client.fetchReplyAudio(config, id)
}
