package com.jtwolfe.glass.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP client for glass-pair v1 plugin protocol.
 *
 * Raw TCP, one UTF-8 JSON object per line. No varint, no libp2p, no relay.
 *
 * Operations:
 * - pair:    {"v":1,"op":"pair","code":"<8 Crockford>"}
 * - send:    {"v":1,"op":"send","from":"jamie","text":"...","at":"<ISO-8601>"}
 * - replies: {"v":1,"op":"replies","after":"<ISO-8601>","limit":50}
 *
 * If authorization is available, add "authorization":"Bearer <token>" on send/replies.
 */
class PluginClient : Closeable {

    private val mutex = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var _isPaired = false
    val isPaired: Boolean get() = _isPaired

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    @Volatile
    private var connectedHost: String? = null

    @Volatile
    private var connectedPort: Int = 0

    /**
     * Connect to the plugin and perform pairing handshake.
     *
     * @param host Resolved host from mDNS (no baked IPs)
     * @param port Resolved port from mDNS
     * @param code 8-char Crockford code from QR
     * @return PluginResult with success/error status
     */
    suspend fun connectAndPair(host: String, port: Int, code: String): PluginResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                closeInternal()

                val result = withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                    connectAndPairInternal(host, port, code)
                }
                result ?: PluginResult.Timeout
            }
        }

    private fun connectAndPairInternal(host: String, port: Int, code: String): PluginResult {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS

            val w = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))

            socket = sock
            writer = w
            reader = r
            connectedHost = host
            connectedPort = port
            _isConnected = true

            val request = JSONObject()
                .put("v", 1)
                .put("op", "pair")
                .put("code", code)

            w.write(request.toString())
            w.write("\n")
            w.flush()

            val responseLine = r.readLine()
                ?: return PluginResult.Error("Connection closed by plugin").also { closeInternal() }

            return parsePairResponse(responseLine)
        } catch (e: java.net.SocketTimeoutException) {
            closeInternal()
            return PluginResult.Timeout
        } catch (e: java.net.ConnectException) {
            closeInternal()
            return PluginResult.Error("Could not connect to plugin: ${e.message}")
        } catch (e: Exception) {
            closeInternal()
            return PluginResult.Error(e.message ?: "Connection failed")
        }
    }

    private fun parsePairResponse(line: String): PluginResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                _isPaired = true
                PluginResult.Success
            } else {
                val error = json.optString("error", "").ifBlank { null }
                closeInternal()
                PluginResult.Rejected(error ?: "Plugin did not accept pairing")
            }
        } catch (_: Exception) {
            closeInternal()
            PluginResult.Error("Invalid response from plugin")
        }
    }

    /**
     * Send a message to the plugin.
     *
     * @param from Sender name (always "jamie")
     * @param text Message text
     * @param at ISO-8601 timestamp
     * @param token Optional bearer token for authorization
     * @return SendResult with success/error status and echoed message
     */
    suspend fun send(
        from: String,
        text: String,
        at: String,
        token: String? = null,
    ): SendResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected || !_isPaired) {
                return@withContext SendResult.NotConnected
            }

            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                sendInternal(from, text, at, token)
            }
            result ?: SendResult.Error("Request timeout")
        }
    }

    private fun sendInternal(from: String, text: String, at: String, token: String?): SendResult {
        val w = writer ?: return SendResult.NotConnected
        val r = reader ?: return SendResult.NotConnected

        try {
            val request = JSONObject()
                .put("v", 1)
                .put("op", "send")
                .put("from", from)
                .put("text", text)
                .put("at", at)

            if (!token.isNullOrBlank()) {
                request.put("authorization", "Bearer $token")
            }

            w.write(request.toString())
            w.write("\n")
            w.flush()

            val responseLine = r.readLine()
                ?: return SendResult.Error("Connection closed").also { markDisconnected() }

            return parseSendResponse(responseLine)
        } catch (e: Exception) {
            markDisconnected()
            return SendResult.Error(e.message ?: "Send failed")
        }
    }

    private fun parseSendResponse(line: String): SendResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                SendResult.Success(
                    id = json.optString("id", ""),
                    from = json.optString("from", ""),
                    text = json.optString("text", ""),
                    at = json.optString("at", ""),
                )
            } else {
                val error = json.optString("error", "").ifBlank { null }
                SendResult.Error(error ?: "Send rejected")
            }
        } catch (_: Exception) {
            SendResult.Error("Invalid response")
        }
    }

    /**
     * Fetch replies from the plugin.
     *
     * @param after ISO-8601 cursor (fetch messages after this timestamp)
     * @param limit Maximum number of messages to fetch
     * @param token Optional bearer token for authorization
     * @return RepliesResult with success/error status and messages
     */
    suspend fun replies(
        after: String,
        limit: Int = 50,
        token: String? = null,
    ): RepliesResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected || !_isPaired) {
                return@withContext RepliesResult.NotConnected
            }

            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                repliesInternal(after, limit, token)
            }
            result ?: RepliesResult.Error("Request timeout")
        }
    }

    private fun repliesInternal(after: String, limit: Int, token: String?): RepliesResult {
        val w = writer ?: return RepliesResult.NotConnected
        val r = reader ?: return RepliesResult.NotConnected

        try {
            val request = JSONObject()
                .put("v", 1)
                .put("op", "replies")
                .put("after", after)
                .put("limit", limit)

            if (!token.isNullOrBlank()) {
                request.put("authorization", "Bearer $token")
            }

            w.write(request.toString())
            w.write("\n")
            w.flush()

            val responseLine = r.readLine()
                ?: return RepliesResult.Error("Connection closed").also { markDisconnected() }

            return parseRepliesResponse(responseLine)
        } catch (e: Exception) {
            markDisconnected()
            return RepliesResult.Error(e.message ?: "Replies fetch failed")
        }
    }

    private fun parseRepliesResponse(line: String): RepliesResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                val messagesArray = json.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<PluginMessage>()
                for (i in 0 until messagesArray.length()) {
                    val msg = messagesArray.optJSONObject(i) ?: continue
                    messages.add(
                        PluginMessage(
                            id = msg.optString("id", ""),
                            from = msg.optString("from", ""),
                            text = msg.optString("text", ""),
                            at = msg.optString("at", ""),
                        )
                    )
                }
                RepliesResult.Success(messages)
            } else {
                val error = json.optString("error", "").ifBlank { null }
                RepliesResult.Error(error ?: "Replies rejected")
            }
        } catch (_: Exception) {
            RepliesResult.Error("Invalid response")
        }
    }

    private fun markDisconnected() {
        _isConnected = false
        _isPaired = false
    }

    private fun closeInternal() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { socket?.close() }
        socket = null
        writer = null
        reader = null
        _isConnected = false
        _isPaired = false
        connectedHost = null
        connectedPort = 0
    }

    override fun close() {
        closeInternal()
    }

    fun disconnect() {
        closeInternal()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val OVERALL_TIMEOUT_MS = 10000L
        private const val REQUEST_TIMEOUT_MS = 30000L
    }
}

sealed class PluginResult {
    data object Success : PluginResult()
    data object Timeout : PluginResult()
    data class Rejected(val reason: String) : PluginResult()
    data class Error(val message: String) : PluginResult()
}

sealed class SendResult {
    data class Success(
        val id: String,
        val from: String,
        val text: String,
        val at: String,
    ) : SendResult()
    data object NotConnected : SendResult()
    data class Error(val message: String) : SendResult()
}

sealed class RepliesResult {
    data class Success(val messages: List<PluginMessage>) : RepliesResult()
    data object NotConnected : RepliesResult()
    data class Error(val message: String) : RepliesResult()
}

data class PluginMessage(
    val id: String,
    val from: String,
    val text: String,
    val at: String,
)
