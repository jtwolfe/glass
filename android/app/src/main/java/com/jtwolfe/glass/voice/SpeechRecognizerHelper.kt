package com.jtwolfe.glass.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListeningState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR,
}

data class SpeechState(
    val listeningState: ListeningState = ListeningState.IDLE,
    val partialTranscript: String = "",
    val finalTranscript: String? = null,
    val errorMessage: String? = null,
)

class SpeechRecognizerHelper(
    private val context: Context,
    private val onFinalTranscript: (String) -> Unit,
) : RecognitionListener {

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (!isAvailable) {
            _state.value = SpeechState(
                listeningState = ListeningState.ERROR,
                errorMessage = "Speech recognition not available on this device",
            )
            return
        }

        stopListening()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(this@SpeechRecognizerHelper)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        _state.value = SpeechState(listeningState = ListeningState.LISTENING)
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.apply {
            stopListening()
            cancel()
            destroy()
        }
        recognizer = null
    }

    fun reset() {
        stopListening()
        _state.value = SpeechState()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        _state.value = _state.value.copy(listeningState = ListeningState.LISTENING)
    }

    override fun onBeginningOfSpeech() {
        _state.value = _state.value.copy(listeningState = ListeningState.LISTENING)
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        _state.value = _state.value.copy(listeningState = ListeningState.PROCESSING)
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            else -> "Recognition error ($error)"
        }
        _state.value = SpeechState(
            listeningState = ListeningState.ERROR,
            errorMessage = message,
        )
        stopListening()
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val transcript = matches?.firstOrNull()?.trim().orEmpty()
        _state.value = SpeechState(
            listeningState = ListeningState.IDLE,
            finalTranscript = transcript.ifEmpty { null },
        )
        if (transcript.isNotEmpty()) {
            onFinalTranscript(transcript)
        }
        stopListening()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val partial = matches?.firstOrNull().orEmpty()
        _state.value = _state.value.copy(partialTranscript = partial)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
