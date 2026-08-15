package com.jtwolfe.glass.inbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class InboxException(
    message: String,
    val httpCode: Int = 0,
    cause: Throwable? = null,
) : IOException(message, cause)

class InboxClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    fun fetchReplies(config: InboxConfig, after: String, limit: Int = 50): List<V0Message> {
        val encodedAfter = URLEncoder.encode(after, StandardCharsets.UTF_8.name())
        val request = authorized(
            config,
            "GET",
            path = "/v0/replies?after=$encodedAfter&limit=$limit",
        )
        return execute(request) { body -> V0Message.listFromEnvelope(body) }
    }

    fun postMessage(config: InboxConfig, message: V0Message): V0Message {
        val json = message.toJson().toString()
        val request = authorized(
            config,
            "POST",
            path = "/v0/messages",
            body = json,
        )
        return execute(request) { body ->
            if (body.isBlank()) message
            else V0Message.fromJson(JSONObject(body)) ?: message
        }
    }

    /** POST /v0/stt multipart field `file`. 200 {text}. 503 credential_unavailable → null. */
    fun transcribe(
        config: InboxConfig,
        audio: ByteArray,
        filename: String = "speech.m4a",
        contentType: String = "audio/mp4",
    ): String? {
        val part = MultipartBody.Part.createFormData(
            "file",
            filename,
            audio.toRequestBody(contentType.toMediaType()),
        )
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addPart(part)
            .build()
        val base = config.url.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$base/v0/stt")
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return executeOptional(request) { text ->
            JSONObject(text).optString("text").trim().ifEmpty { null }
        }
    }

    /** GET /v0/replies/{id}/audio → audio/mpeg. 503 → null (use on-device TTS). */
    fun fetchReplyAudio(config: InboxConfig, id: String): ByteArray? {
        val encodedId = URLEncoder.encode(id, StandardCharsets.UTF_8.name())
        val base = config.url.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$base/v0/replies/$encodedId/audio")
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "audio/mpeg")
            .get()
            .build()
        return executeBytesOptional(request)
    }

    fun health(baseUrl: String): Boolean {
        val base = baseUrl.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$base/v0/health")
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request) { body ->
            runCatching { JSONObject(body).optBoolean("ok") }.getOrDefault(false)
        }
    }

    private fun authorized(
        config: InboxConfig,
        method: String,
        path: String,
        body: String? = null,
    ): Request {
        val base = config.url.trim().trimEnd('/')
        val builder = Request.Builder()
            .url("$base$path")
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json")
        if (body != null) {
            builder.method(
                method,
                body.toRequestBody(JSON),
            )
            builder.header("Content-Type", "application/json")
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    private fun <T> execute(request: Request, parse: (String) -> T): T {
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw InboxException("Inbox HTTP ${response.code}", response.code)
                }
                return parse(body)
            }
        } catch (e: InboxException) {
            throw e
        } catch (e: Exception) {
            throw InboxException(e.message ?: "Inbox unreachable", cause = e)
        }
    }

    private fun <T> executeOptional(request: Request, parse: (String) -> T?): T? {
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 503) return null
                if (!response.isSuccessful) return null
                parse(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeBytesOptional(request: Request): ByteArray? {
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 503) return null
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
