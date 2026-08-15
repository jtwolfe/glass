package com.jtwolfe.glass.data

import com.google.gson.annotations.SerializedName

data class Message(
    @SerializedName("id") val id: String? = null,
    @SerializedName("from") val from: String,
    @SerializedName("text") val text: String,
    @SerializedName("at") val at: String
)

data class SendMessageRequest(
    @SerializedName("from") val from: String = "jamie",
    @SerializedName("text") val text: String,
    @SerializedName("at") val at: String
)

data class RepliesResponse(
    @SerializedName("messages") val messages: List<Message>
)

data class HealthResponse(
    @SerializedName("ok") val ok: Boolean
)
