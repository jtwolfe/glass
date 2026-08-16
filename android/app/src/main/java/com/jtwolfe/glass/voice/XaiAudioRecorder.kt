package com.jtwolfe.glass.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records raw audio for xAI STT transcription.
 *
 * When the user is logged into xAI, this recorder captures microphone audio
 * as WAV bytes, which are then sent to XaiVoiceClient.transcribe().
 *
 * The audio is recorded at 16kHz mono 16-bit PCM, which is optimal for STT.
 * WAV format is used because it's universally supported by STT services.
 */
class XaiAudioRecorder(private val context: Context) {

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var pcmBuffer: ByteArrayOutputStream? = null

    @Volatile
    private var isRecording = false

    val isActive: Boolean get() = isRecording

    /**
     * Start recording audio.
     * Call this when the mic button is pressed and xAI is logged in.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = RecorderState(error = "Microphone permission required")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            _state.value = RecorderState(error = "Failed to initialize audio recorder")
            return false
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2,
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            _state.value = RecorderState(error = "Audio recorder initialization failed")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        pcmBuffer = ByteArrayOutputStream()
        isRecording = true
        _state.value = RecorderState(isRecording = true)

        audioRecord?.startRecording()

        recordingThread = thread(name = "XaiAudioRecorder") {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    pcmBuffer?.write(buffer, 0, read)
                }
            }
        }

        return true
    }

    /**
     * Stop recording and return the captured audio as WAV bytes.
     * Call this when the mic button is released.
     *
     * @return WAV audio bytes, or null if recording failed or was too short
     */
    fun stopRecording(): ByteArray? {
        if (!isRecording) return null

        isRecording = false
        _state.value = RecorderState(isRecording = false, isProcessing = true)

        try {
            recordingThread?.join(1000)
        } catch (_: InterruptedException) {
            // Continue cleanup
        }
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = pcmBuffer?.toByteArray()
        pcmBuffer = null

        _state.value = RecorderState()

        if (pcmData == null || pcmData.size < MIN_AUDIO_BYTES) {
            return null
        }

        return pcmToWav(pcmData)
    }

    /**
     * Cancel recording without returning audio.
     */
    fun cancel() {
        isRecording = false
        try {
            recordingThread?.join(500)
        } catch (_: InterruptedException) {
            // Ignore
        }
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        pcmBuffer = null
        _state.value = RecorderState()
    }

    /**
     * Convert raw PCM data to WAV format.
     */
    private fun pcmToWav(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            // RIFF header
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())

            // fmt subchunk
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size (16 for PCM)
            putShort(1) // AudioFormat (1 = PCM)
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // BlockAlign
            putShort(BITS_PER_SAMPLE.toShort())

            // data subchunk
            put("data".toByteArray())
            putInt(pcmData.size)
        }.array()

        return header + pcmData
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16

        const val MIN_AUDIO_BYTES = 1600
    }
}

data class RecorderState(
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val error: String? = null,
)
