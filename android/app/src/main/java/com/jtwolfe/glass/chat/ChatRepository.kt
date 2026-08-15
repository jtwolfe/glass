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
import com.jtwolfe.glass.pairing.PluginClient
import com.jtwolfe.glass.pairing.RepliesResult
import com.jtwolfe.glass.pairing.SendResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.localChatStore by preferencesDataStore(name = "glass_local_chat")

class ChatRepository(
    private val context: Context,
    private val client: InboxClient = InboxClient(),
    private val streamClient: InboxStreamClient? = null,
    private val pluginClient: PluginClient? = null,
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
        // v1: Use PluginClient TCP socket when paired (never /v0/messages)
        val plugin = pluginClient
        if (plugin != null && plugin.isConnected && plugin.isPaired) {
            val token = config.token.takeIf { it.isNotBlank() }
            val result = plugin.replies(after = after, limit = 50, token = token)
            if (result is RepliesResult.Success) {
                return result.messages.map { msg ->
                    V0Message(
                        id = msg.id.takeIf { it.isNotBlank() },
                        from = msg.from,
                        text = msg.text,
                        at = msg.at,
                    )
                }
            }
        }

        // v0: Use P2P stream if connected, otherwise fall back to HTTPS
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
        // v1: Use PluginClient TCP socket when paired (from=jamie only)
        val plugin = pluginClient
        if (plugin != null && plugin.isConnected && plugin.isPaired) {
            val token = config.token.takeIf { it.isNotBlank() }
            val result = plugin.send(
                from = message.from,
                text = message.text,
                at = message.at,
                token = token,
            )
            if (result is SendResult.Success) {
                return V0Message(
                    id = result.id.takeIf { it.isNotBlank() },
                    from = result.from,
                    text = result.text,
                    at = result.at,
                )
            }
        }

        // v0: Use P2P stream if connected, otherwise fall back to HTTPS
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
