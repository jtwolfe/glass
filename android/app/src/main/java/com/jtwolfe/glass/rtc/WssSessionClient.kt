package com.jtwolfe.glass.rtc

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WebSocket session client for glass-pair v1.
 *
 * Product path: after pair, connect to wss://glass.enphi.net/session
 *
 * Protocol:
 * - First frame (text JSON): {"op":"hello","peer":"<phone_peer>"} with optional "pub" field
 * - After hello: same JSON as DataChannel (send/replies/agents)
 *
 * ConnectionState:
 * - CONNECTED if WSS is open OR DataChannel is open
 * - Prefer WSS when available; WebRTC DC is WiFi/fallback
 *
 * On disconnect:
 * - Reconnect WSS while still paired (same phone_peer, no remint)
 * - WSS connect failure is NOT unpair — fall back to WebRTC
 */
class WssSessionClient(
    private val onDisconnected: (() -> Unit)? = null,
    private val onConnected: (() -> Unit)? = null,
    private val onMessage: ((String) -> Unit)? = null,
) : Closeable {

    companion object {
        private const val TAG = "WssSessionClient"
        private const val WSS_URL = "wss://glass.enphi.net/session"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val webSocketRef = AtomicReference<WebSocket?>(null)
    private val closed = AtomicBoolean(false)
    private val helloSent = AtomicBoolean(false)

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    private var phonePeer: String? = null
    private var pub: String? = null

    private val pendingResponses = ConcurrentLinkedQueue<CompletableDeferred<String>>()
    private var reconnectJob: Job? = null

    private val connectDeferred = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /**
     * Connect to the WSS session endpoint.
     *
     * @param phonePeer The phone's 52-char base32 peer ID (persisted in PairingStore)
     * @param pub Optional plugin/invite pub for sha256-check
     * @return WssConnectResult with success/error status
     */
    suspend fun connect(phonePeer: String, pub: String? = null): WssConnectResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_isConnected) {
                Log.d(TAG, "connect: already connected")
                return@withContext WssConnectResult.AlreadyConnected
            }
            if (closed.get()) {
                Log.d(TAG, "connect: client closed")
                return@withContext WssConnectResult.Error("Client closed")
            }

            this@WssSessionClient.phonePeer = phonePeer
            this@WssSessionClient.pub = pub
            helloSent.set(false)

            Log.d(TAG, "connect: attempting WSS connection to $WSS_URL")

            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    connectInternal()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "connect: timeout after ${CONNECT_TIMEOUT_MS}ms")
                closeSocketOnly()
                WssConnectResult.Timeout
            } catch (e: Exception) {
                Log.w(TAG, "connect: exception ${e.javaClass.simpleName}: ${e.message}")
                closeSocketOnly()
                WssConnectResult.Error(e.message ?: "Connection failed")
            }
        }
    }

    private suspend fun connectInternal(): WssConnectResult {
        val deferred = CompletableDeferred<Boolean>()
        val errorRef = AtomicReference<String?>(null)
        connectDeferred.set(deferred)

        val request = Request.Builder()
            .url(WSS_URL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "onOpen: WSS connected, code=${response.code}")
                webSocketRef.set(webSocket)
                sendHello(webSocket)
                _isConnected = true
                connectDeferred.get()?.complete(true)
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosing: code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosed: code=$code reason=$reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val msg = "${t.javaClass.simpleName}: ${t.message} (HTTP $code)"
                Log.w(TAG, "onFailure: $msg")
                errorRef.set(msg)
                val deferred = connectDeferred.getAndSet(null)
                if (deferred != null && !deferred.isCompleted) {
                    deferred.complete(false)
                }
                handleDisconnect()
            }
        }

        client.newWebSocket(request, listener)

        val success = deferred.await()
        connectDeferred.set(null)

        return if (success) {
            Log.d(TAG, "connectInternal: success")
            WssConnectResult.Success
        } else {
            val error = errorRef.get() ?: "WebSocket connection failed"
            Log.w(TAG, "connectInternal: failed - $error")
            WssConnectResult.Error(error)
        }
    }

    private fun sendHello(webSocket: WebSocket) {
        if (helloSent.getAndSet(true)) return

        val peer = phonePeer ?: return
        val hello = JSONObject().apply {
            put("op", "hello")
            put("peer", peer)
            pub?.let { put("pub", it) }
        }
        val helloStr = hello.toString()
        Log.d(TAG, "sendHello: sending hello frame")
        webSocket.send(helloStr)
    }

    private fun handleMessage(text: String) {
        val pending = pendingResponses.poll()
        if (pending != null) {
            pending.complete(text)
        } else {
            onMessage?.invoke(text)
        }
    }

    private fun handleDisconnect() {
        val wasConnected = _isConnected
        _isConnected = false
        webSocketRef.set(null)
        helloSent.set(false)

        pendingResponses.forEach { it.complete("") }
        pendingResponses.clear()

        if (wasConnected && !closed.get()) {
            Log.d(TAG, "handleDisconnect: was connected, invoking onDisconnected")
            onDisconnected?.invoke()
        }
    }

    /**
     * Send a message to the plugin via WSS.
     *
     * @param from Sender name (always "jamie")
     * @param text Message text
     * @param at ISO-8601 timestamp
     * @param agentId Agent UUID to send to
     * @param token Optional bearer token for authorization
     * @return WssSendResult with success/error status and echoed message
     */
    suspend fun send(
        from: String,
        text: String,
        at: String,
        agentId: String? = null,
        token: String? = null,
    ): WssSendResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected) {
                return@withContext WssSendResult.NotConnected
            }

            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    sendInternal(from, text, at, agentId, token)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                WssSendResult.Error("Request timeout")
            } catch (e: Exception) {
                WssSendResult.Error(e.message ?: "Send failed")
            }
        }
    }

    private suspend fun sendInternal(
        from: String,
        text: String,
        at: String,
        agentId: String?,
        token: String?,
    ): WssSendResult {
        val ws = webSocketRef.get() ?: return WssSendResult.NotConnected

        val request = JSONObject().apply {
            put("v", 1)
            put("op", "send")
            put("from", from)
            put("text", text)
            put("at", at)
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
            if (!token.isNullOrBlank()) put("authorization", "Bearer $token")
        }

        val responseDeferred = CompletableDeferred<String>()
        pendingResponses.add(responseDeferred)

        if (!ws.send(request.toString())) {
            pendingResponses.remove(responseDeferred)
            return WssSendResult.Error("Failed to send")
        }

        val response = responseDeferred.await()
        if (response.isBlank()) {
            return WssSendResult.Error("Connection closed")
        }

        return parseSendResponse(response)
    }

    /**
     * Request available agents from the plugin via WSS.
     *
     * @return WssAgentsResult with list of agents or error
     */
    suspend fun agents(): WssAgentsResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected) {
                return@withContext WssAgentsResult.NotConnected
            }

            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    agentsInternal()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                WssAgentsResult.Error("Request timeout")
            } catch (e: Exception) {
                WssAgentsResult.Error(e.message ?: "Agents fetch failed")
            }
        }
    }

    private suspend fun agentsInternal(): WssAgentsResult {
        val ws = webSocketRef.get() ?: return WssAgentsResult.NotConnected

        val request = JSONObject().apply {
            put("v", 1)
            put("op", "agents")
        }

        val responseDeferred = CompletableDeferred<String>()
        pendingResponses.add(responseDeferred)

        if (!ws.send(request.toString())) {
            pendingResponses.remove(responseDeferred)
            return WssAgentsResult.Error("Failed to send")
        }

        val response = responseDeferred.await()
        if (response.isBlank()) {
            return WssAgentsResult.Error("Connection closed")
        }

        return parseAgentsResponse(response)
    }

    /**
     * Fetch replies from the plugin via WSS.
     *
     * @param after ISO-8601 cursor (fetch messages after this timestamp)
     * @param limit Maximum number of messages to fetch
     * @param token Optional bearer token for authorization
     * @return WssRepliesResult with success/error status and messages
     */
    suspend fun replies(
        after: String,
        limit: Int = 50,
        token: String? = null,
    ): WssRepliesResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected) {
                return@withContext WssRepliesResult.NotConnected
            }

            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    repliesInternal(after, limit, token)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                WssRepliesResult.Error("Request timeout")
            } catch (e: Exception) {
                WssRepliesResult.Error(e.message ?: "Replies fetch failed")
            }
        }
    }

    private suspend fun repliesInternal(after: String, limit: Int, token: String?): WssRepliesResult {
        val ws = webSocketRef.get() ?: return WssRepliesResult.NotConnected

        val request = JSONObject().apply {
            put("v", 1)
            put("op", "replies")
            put("after", after)
            put("limit", limit)
            if (!token.isNullOrBlank()) put("authorization", "Bearer $token")
        }

        val responseDeferred = CompletableDeferred<String>()
        pendingResponses.add(responseDeferred)

        if (!ws.send(request.toString())) {
            pendingResponses.remove(responseDeferred)
            return WssRepliesResult.Error("Failed to send")
        }

        val response = responseDeferred.await()
        if (response.isBlank()) {
            return WssRepliesResult.Error("Connection closed")
        }

        return parseRepliesResponse(response)
    }

    private fun parseSendResponse(line: String): WssSendResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                WssSendResult.Success(
                    id = json.optString("id", ""),
                    from = json.optString("from", ""),
                    text = json.optString("text", ""),
                    at = json.optString("at", ""),
                )
            } else {
                val error = json.optString("error", "").ifBlank { null }
                WssSendResult.Error(error ?: "Send rejected")
            }
        } catch (_: Exception) {
            WssSendResult.Error("Invalid response")
        }
    }

    private fun parseAgentsResponse(line: String): WssAgentsResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                val agentsArray = json.optJSONArray("agents") ?: JSONArray()
                val agents = mutableListOf<WssAgent>()
                for (i in 0 until agentsArray.length()) {
                    val agent = agentsArray.optJSONObject(i) ?: continue
                    agents.add(
                        WssAgent(
                            id = agent.optString("id", ""),
                            name = agent.optString("name", ""),
                        )
                    )
                }
                WssAgentsResult.Success(agents)
            } else {
                val error = json.optString("error", "").ifBlank { null }
                WssAgentsResult.Error(error ?: "Agents request rejected")
            }
        } catch (_: Exception) {
            WssAgentsResult.Error("Invalid response")
        }
    }

    private fun parseRepliesResponse(line: String): WssRepliesResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                val messagesArray = json.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<WssMessage>()
                for (i in 0 until messagesArray.length()) {
                    val msg = messagesArray.optJSONObject(i) ?: continue
                    messages.add(
                        WssMessage(
                            id = msg.optString("id", ""),
                            from = msg.optString("from", ""),
                            text = msg.optString("text", ""),
                            at = msg.optString("at", ""),
                        )
                    )
                }
                WssRepliesResult.Success(messages)
            } else {
                val error = json.optString("error", "").ifBlank { null }
                WssRepliesResult.Error(error ?: "Replies rejected")
            }
        } catch (_: Exception) {
            WssRepliesResult.Error("Invalid response")
        }
    }

    /**
     * Start automatic reconnection attempts.
     * Called when WSS disconnects while still paired.
     */
    fun startReconnect(phonePeer: String, pub: String?) {
        if (closed.get()) return
        reconnectJob?.cancel()

        this.phonePeer = phonePeer
        this.pub = pub

        reconnectJob = scope.launch {
            var attempts = 0
            while (attempts < MAX_RECONNECT_ATTEMPTS && !closed.get() && !_isConnected) {
                attempts++
                val delayMs = RECONNECT_BASE_DELAY_MS * attempts
                delay(delayMs)

                if (closed.get() || _isConnected) break

                val result = connect(phonePeer, pub)
                if (result is WssConnectResult.Success || result is WssConnectResult.AlreadyConnected) {
                    break
                }
            }
        }
    }

    fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun disconnect() {
        closeSocketOnly()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelReconnect()
        closeSocketOnly()
        client.dispatcher.executorService.shutdown()
        scope.cancel()
    }

    private fun closeSocketOnly() {
        webSocketRef.getAndSet(null)?.close(1000, null)
        _isConnected = false
        helloSent.set(false)
        connectDeferred.getAndSet(null)?.complete(false)
        pendingResponses.forEach { it.complete("") }
        pendingResponses.clear()
    }
}

sealed class WssConnectResult {
    data object Success : WssConnectResult()
    data object AlreadyConnected : WssConnectResult()
    data object Timeout : WssConnectResult()
    data class Error(val message: String) : WssConnectResult()
}

sealed class WssSendResult {
    data class Success(
        val id: String,
        val from: String,
        val text: String,
        val at: String,
    ) : WssSendResult()
    data object NotConnected : WssSendResult()
    data class Error(val message: String) : WssSendResult()
}

sealed class WssRepliesResult {
    data class Success(val messages: List<WssMessage>) : WssRepliesResult()
    data object NotConnected : WssRepliesResult()
    data class Error(val message: String) : WssRepliesResult()
}

data class WssMessage(
    val id: String,
    val from: String,
    val text: String,
    val at: String,
)

sealed class WssAgentsResult {
    data class Success(val agents: List<WssAgent>) : WssAgentsResult()
    data object NotConnected : WssAgentsResult()
    data class Error(val message: String) : WssAgentsResult()
}

data class WssAgent(
    val id: String,
    val name: String,
)
