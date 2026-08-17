package com.jtwolfe.glass.inbox

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class V0Message(
    val id: String? = null,
    val from: String,
    val text: String,
    val at: String,
    val seq: Long? = null,
    val sessionId: String? = null,
) {
    val isOutgoing: Boolean get() = from.equals(FROM_JAMIE, ignoreCase = true)
    val displayName: String get() = if (isOutgoing) "Jamie" else from

    fun toJson(): JSONObject = JSONObject()
        .put("from", from)
        .put("text", text)
        .put("at", at)
        .also {
            if (!id.isNullOrBlank()) it.put("id", id)
            if (seq != null) it.put("seq", seq)
            if (!sessionId.isNullOrBlank()) it.put("sessionId", sessionId)
        }

    companion object {
        const val FROM_JAMIE = "jamie"
        const val FROM_ASSISTANT = "ashleigh"

        fun outgoing(text: String, at: Instant = Instant.now()): V0Message =
            V0Message(from = FROM_JAMIE, text = text, at = at.toString())

        fun fromJson(obj: JSONObject): V0Message? {
            val text = obj.optString("text").trim()
            if (text.isEmpty()) return null
            val from = obj.optString("from").ifBlank { FROM_ASSISTANT }
            val at = obj.optString("at").ifBlank { Instant.now().toString() }
            val id = obj.optString("id").ifBlank { null }
            val seq = if (obj.has("seq") && !obj.isNull("seq")) obj.optLong("seq") else null
            val sessionId = obj.optString("sessionId").ifBlank { null }
            return V0Message(
                id = id,
                from = from,
                text = text,
                at = at,
                seq = seq,
                sessionId = sessionId,
            )
        }

        fun listFromJson(raw: String): List<V0Message> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptyList()
            return if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        fromJson(arr.getJSONObject(i))?.let(::add)
                    }
                }
            } else {
                listOfNotNull(fromJson(JSONObject(trimmed)))
            }
        }

        fun listFromEnvelope(raw: String): List<V0Message> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has("messages")) {
                    return listFromJson(obj.getJSONArray("messages").toString())
                }
                return listOfNotNull(fromJson(obj))
            }
            return listFromJson(trimmed)
        }

        fun listToJson(messages: List<V0Message>): String {
            val arr = JSONArray()
            messages.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
