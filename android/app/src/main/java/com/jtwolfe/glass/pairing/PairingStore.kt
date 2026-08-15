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
 *   "peer": "<52 char lowercase RFC 4648 base32 (a-z2-7) of SHA-256(device pub)>",
 *   "pub": "<64 hex X25519 ephemeral provision pub>",
 *   "code": "<8 Crockford A-Z2-7>",
 *   "exp": "<ISO-8601, ~15 min>"
 * }
 *
 * Example peer shape: 5coyrsvqsuzekhvfx3vlp7g4gr3aqphxrhqp6dllcwbi7xlfok4q
 * mDNS instance name = peer string from QR exactly.
 * No addrs. No provision handshake. No relay. No inbox URL.
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
            .putString(KEY_PEER_BASE32, invite.peerBase32)
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
            peerBase32 = prefs.getString(KEY_PEER_BASE32, null),
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
        private const val KEY_PEER_BASE32 = "peer_base32"
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
 * v1 (plugin): peer (52-char lowercase base32 a-z2-7), pub (64 hex), code (8 Crockford), exp
 * v0 (legacy inbox): peer, addrs, proto, code, psk, exp
 *
 * mDNS instance name = peer string from QR exactly (DNS label max 63).
 */
data class PairingInvite(
    val version: Int,
    val peer: String,
    val peerBase32: String? = null,
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

    /**
     * mDNS instance name: always 52-char unpadded base32 (DNS label max 63).
     * Returns peerBase32 if set, otherwise peer if already base32, otherwise derives from hex.
     */
    val mdnsInstanceName: String
        get() = peerBase32 ?: peer

    companion object {
        private val HEX_64_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val BASE32_52_REGEX = Regex("^[a-z2-7]{52}$")
        private val CROCKFORD_8_REGEX = Regex("^[A-HJ-NP-Z2-9]{8}$", RegexOption.IGNORE_CASE)
        private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

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
            val peerLower = peer.lowercase()
            val peerBase32 = when {
                BASE32_52_REGEX.matches(peerLower) -> peerLower
                HEX_64_REGEX.matches(peer) -> {
                    val bytes = hexToBytes(peer) ?: return null
                    bytesToBase32(bytes)
                }
                else -> return null
            }

            val pub = obj.optString("pub")
            if (!HEX_64_REGEX.matches(pub)) return null

            val code = obj.optString("code")
            if (!CROCKFORD_8_REGEX.matches(code)) return null

            val exp = obj.optString("exp").takeIf { it.isNotBlank() } ?: return null

            val invite = PairingInvite(
                version = 1,
                peer = peerBase32,
                peerBase32 = peerBase32,
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

        private fun hexToBytes(hex: String): ByteArray? {
            if (hex.length % 2 != 0) return null
            return try {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Convert 32 bytes to 52-char unpadded RFC 4648 base32.
         * 32 bytes * 8 bits = 256 bits → ceil(256/5) = 52 base32 chars.
         */
        private fun bytesToBase32(bytes: ByteArray): String {
            val sb = StringBuilder()
            var buffer = 0
            var bitsLeft = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bitsLeft += 8
                while (bitsLeft >= 5) {
                    bitsLeft -= 5
                    sb.append(BASE32_ALPHABET[(buffer shr bitsLeft) and 0x1F])
                }
            }
            if (bitsLeft > 0) {
                sb.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
            }
            return sb.toString()
        }
    }
}
