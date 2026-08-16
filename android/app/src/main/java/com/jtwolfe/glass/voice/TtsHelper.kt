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
    private val onDone: () -> Unit = {},
) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context, this)
    private val utteranceCounter = AtomicInteger(0)

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
                _isSpeaking.value = false
                _state.value = TtsState.READY
                onDone()
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                _state.value = TtsState.ERROR
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                _state.value = TtsState.ERROR
            }
        })
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

    fun speak(text: String) {
        if (_state.value != TtsState.READY && _state.value != TtsState.SPEAKING) return
        val utteranceId = "ashleigh_${utteranceCounter.incrementAndGet()}"
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
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
