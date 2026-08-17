package com.jtwolfe.glass.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wssDataStore by preferencesDataStore(name = "glass_wss_settings")

class WssSettings(private val context: Context) {

    private val urlKey = stringPreferencesKey("public_url")

    val publicUrl: Flow<String> = context.wssDataStore.data.map { prefs ->
        prefs[urlKey].orEmpty().trim()
    }

    suspend fun current(): String = publicUrl.first()

    suspend fun save(url: String) {
        val trimmed = url.trim()
        val stored = if (trimmed.isEmpty()) {
            ""
        } else {
            WssUrl.parse(trimmed)?.canonical ?: return
        }
        context.wssDataStore.edit { prefs ->
            prefs[urlKey] = stored
        }
    }

    companion object {
        const val PLACEHOLDER = "wss://glass.enphi.net/session"
    }
}
