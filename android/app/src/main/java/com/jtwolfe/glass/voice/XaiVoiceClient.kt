package com.jtwolfe.glass.voice

import android.util.Log
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
 * Endpoints (per https://docs.x.ai/developers/model-capabilities/audio):
 * - POST https://api.x.ai/v1/stt  multipart: language, vad_threshold, file (LAST)
 * - POST https://api.x.ai/v1/tts  JSON: {text, voice_id, language}
 *
 * The xAI bearer NEVER leaves the phone:
 * - Never sent to a third-party inbox
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
     * Transcribe audio using xAI STT (grok-stt service).
     *
     * Request shape per docs: multipart with optional fields first, file LAST.
     * No `model` field - grok-stt is the service name, not a form field.
     * WAV is a supported container - no audio_format/sample_rate needed.
     *
     * @param bearer The xAI OAuth access token (from XaiAuthStore)
     * @param audio Raw audio bytes (WAV format)
     * @param filename Suggested filename with extension
     * @param contentType MIME type of the audio
     * @return SttResult with transcript on success, or error details on failure
     */
    fun transcribe(
        bearer: String,
        audio: ByteArray,
        filename: String = "speech.wav",
        contentType: String = "audio/wav",
    ): SttResult {
        if (bearer.isBlank()) {
            return SttResult.Error(0, "No bearer token")
        }
        if (audio.isEmpty()) {
            return SttResult.Error(0, "Empty audio")
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("language", "en")
            .addFormDataPart("vad_threshold", VAD_THRESHOLD)
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
                val body = response.body?.string() ?: ""
                val code = response.code

                Log.d(TAG, "STT response: $code ${body.take(200)}")

                if (!response.isSuccessful) {
                    val errorMsg = parseErrorMessage(body) ?: "HTTP $code"
                    return SttResult.Error(code, errorMsg)
                }

                val json = runCatching { JSONObject(body) }.getOrNull()
                val text = json?.optString("text")?.trim()

                if (text.isNullOrBlank()) {
                    return SttResult.Error(code, "Empty transcript (VAD filtered or silence)")
                }

                SttResult.Success(text)
            }
        } catch (e: IOException) {
            Log.e(TAG, "STT network error", e)
            SttResult.Error(0, "Network error: ${e.message}")
        }
    }

    /**
     * Synthesize speech using xAI TTS.
     *
     * Request shape per docs: JSON {text, voice_id, language}
     *
     * @param bearer The xAI OAuth access token (from XaiAuthStore)
     * @param text Text to synthesize
     * @param voiceId TTS voice ID (default: eve)
     * @return Audio bytes (MP3), or null on error
     */
    fun synthesize(
        bearer: String,
        text: String,
        voiceId: String = TTS_VOICE,
    ): TtsResult {
        if (bearer.isBlank()) {
            return TtsResult.Error(0, "No bearer token")
        }
        if (text.isBlank()) {
            return TtsResult.Error(0, "Empty text")
        }

        val jsonBody = JSONObject()
            .put("text", text)
            .put("voice_id", voiceId)
            .put("language", "en")
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
                val code = response.code

                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    Log.d(TAG, "TTS error: $code ${body.take(200)}")
                    val errorMsg = parseErrorMessage(body) ?: "HTTP $code"
                    return TtsResult.Error(code, errorMsg)
                }

                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    return TtsResult.Error(code, "Empty audio response")
                }

                TtsResult.Success(bytes)
            }
        } catch (e: IOException) {
            Log.e(TAG, "TTS network error", e)
            TtsResult.Error(0, "Network error: ${e.message}")
        }
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optJSONObject("error")?.optString("message")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "XaiVoiceClient"
        const val STT_URL = "https://api.x.ai/v1/stt"
        const val TTS_URL = "https://api.x.ai/v1/tts"
        const val TTS_VOICE = "eve"
        const val VAD_THRESHOLD = "0.08"
    }
}

sealed class SttResult {
    data class Success(val text: String) : SttResult()
    data class Error(val httpCode: Int, val message: String) : SttResult() {
        val displayMessage: String
            get() = when (httpCode) {
                401 -> "xAI auth failed (401) — re-login may be needed"
                403 -> "xAI access denied (403)"
                429 -> "xAI rate limited (429)"
                in 500..599 -> "xAI server error ($httpCode)"
                0 -> message
                else -> "STT failed: $message"
            }
    }
}

sealed class TtsResult {
    data class Success(val audio: ByteArray) : TtsResult()
    data class Error(val httpCode: Int, val message: String) : TtsResult()
}
