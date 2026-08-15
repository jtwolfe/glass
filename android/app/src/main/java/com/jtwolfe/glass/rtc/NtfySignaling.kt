package com.jtwolfe.glass.rtc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ntfy.sh signaling for glass-pair v1 WebRTC pairing.
 *
 * Topic computation:
 *   topic = lowercase hex( SHA-256( UTF-8( "glass-pair/v1\n" + peer + "\n" + pub + "\n" + code ) ) )
 *   64 hex chars, unguessable from 8-char code alone.
 *
 * Signaling messages (JSON text):
 *   {"v":1,"t":"offer","sdp":"<sdp>"}
 *   {"v":1,"t":"answer","sdp":"<sdp>"}
 *   {"v":1,"t":"ice","cand":"<candidate>"}
 *
 * Publish: POST https://ntfy.sh/{topic}  body = JSON (text/plain)
 * Subscribe: GET https://ntfy.sh/{topic}/json  (NDJSON stream)
 *   - Parse "event":"message" lines, payload is in "message" field
 *   - Ignore "event":"open", "event":"keepalive"
 *
 * Chat NEVER goes through ntfy. Once DataChannel opens, ntfy is done.
 */
class NtfySignaling(
    private val peer: String,
    private val pub: String,
    private val code: String,
) {
    companion object {
        private const val NTFY_HOST = "https://ntfy.sh"
        private const val VERSION_PREFIX = "glass-pair/v1"

        fun computeTopic(peer: String, pub: String, code: String): String {
            val input = "$VERSION_PREFIX\n$peer\n$pub\n$code"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    val topic: String = computeTopic(peer, pub, code)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val shortClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun publishOffer(sdp: String): Boolean = publish("offer", sdp)

    suspend fun publishAnswer(sdp: String): Boolean = publish("answer", sdp)

    suspend fun publishIce(candidate: String): Boolean {
        return withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("v", 1)
                .put("t", "ice")
                .put("cand", candidate)
                .toString()
            doPublish(json)
        }
    }

    private suspend fun publish(type: String, sdp: String): Boolean {
        return withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("v", 1)
                .put("t", type)
                .put("sdp", sdp)
                .toString()
            doPublish(json)
        }
    }

    private fun doPublish(body: String): Boolean {
        val request = Request.Builder()
            .url("$NTFY_HOST/$topic")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        return try {
            shortClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    fun subscribe(): Flow<SignalingMessage> = callbackFlow {
        val request = Request.Builder()
            .url("$NTFY_HOST/$topic/json")
            .get()
            .build()

        val call = client.newCall(request)
        val response = call.execute()

        if (!response.isSuccessful) {
            close(Exception("ntfy subscribe failed: ${response.code}"))
            return@callbackFlow
        }

        val body = response.body
        if (body == null) {
            close(Exception("ntfy subscribe: empty body"))
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        try {
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                if (line.isBlank()) continue

                val parsed = parseNtfyLine(line)
                if (parsed != null) {
                    trySend(parsed)
                }
            }
        } catch (_: Exception) {
            // Stream ended or cancelled
        } finally {
            reader.close()
            response.close()
        }

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseNtfyLine(line: String): SignalingMessage? {
        return try {
            val obj = JSONObject(line)
            val event = obj.optString("event", "")
            if (event != "message") return null

            val message = obj.optString("message", "")
            if (message.isBlank()) return null

            val inner = JSONObject(message)
            val v = inner.optInt("v", -1)
            if (v != 1) return null

            val type = inner.optString("t", "")
            when (type) {
                "offer" -> SignalingMessage.Offer(inner.optString("sdp", ""))
                "answer" -> SignalingMessage.Answer(inner.optString("sdp", ""))
                "ice" -> SignalingMessage.Ice(inner.optString("cand", ""))
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        shortClient.dispatcher.executorService.shutdown()
    }
}

sealed class SignalingMessage {
    data class Offer(val sdp: String) : SignalingMessage()
    data class Answer(val sdp: String) : SignalingMessage()
    data class Ice(val candidate: String) : SignalingMessage()
}
