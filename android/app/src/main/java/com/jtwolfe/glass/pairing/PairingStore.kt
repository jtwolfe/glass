package com.jtwolfe.glass.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jtwolfe.glass.chat.Watermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/**
 * Encrypted storage for glass-pair pairing data.
 *
 * phone_peer is 52-char lowercase base32 of SHA-256(device pub).
 * Generated once, persisted; regenerated only on unpair/clear.
 * isPaired is independent of invite exp.
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

    val sessionId: String? get() = prefs.getString(KEY_SESSION_ID, null)

    val lastSpokenSeq: Long get() = prefs.getLong(KEY_LAST_SPOKEN_SEQ, DEFAULT_LAST_SPOKEN_SEQ)

    val lastSeenSeq: Long get() = prefs.getLong(KEY_LAST_SEEN_SEQ, DEFAULT_LAST_SEEN_SEQ)

    val lastWssUrl: String? get() = prefs.getString(KEY_LAST_WSS_URL, null)

    private val watermarkMutex = Mutex()

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

    suspend fun persistLastSeen(sessionId: String, lastSeenSeq: Long) = withContext(Dispatchers.IO) {
        watermarkMutex.withLock {
            // Do not write sessionId here — persistHelloSession owns session changes
            // so a catch-up persist cannot skip the remint watermark reset.
            if (prefs.getString(KEY_SESSION_ID, null) != sessionId) return@withLock
            val current = prefs.getLong(KEY_LAST_SEEN_SEQ, DEFAULT_LAST_SEEN_SEQ)
            val next = Watermark.advance(current, lastSeenSeq)
            if (next == current) return@withLock
            prefs.edit()
                .putLong(KEY_LAST_SEEN_SEQ, next)
                .commit()
        }
    }

    suspend fun persistLastSpoken(sessionId: String, lastSpokenSeq: Long) = withContext(Dispatchers.IO) {
        watermarkMutex.withLock {
            if (prefs.getString(KEY_SESSION_ID, null) != sessionId) return@withLock
            val current = prefs.getLong(KEY_LAST_SPOKEN_SEQ, DEFAULT_LAST_SPOKEN_SEQ)
            val next = Watermark.advance(current, lastSpokenSeq)
            if (next == current) return@withLock
            prefs.edit()
                .putLong(KEY_LAST_SPOKEN_SEQ, next)
                .commit()
        }
    }

    suspend fun persistWatermark(sessionId: String, lastSpokenSeq: Long) =
        persistLastSpoken(sessionId, lastSpokenSeq)

    suspend fun persistHelloSession(sessionId: String, firstSeq: Long) = withContext(Dispatchers.IO) {
        watermarkMutex.withLock {
            val reset = Watermark.helloResetSeq(
                previousSessionId = prefs.getString(KEY_SESSION_ID, null),
                newSessionId = sessionId,
                helloSeq = firstSeq,
            )
            if (reset == null) {
                prefs.edit().putString(KEY_SESSION_ID, sessionId).commit()
            } else {
                prefs.edit()
                    .putString(KEY_SESSION_ID, sessionId)
                    .putLong(KEY_LAST_SPOKEN_SEQ, reset)
                    .putLong(KEY_LAST_SEEN_SEQ, reset)
                    .commit()
            }
        }
    }

    suspend fun saveLastWssUrl(url: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_LAST_WSS_URL, url).apply()
    }

    suspend fun clearWatermark() = withContext(Dispatchers.IO) {
        watermarkMutex.withLock {
            prefs.edit()
                .remove(KEY_SESSION_ID)
                .remove(KEY_LAST_SPOKEN_SEQ)
                .remove(KEY_LAST_SEEN_SEQ)
                .commit()
        }
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
            .remove(KEY_SESSION_ID)
            .remove(KEY_LAST_SPOKEN_SEQ)
            .remove(KEY_LAST_SEEN_SEQ)
            .remove(KEY_LAST_WSS_URL)
            .commit()
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
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_SPOKEN_SEQ = "last_spoken_seq"
        private const val KEY_LAST_SEEN_SEQ = "last_seen_seq"
        private const val KEY_LAST_WSS_URL = "last_wss_url"
        const val DEFAULT_LAST_SPOKEN_SEQ = -1L
        const val DEFAULT_LAST_SEEN_SEQ = -1L

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
    }
}
