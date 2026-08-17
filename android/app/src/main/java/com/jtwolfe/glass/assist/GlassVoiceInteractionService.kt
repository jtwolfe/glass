package com.jtwolfe.glass.assist

import android.service.voice.VoiceInteractionService

/**
 * Default-assistant entry. Long-press / assist is handled by
 * [GlassVoiceInteractionSession], which opens the chat.
 */
class GlassVoiceInteractionService : VoiceInteractionService()
