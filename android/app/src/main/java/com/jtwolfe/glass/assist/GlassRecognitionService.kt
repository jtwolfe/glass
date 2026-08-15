package com.jtwolfe.glass.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Stub recognizer — Glass is a chat assistant, not a speech pipeline. */
class GlassRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
