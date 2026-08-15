package com.jtwolfe.glass.data

import com.jtwolfe.glass.network.InboxClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.format.DateTimeFormatter

class ChatRepository {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isLocalOnly = MutableStateFlow(!InboxClient.isConfigured)
    val isLocalOnly: StateFlow<Boolean> = _isLocalOnly.asStateFlow()

    private var lastPollTimestamp: String = Instant.EPOCH.toString()

    private fun currentIsoTimestamp(): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    suspend fun sendMessage(text: String): Result<Message> {
        val timestamp = currentIsoTimestamp()
        val request = SendMessageRequest(from = "jamie", text = text, at = timestamp)

        val api = InboxClient.api
        if (api == null) {
            val localMessage = Message(
                id = "local-${System.currentTimeMillis()}",
                from = "jamie",
                text = text,
                at = timestamp
            )
            _messages.value = _messages.value + localMessage
            return Result.success(localMessage)
        }

        return try {
            val response = api.sendMessage(request)
            if (response.isSuccessful && response.body() != null) {
                val message = response.body()!!
                _messages.value = _messages.value + message
                if (message.at > lastPollTimestamp) {
                    lastPollTimestamp = message.at
                }
                Result.success(message)
            } else {
                val localMessage = Message(
                    id = "local-${System.currentTimeMillis()}",
                    from = "jamie",
                    text = text,
                    at = timestamp
                )
                _messages.value = _messages.value + localMessage
                Result.failure(Exception("Failed to send: ${response.code()}"))
            }
        } catch (e: Exception) {
            val localMessage = Message(
                id = "local-${System.currentTimeMillis()}",
                from = "jamie",
                text = text,
                at = timestamp
            )
            _messages.value = _messages.value + localMessage
            Result.failure(e)
        }
    }

    suspend fun pollReplies(): Result<List<Message>> {
        val api = InboxClient.api ?: return Result.success(emptyList())

        return try {
            val response = api.getReplies(after = lastPollTimestamp, limit = 50)
            if (response.isSuccessful && response.body() != null) {
                val newMessages = response.body()!!.messages
                    .filter { it.from == "ashleigh" }
                    .filter { msg -> _messages.value.none { it.id == msg.id } }

                if (newMessages.isNotEmpty()) {
                    _messages.value = (_messages.value + newMessages)
                        .sortedBy { it.at }
                    newMessages.maxByOrNull { it.at }?.let {
                        lastPollTimestamp = it.at
                    }
                }
                Result.success(newMessages)
            } else {
                Result.failure(Exception("Failed to poll: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        lastPollTimestamp = Instant.EPOCH.toString()
    }
}
