package com.jtwolfe.glass.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Encrypted storage for glass-pair/v0 pairing data.
 *
 * QR payload from inbox (Quay's contract):
 * {
 *   "v": 0,
 *   "peer": "<inbox libp2p peer id>",
 *   "addrs": ["/ip4/10.0.0.1/tcp/4001", "/p2p/<relay>/p2p-circuit"],
 *   "proto": "/glass/inbox/v0",
 *   "code": "K7M2Q9WH",
 *   "psk": "<64 hex chars, 32-byte swarm key>",
 *   "exp": "2026-08-16T08:00:00Z"
 * }
 *
 * The PSK is stored ONLY in EncryptedSharedPreferences:
 * - Never committed to git
 * - Never logged
 * - Used for Noise + PSK handshake after pair
 */
class PairingStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "glass_pairing",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _state = MutableStateFlow(loadInvite())
    val state: Flow<PairingInvite?> = _state.asStateFlow()

    val currentInvite: PairingInvite? get() = _state.value

    val isPaired: Boolean get() = currentInvite?.isValid == true

    suspend fun save(invite: PairingInvite) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putInt(KEY_VERSION, invite.version)
            .putString(KEY_PEER, invite.peer)
            .putString(KEY_ADDRS, invite.addrs.joinToString("\n"))
            .putString(KEY_PROTO, invite.proto)
            .putString(KEY_CODE, invite.code)
            .putString(KEY_PSK, invite.psk)
            .putString(KEY_EXP, invite.exp)
            .putString(KEY_PAIRED_AT, Instant.now().toString())
            .apply()
        _state.value = invite
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        _state.value = null
    }

    private fun loadInvite(): PairingInvite? {
        val peer = prefs.getString(KEY_PEER, null)
        if (peer.isNullOrBlank()) return null
        return PairingInvite(
            version = prefs.getInt(KEY_VERSION, 0),
            peer = peer,
            addrs = prefs.getString(KEY_ADDRS, "")?.split("\n")?.filter { it.isNotBlank() }
                ?: emptyList(),
            proto = prefs.getString(KEY_PROTO, null) ?: PROTO_V0,
            code = prefs.getString(KEY_CODE, null) ?: "",
            psk = prefs.getString(KEY_PSK, null),
            exp = prefs.getString(KEY_EXP, null),
        )
    }

    companion object {
        const val PROTO_V0 = "/glass/inbox/v0"

        private const val KEY_VERSION = "version"
        private const val KEY_PEER = "peer"
        private const val KEY_ADDRS = "addrs"
        private const val KEY_PROTO = "proto"
        private const val KEY_CODE = "code"
        private const val KEY_PSK = "psk"
        private const val KEY_EXP = "exp"
        private const val KEY_PAIRED_AT = "paired_at"
    }
}

/**
 * glass-pair/v0 invite from QR or short-code exchange.
 */
data class PairingInvite(
    val version: Int,
    val peer: String,
    val addrs: List<String>,
    val proto: String,
    val code: String,
    val psk: String?,
    val exp: String?,
) {
    val isExpired: Boolean
        get() {
            val expStr = exp ?: return false
            return try {
                val expInstant = Instant.parse(expStr)
                Instant.now().isAfter(expInstant)
            } catch (_: DateTimeParseException) {
                false
            }
        }

    val isValid: Boolean
        get() = peer.isNotBlank() && !isExpired

    val shortCode: String
        get() = code.take(8).uppercase()

    val hasCircuitRelay: Boolean
        get() = addrs.any { it.contains("p2p-circuit") }

    companion object {
        fun fromQrJson(json: String): PairingInvite? {
            return try {
                val obj = JSONObject(json.trim())
                val version = obj.optInt("v", 0)
                val peer = obj.optString("peer")
                if (peer.isBlank()) return null

                val addrsArray = obj.optJSONArray("addrs") ?: JSONArray()
                val addrs = buildList {
                    for (i in 0 until addrsArray.length()) {
                        addrsArray.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }

                val proto = obj.optString("proto").ifBlank { PairingStore.PROTO_V0 }
                val code = obj.optString("code")
                val psk = obj.optString("psk").takeIf { it.isNotBlank() }
                val exp = obj.optString("exp").takeIf { it.isNotBlank() }

                PairingInvite(
                    version = version,
                    peer = peer,
                    addrs = addrs,
                    proto = proto,
                    code = code,
                    psk = psk,
                    exp = exp,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
