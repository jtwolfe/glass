package com.jtwolfe.glass.service

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.jtwolfe.glass.ui.AssistActivity

class GlassVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return GlassVoiceInteractionSession(this)
    }
}

class GlassVoiceInteractionSession(
    private val service: VoiceInteractionSessionService
) : VoiceInteractionSession(service) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launchAssistActivity()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        launchAssistActivity()
    }

    private fun launchAssistActivity() {
        val intent = Intent(service, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        service.startActivity(intent)
        hide()
    }
}
