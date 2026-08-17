package com.jtwolfe.glass.pairing

import com.jtwolfe.glass.settings.WssUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Mint alphabet: Crockford, exclude I L O U. 0 and 1 are valid.
 * Keep in lockstep with glass-peer/mint.py PHONE_CROCKFORD_8_REGEX.
 */
object Crockford {
    val CODE_8_REGEX = Regex("^[0-9A-HJKMNP-TV-Z]{8}$", RegexOption.IGNORE_CASE)

    fun isValidCode(code: String): Boolean = CODE_8_REGEX.matches(code)
}

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
    val wssHint: String? = null,
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

    companion object {
        private val HEX_64_REGEX = Regex("^[a-fA-F0-9]{64}$")
        private val BASE32_52_REGEX = Regex("^[a-z2-7]{52}$")
        private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

        fun fromQrJson(json: String): PairingInvite? {
            return try {
                val obj = JSONObject(json.trim())
                val version = when (val raw = obj.opt("v")) {
                    is Int -> raw
                    is Long -> raw.toInt()
                    is Number -> raw.toInt()
                    else -> 0
                }
                val peer = obj.opt("peer") as? String ?: return null
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

            val pub = obj.opt("pub") as? String ?: return null
            if (!HEX_64_REGEX.matches(pub)) return null

            val code = obj.opt("code") as? String ?: return null
            if (!Crockford.isValidCode(code)) return null

            val exp = (obj.opt("exp") as? String)?.takeIf { it.isNotBlank() } ?: return null
            val wssRaw = obj.opt("wss") as? String
            val wssHint = wssRaw?.let { WssUrl.parse(it)?.canonical }

            val invite = PairingInvite(
                version = 1,
                peer = peerBase32,
                peerBase32 = peerBase32,
                pub = pub.lowercase(),
                code = code.uppercase(),
                exp = exp,
                wssHint = wssHint,
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
