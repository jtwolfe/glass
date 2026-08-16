package com.jtwolfe.glass.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.voiceStore by preferencesDataStore(name = "glass_voice_settings")

class VoiceSettings(private val context: Context) {

    private val voiceIdKey = stringPreferencesKey("tts_voice_id")

    val voiceId: Flow<String> = context.voiceStore.data.map { prefs ->
        prefs[voiceIdKey] ?: DEFAULT_VOICE
    }

    suspend fun setVoiceId(voiceId: String) {
        if (voiceId in AVAILABLE_VOICES) {
            context.voiceStore.edit { prefs ->
                prefs[voiceIdKey] = voiceId
            }
        }
    }

    companion object {
        const val DEFAULT_VOICE = "eve"
        val AVAILABLE_VOICES = listOf("eve", "ara", "rex", "sal", "leo")

        fun voiceDisplayName(voiceId: String): String = when (voiceId.lowercase()) {
            "eve" -> "Eve"
            "ara" -> "Ara"
            "rex" -> "Rex"
            "sal" -> "Sal"
            "leo" -> "Leo"
            else -> voiceId.replaceFirstChar { it.uppercase() }
        }
    }
}
