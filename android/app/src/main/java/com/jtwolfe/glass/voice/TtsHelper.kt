package com.jtwolfe.glass.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class TtsState {
    INITIALIZING,
    READY,
    SPEAKING,
    ERROR,
}

class TtsHelper(
    context: Context,
    private val onReady: () -> Unit = {},
    onDone: () -> Unit = {},
) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context, this)
    private val utteranceCounter = AtomicInteger(0)

    @Volatile
    var onDone: () -> Unit = onDone

    private val _state = MutableStateFlow(TtsState.INITIALIZING)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                _state.value = TtsState.SPEAKING
            }

            override fun onDone(utteranceId: String?) {
                terminal()
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                terminal()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                terminal()
            }
        })
    }

    private fun terminal() {
        _isSpeaking.value = false
        if (_state.value != TtsState.INITIALIZING) {
            _state.value = TtsState.READY
        }
        onDone()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            _state.value = if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                TtsState.ERROR
            } else {
                onReady()
                TtsState.READY
            }
        } else {
            _state.value = TtsState.ERROR
        }
    }

    fun speak(text: String): Boolean {
        if (_state.value != TtsState.READY && _state.value != TtsState.SPEAKING) {
            return false
        }
        val utteranceId = "tts_${utteranceCounter.incrementAndGet()}"
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        // ERROR does not invoke the progress listener; caller must finish the job.
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        tts.stop()
        _isSpeaking.value = false
        if (_state.value == TtsState.SPEAKING) {
            _state.value = TtsState.READY
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
