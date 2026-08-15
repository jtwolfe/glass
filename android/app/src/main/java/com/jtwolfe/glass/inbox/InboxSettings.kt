package com.jtwolfe.glass.inbox

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jtwolfe.glass.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.inboxDataStore by preferencesDataStore(name = "glass_inbox")

data class InboxConfig(
    val url: String,
    val token: String,
    val source: String,
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() && token.isNotBlank() &&
            !url.contains("inbox.example.invalid")
}

class InboxSettings(private val context: Context) {

    private val urlKey = stringPreferencesKey("inbox_url")
    private val tokenKey = stringPreferencesKey("inbox_token")

    val config: Flow<InboxConfig> = context.inboxDataStore.data.map { prefs ->
        val storedUrl = prefs[urlKey].orEmpty().trim()
        val storedToken = prefs[tokenKey].orEmpty().trim()
        val buildUrl = BuildConfig.INBOX_URL.trim()
        val buildToken = BuildConfig.INBOX_TOKEN.trim()
        when {
            storedUrl.isNotEmpty() || storedToken.isNotEmpty() -> InboxConfig(
                url = storedUrl.ifEmpty { buildUrl },
                token = storedToken.ifEmpty { buildToken },
                source = "settings",
            )
            buildUrl.isNotEmpty() || buildToken.isNotEmpty() -> InboxConfig(
                url = buildUrl,
                token = buildToken,
                source = "local.properties",
            )
            else -> InboxConfig(url = "", token = "", source = "unset")
        }
    }

    suspend fun save(url: String, token: String) {
        context.inboxDataStore.edit { prefs ->
            prefs[urlKey] = url.trim()
            prefs[tokenKey] = token.trim()
        }
    }
}
