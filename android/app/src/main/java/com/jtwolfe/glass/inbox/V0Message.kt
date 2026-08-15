package com.jtwolfe.glass.inbox

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class V0Message(
    val from: String,
    val text: String,
    val at: String,
) {
    val isOutgoing: Boolean get() = from.equals(FROM_JAMIE, ignoreCase = true)
    val displayName: String get() = if (isOutgoing) "Jamie" else "Ashleigh"

    fun toJson(): JSONObject = JSONObject()
        .put("from", from)
        .put("text", text)
        .put("at", at)

    companion object {
        const val FROM_JAMIE = "jamie"
        const val FROM_ASHLEIGH = "ashleigh"

        fun outgoing(text: String, at: Instant = Instant.now()): V0Message =
            V0Message(from = FROM_JAMIE, text = text, at = at.toString())

        fun fromJson(obj: JSONObject): V0Message? {
            val text = obj.optString("text").trim()
            if (text.isEmpty()) return null
            val from = obj.optString("from").ifBlank { FROM_ASHLEIGH }
            val at = obj.optString("at").ifBlank { Instant.now().toString() }
            return V0Message(from = from, text = text, at = at)
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

        fun listToJson(messages: List<V0Message>): String {
            val arr = JSONArray()
            messages.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
