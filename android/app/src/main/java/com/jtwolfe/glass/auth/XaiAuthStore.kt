package com.jtwolfe.glass.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Encrypted storage for xAI OAuth tokens.
 *
 * Tokens are stored in EncryptedSharedPreferences on the device and NEVER:
 * - Committed to git
 * - Logged
 * - Sent to the inbox public URL or Quay's tunnel
 *
 * The xAI bearer is used only for direct calls to api.x.ai (STT/TTS).
 */
class XaiAuthStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "glass_xai_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _state = MutableStateFlow(loadBundle())
    val state: Flow<XaiAuthBundle?> = _state.asStateFlow()

    val currentBundle: XaiAuthBundle? get() = _state.value

    val isLoggedIn: Boolean get() = currentBundle?.accessToken?.isNotBlank() == true

    val email: String? get() = currentBundle?.email

    suspend fun save(bundle: XaiAuthBundle) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, bundle.accessToken)
            .putString(KEY_REFRESH_TOKEN, bundle.refreshToken)
            .putString(KEY_EXPIRES_AT, bundle.expiresAt)
            .putString(KEY_EMAIL, bundle.email)
            .putString(KEY_SUBJECT, bundle.subject)
            .putString(KEY_ID_TOKEN, bundle.idToken)
            .putString(KEY_OBTAINED_AT, bundle.obtainedAt)
            .putString(KEY_UPDATED_AT, Instant.now().toString())
            .apply()
        _state.value = bundle
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        _state.value = null
    }

    fun getFreshAccessToken(): String? {
        val bundle = _state.value ?: return null
        if (bundle.accessToken.isBlank()) return null
        if (bundle.isExpired) return null
        return bundle.accessToken
    }

    private fun loadBundle(): XaiAuthBundle? {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (accessToken.isNullOrBlank()) return null
        return XaiAuthBundle(
            accessToken = accessToken,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null),
            expiresAt = prefs.getString(KEY_EXPIRES_AT, null),
            email = prefs.getString(KEY_EMAIL, null),
            subject = prefs.getString(KEY_SUBJECT, null),
            idToken = prefs.getString(KEY_ID_TOKEN, null),
            obtainedAt = prefs.getString(KEY_OBTAINED_AT, null),
        )
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_EMAIL = "email"
        private const val KEY_SUBJECT = "subject"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_OBTAINED_AT = "obtained_at"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

data class XaiAuthBundle(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: String?,
    val email: String?,
    val subject: String?,
    val idToken: String?,
    val obtainedAt: String?,
) {
    val isExpired: Boolean
        get() {
            val exp = expiresAt ?: return false
            return try {
                val expInstant = Instant.parse(exp)
                Instant.now().isAfter(expInstant.minusSeconds(SKEW_SECONDS))
            } catch (_: DateTimeParseException) {
                false
            }
        }

    companion object {
        private const val SKEW_SECONDS = 120L

        fun fromTokenResponse(
            accessToken: String,
            refreshToken: String?,
            expiresIn: Int?,
            idToken: String?,
        ): XaiAuthBundle {
            val now = Instant.now()
            val expiresAt = expiresIn?.let { now.plusSeconds(it.toLong()).toString() }
            val (email, subject) = parseIdTokenClaims(idToken)
            return XaiAuthBundle(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                email = email,
                subject = subject,
                idToken = idToken,
                obtainedAt = now.toString(),
            )
        }

        private fun parseIdTokenClaims(idToken: String?): Pair<String?, String?> {
            if (idToken.isNullOrBlank()) return null to null
            val parts = idToken.split(".")
            if (parts.size < 2) return null to null
            return try {
                val payload = parts[1]
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                val decoded = Base64.getUrlDecoder().decode(padded)
                val json = JSONObject(String(decoded, Charsets.UTF_8))
                val email = json.optString("email").takeIf { it.isNotBlank() }
                val sub = json.optString("sub").takeIf { it.isNotBlank() }
                email to sub
            } catch (_: Exception) {
                null to null
            }
        }
    }
}
