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
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Encrypted storage for glass-pair pairing data.
 *
 * Pairing model:
 * - First pair (QR scan): invite topic = SHA-256("glass-pair/v1\n{plugin_peer}\n{pub}\n{code}")
 * - After DC open: send hello, persist phone_peer + plugin_peer + paired=true
 * - Reconnect: stable topic = SHA-256("glass-pair/v1\n{plugin_peer}\n{phone_peer}")
 * - isPaired flag is independent of invite exp
 *
 * phone_peer: 52-char lowercase base32 of SHA-256(device pub)
 * - Generated once, persisted
 * - Regenerated only on unpair/clear
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

    val isPaired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    val phonePeer: String get() = getOrCreatePhonePeer()

    val pluginPeer: String? get() = prefs.getString(KEY_PLUGIN_PEER, null)

    val stableTopic: String?
        get() {
            val plugin = pluginPeer ?: return null
            val phone = phonePeer
            return computeStableTopic(plugin, phone)
        }

    suspend fun saveInvite(invite: PairingInvite) = withContext(Dispatchers.IO) {
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
            .apply()
        _state.value = invite
    }

    suspend fun markPaired(pluginPeer: String) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putBoolean(KEY_PAIRED, true)
            .putString(KEY_PLUGIN_PEER, pluginPeer)
            .putString(KEY_PAIRED_AT, Instant.now().toString())
            .apply()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_VERSION)
            .remove(KEY_PEER)
            .remove(KEY_PEER_BASE32)
            .remove(KEY_PUB)
            .remove(KEY_ADDRS)
            .remove(KEY_PROTO)
            .remove(KEY_CODE)
            .remove(KEY_PSK)
            .remove(KEY_EXP)
            .remove(KEY_PAIRED)
            .remove(KEY_PLUGIN_PEER)
            .remove(KEY_PAIRED_AT)
            .remove(KEY_PHONE_PEER)
            .remove(KEY_PHONE_PUB)
            .apply()
        _state.value = null
    }

    private fun getOrCreatePhonePeer(): String {
        val existing = prefs.getString(KEY_PHONE_PEER, null)
        if (!existing.isNullOrBlank()) return existing

        val pub = generateDevicePub()
        val peer = computePhonePeer(pub)

        prefs.edit()
            .putString(KEY_PHONE_PUB, pub)
            .putString(KEY_PHONE_PEER, peer)
            .apply()

        return peer
    }

    private fun generateDevicePub(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun computePhonePeer(pubHex: String): String {
        val pubBytes = pubHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pubBytes)
        return base32Encode(hash).lowercase()
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
        private const val VERSION_PREFIX = "glass-pair/v1"

        private const val KEY_VERSION = "version"
        private const val KEY_PEER = "peer"
        private const val KEY_PEER_BASE32 = "peer_base32"
        private const val KEY_PUB = "pub"
        private const val KEY_ADDRS = "addrs"
        private const val KEY_PROTO = "proto"
        private const val KEY_CODE = "code"
        private const val KEY_PSK = "psk"
        private const val KEY_EXP = "exp"
        private const val KEY_PAIRED = "paired"
        private const val KEY_PAIRED_AT = "paired_at"
        private const val KEY_PLUGIN_PEER = "plugin_peer"
        private const val KEY_PHONE_PEER = "phone_peer"
        private const val KEY_PHONE_PUB = "phone_pub"

        private val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray()

        fun base32Encode(data: ByteArray): String {
            val sb = StringBuilder()
            var buffer = 0
            var bitsLeft = 0

            for (byte in data) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
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

        fun computeStableTopic(pluginPeer: String, phonePeer: String): String {
            val input = "$VERSION_PREFIX\n$pluginPeer\n$phonePeer"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
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

    /**
     * Compute ntfy topic for v1 signaling.
     * topic = lowercase hex( SHA-256( UTF-8( "glass-pair/v1\n" + peer + "\n" + pub + "\n" + code ) ) )
     * 64 hex chars, unguessable from 8-char code alone.
     *
     * Returns null for v0 invites (no pub field).
     */
    val ntfyTopic: String?
        get() {
            if (version != 1) return null
            val pubHex = pub ?: return null
            return computeNtfyTopic(peer, pubHex, code)
        }

    companion object {
        private const val NTFY_TOPIC_PREFIX = "glass-pair/v1"

        fun computeNtfyTopic(peer: String, pub: String, code: String): String {
            val input = "$NTFY_TOPIC_PREFIX\n$peer\n$pub\n$code"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
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
