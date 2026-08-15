package com.jtwolfe.glass.p2p

import java.io.InputStream
import java.io.OutputStream

/**
 * Unsigned varint (multiformats convention) for length-prefixed frames.
 * 
 * Encoding: 7 bits per byte, MSB = continuation flag.
 * Same as it-length-prefixed in JS libp2p.
 */
object Varint {

    fun encode(value: Long): ByteArray {
        require(value >= 0) { "Unsigned varint cannot encode negative value" }
        if (value == 0L) return byteArrayOf(0)

        val result = mutableListOf<Byte>()
        var n = value
        while (n > 0) {
            var b = (n and 0x7F).toByte()
            n = n ushr 7
            if (n > 0) {
                b = (b.toInt() or 0x80).toByte()
            }
            result.add(b)
        }
        return result.toByteArray()
    }

    fun decode(bytes: ByteArray, offset: Int = 0): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if ((b and 0x80) == 0) {
                return result to (pos - offset)
            }
            shift += 7
            if (shift > 63) {
                throw IllegalArgumentException("Varint too long")
            }
        }
        throw IllegalArgumentException("Incomplete varint")
    }

    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b == -1) throw IllegalArgumentException("Unexpected end of stream")
            result = result or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) {
                return result
            }
            shift += 7
            if (shift > 63) {
                throw IllegalArgumentException("Varint too long")
            }
        }
    }

    fun writeVarint(output: OutputStream, value: Long) {
        output.write(encode(value))
    }
}

/**
 * Length-prefixed frame codec using unsigned varint.
 * Each frame is: varint(length) + UTF-8 JSON bytes
 */
object FrameCodec {

    fun encodeFrame(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        val lengthBytes = Varint.encode(payload.size.toLong())
        return lengthBytes + payload
    }

    fun decodeFrame(input: InputStream): String {
        val length = Varint.readVarint(input)
        if (length > Int.MAX_VALUE || length < 0) {
            throw IllegalArgumentException("Frame too large: $length")
        }
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < length) {
            val n = input.read(payload, read, length.toInt() - read)
            if (n == -1) throw IllegalArgumentException("Unexpected end of frame")
            read += n
        }
        return String(payload, Charsets.UTF_8)
    }

    fun writeFrame(output: OutputStream, json: String) {
        output.write(encodeFrame(json))
        output.flush()
    }
}
