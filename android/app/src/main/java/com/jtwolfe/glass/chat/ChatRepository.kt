package com.jtwolfe.glass.chat

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jtwolfe.glass.inbox.InboxClient
import com.jtwolfe.glass.inbox.InboxConfig
import com.jtwolfe.glass.inbox.V0Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.localChatStore by preferencesDataStore(name = "glass_local_chat")

class ChatRepository(
    private val context: Context,
    private val client: InboxClient = InboxClient(),
) {
    private val messagesKey = stringPreferencesKey("messages_json")

    suspend fun loadLocal(): List<V0Message> {
        val raw = context.localChatStore.data.map { it[messagesKey].orEmpty() }.first()
        return runCatching { V0Message.listFromJson(raw) }.getOrDefault(emptyList())
    }

    suspend fun saveLocal(messages: List<V0Message>) {
        context.localChatStore.edit { it[messagesKey] = V0Message.listToJson(messages) }
    }

    suspend fun pullReplies(config: InboxConfig, after: String): List<V0Message> =
        client.fetchReplies(config, after)

    suspend fun sendRemote(config: InboxConfig, message: V0Message): V0Message =
        client.postMessage(config, message)
}
