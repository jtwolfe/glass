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
 * Encrypted storage for glass-pair pairing data.
 *
 * v1 QR payload from plugin (Jamie's machine):
 * {
 *   "v": 1,
 *   "peer": "<64 hex device id, SHA-256 of plugin Ed25519 device public key>",
 *   "pub": "<64 hex X25519 ephemeral provision pub>",
 *   "code": "<8 Crockford A-Z2-7>",
 *   "exp": "<ISO-8601, ~15 min>"
 * }
 *
 * v0 (legacy) QR payload:
 * {
 *   "v": 0,
 *   "peer": "<inbox libp2p peer id>",
 *   "addrs": ["/ip4/10.0.0.1/tcp/4001", ...],
 *   "proto": "/glass/inbox/v0",
 *   "code": "K7M2Q9WH",
 *   "psk": "<64 hex chars>",
 *   "exp": "2026-08-16T08:00:00Z"
 * }
 *
 * Secrets (pub, psk) are stored ONLY in EncryptedSharedPreferences:
 * - Never committed to git
 * - Never logged
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
            .putString(KEY_PUB, invite.pub)
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
            pub = prefs.getString(KEY_PUB, null),
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
        private const val KEY_PUB = "pub"
        private const val KEY_ADDRS = "addrs"
        private const val KEY_PROTO = "proto"
        private const val KEY_CODE = "code"
        private const val KEY_PSK = "psk"
        private const val KEY_EXP = "exp"
        private const val KEY_PAIRED_AT = "paired_at"
    }
}

/**
 * glass-pair invite from QR or short-code exchange.
 *
 * v1 (plugin): peer (64 hex), pub (64 hex), code (8 Crockford), exp
 * v0 (legacy inbox): peer, addrs, proto, code, psk, exp
 */
data class PairingInvite(
    val version: Int,
    val peer: String,
    val pub: String? = null,
    val addrs: List<String> = emptyList(),
    val proto: String = PairingStore.PROTO_V0,
    val code: String,
    val psk: String? = null,
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

    val isV1: Boolean
        get() = version == 1

    val shortCode: String
        get() = code.take(8).uppercase()

    val hasCircuitRelay: Boolean
        get() = addrs.any { it.contains("p2p-circuit") }

    companion object {
        private val HEX_64_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val CROCKFORD_8_REGEX = Regex("^[A-HJ-NP-Z2-9]{8}$", RegexOption.IGNORE_CASE)

        fun fromQrJson(json: String): PairingInvite? {
            return try {
                val obj = JSONObject(json.trim())
                val version = obj.optInt("v", 0)
                val peer = obj.optString("peer")
                if (peer.isBlank()) return null

                when (version) {
                    1 -> parseV1(obj, peer)
                    else -> parseV0(obj, peer)
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun parseV1(obj: JSONObject, peer: String): PairingInvite? {
            if (!HEX_64_REGEX.matches(peer)) return null

            val pub = obj.optString("pub")
            if (!HEX_64_REGEX.matches(pub)) return null

            val code = obj.optString("code")
            if (!CROCKFORD_8_REGEX.matches(code)) return null

            val exp = obj.optString("exp").takeIf { it.isNotBlank() } ?: return null

            val invite = PairingInvite(
                version = 1,
                peer = peer.lowercase(),
                pub = pub.lowercase(),
                code = code.uppercase(),
                exp = exp,
            )

            if (invite.isExpired) return null
            return invite
        }

        private fun parseV0(obj: JSONObject, peer: String): PairingInvite? {
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

            return PairingInvite(
                version = 0,
                peer = peer,
                addrs = addrs,
                proto = proto,
                code = code,
                psk = psk,
                exp = exp,
            )
        }
    }
}
