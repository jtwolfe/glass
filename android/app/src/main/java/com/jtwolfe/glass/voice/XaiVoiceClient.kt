package com.jtwolfe.glass.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct xAI STT/TTS client using the phone-stored OAuth bearer.
 *
 * Endpoints:
 * - POST https://api.x.ai/v1/stt  model=grok-stt (multipart/audio)
 * - POST https://api.x.ai/v1/tts  voice=eve
 *
 * The xAI bearer NEVER leaves the phone:
 * - Never sent to inbox public URL
 * - Never sent to Quay's tunnel
 * - Only used for direct api.x.ai calls
 */
class XaiVoiceClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    /**
     * Transcribe audio using xAI STT.
     *
     * @param bearer The xAI OAuth access token (from XaiAuthStore)
     * @param audio Raw audio bytes
     * @param filename Suggested filename with extension
     * @param contentType MIME type of the audio
     * @return Transcribed text, or null on error
     */
    fun transcribe(
        bearer: String,
        audio: ByteArray,
        filename: String = "speech.m4a",
        contentType: String = "audio/mp4",
    ): String? {
        if (bearer.isBlank() || audio.isEmpty()) return null

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart(
                "file",
                filename,
                audio.toRequestBody(contentType.toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(STT_URL)
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .post(multipartBody)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                json.optString("text").trim().ifEmpty { null }
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Synthesize speech using xAI TTS.
     *
     * @param bearer The xAI OAuth access token (from XaiAuthStore)
     * @param text Text to synthesize
     * @param voice TTS voice (default: eve)
     * @return Audio bytes (MP3), or null on error
     */
    fun synthesize(
        bearer: String,
        text: String,
        voice: String = TTS_VOICE,
    ): ByteArray? {
        if (bearer.isBlank() || text.isBlank()) return null

        val jsonBody = JSONObject()
            .put("model", TTS_MODEL)
            .put("input", text)
            .put("voice", voice)
            .toString()

        val request = Request.Builder()
            .url(TTS_URL)
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes()
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        const val STT_URL = "https://api.x.ai/v1/stt"
        const val TTS_URL = "https://api.x.ai/v1/tts"
        const val STT_MODEL = "grok-stt"
        const val TTS_MODEL = "grok-tts"
        const val TTS_VOICE = "eve"
    }
}
