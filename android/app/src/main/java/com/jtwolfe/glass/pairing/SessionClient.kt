package com.jtwolfe.glass.pairing

import android.util.Log
import com.jtwolfe.glass.settings.WssUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SessionClient : Closeable {

    var onReply: ((PluginMessage) -> Unit)? = null
    var onError: ((SessionError) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pairAck = AtomicReference<CompletableDeferred<JSONObject>?>(null)
    private val opened = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val generation = AtomicInteger(0)
    private val socketRef = AtomicReference<WebSocket?>(null)

    @Volatile
    private var readMode = ReadMode.SESSION

    @Volatile
    private var _isHelloed = false
    val isHelloed: Boolean get() = _isHelloed

    @Volatile
    private var _lastHelloSessionId: String? = null
    val lastHelloSessionId: String? get() = _lastHelloSessionId

    @Volatile
    private var lastFailure: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    suspend fun connectAndPair(wssUrl: String, code: String): PluginResult =
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                val url = WssUrl.parse(wssUrl)
                    ?: return@withLock PluginResult.Error("Invalid session URL")
                if (!Crockford.isValidCode(code)) {
                    return@withLock PluginResult.Rejected("rejected")
                }
                val open = openSocket(url.canonical, ReadMode.WAIT_PAIR)
                if (open !is PluginResult.Success) return@withLock open

                val ackDeferred = CompletableDeferred<JSONObject>()
                pairAck.set(ackDeferred)
                val pair = JSONObject().put("v", 1).put("code", code)
                val gen = generation.get()
                val sent = writeMutex.withLock { writeUnlocked(pair) }
                if (!sent) {
                    pairAck.set(null)
                    closeSocket(notify = false, expectedGen = gen)
                    return@withLock PluginResult.Error("Failed to send pair")
                }
                val ack = awaitJson(ackDeferred, PAIR_ACK_TIMEOUT_MS)
                pairAck.set(null)
                if (ack == null) {
                    closeSocket(notify = false, expectedGen = gen)
                    return@withLock PluginResult.Timeout
                }
                if (jsonBoolean(ack, "ok") != true) {
                    val reason = jsonString(ack, "error") ?: "rejected"
                    closeSocket(notify = false, expectedGen = gen)
                    return@withLock PluginResult.Rejected(reason)
                }
                readMode = ReadMode.SESSION
                PluginResult.Success
            }
        }

    suspend fun connectSession(wssUrl: String): PluginResult =
        withContext(Dispatchers.IO) {
            connectMutex.withLock {
                if (_isHelloed && socketRef.get() != null) return@withLock PluginResult.Success
                val url = WssUrl.parse(wssUrl)
                    ?: return@withLock PluginResult.Error("Invalid session URL")
                openSocket(url.canonical, ReadMode.SESSION)
            }
        }

    suspend fun hello(
        phonePeer: String,
        pub: String? = null,
        lastSeenSeq: Long = -1,
        sessionId: String? = null,
    ): HelloResult =
        withContext(Dispatchers.IO) {
            if (socketRef.get() == null) return@withContext HelloResult.NotConnected
            if (_isHelloed) {
                val sid = _lastHelloSessionId ?: return@withContext HelloResult.NotConnected
                return@withContext HelloResult.Success(sid, 0)
            }
            if (phonePeer.length != 52) return@withContext HelloResult.Rejected("rejected")
            val gen = generation.get()
            val id = newId()
            val payload = buildHelloPayload(
                id = id,
                phonePeer = phonePeer,
                pub = pub,
                lastSeenSeq = lastSeenSeq,
                sessionId = sessionId,
            )
            Log.d(TAG, "hello peer=${phonePeer.take(12)}")
            val resp = request(payload, HELLO_TIMEOUT_MS)
            if (generation.get() != gen) return@withContext HelloResult.NotConnected
            if (resp == null) {
                closeSocket(notify = false, expectedGen = gen)
                return@withContext HelloResult.Timeout
            }
            when (val parsed = parseHello(resp)) {
                is HelloResult.Success -> {
                    _lastHelloSessionId = parsed.sessionId
                    _isHelloed = true
                    parsed
                }
                else -> {
                    if (_isHelloed) parsed else {
                        closeSocket(notify = false, expectedGen = gen)
                        parsed
                    }
                }
            }
        }

    suspend fun send(from: String, text: String, at: String, agentId: String?): SendResult =
        withContext(Dispatchers.IO) {
            if (!_isHelloed || socketRef.get() == null) return@withContext SendResult.NotConnected
            val id = newId()
            val payload = JSONObject()
                .put("v", 1)
                .put("op", "send")
                .put("id", id)
                .put("from", from)
                .put("text", text)
                .put("at", at)
            if (!agentId.isNullOrBlank()) payload.put("agentId", agentId)
            val resp = request(payload, REQUEST_TIMEOUT_MS)
                ?: return@withContext SendResult.Error("Request timeout")
            parseSend(resp)
        }

    suspend fun agents(): AgentsResult =
        withContext(Dispatchers.IO) {
            if (!_isHelloed || socketRef.get() == null) return@withContext AgentsResult.NotConnected
            val payload = JSONObject()
                .put("v", 1)
                .put("op", "agents")
                .put("id", newId())
            val resp = request(payload, REQUEST_TIMEOUT_MS)
                ?: return@withContext AgentsResult.Error("Request timeout")
            parseAgentsResult(resp)
        }

    suspend fun ping(): Boolean =
        withContext(Dispatchers.IO) {
            if (!_isHelloed || socketRef.get() == null) return@withContext false
            val payload = JSONObject()
                .put("v", 1)
                .put("op", "ping")
                .put("id", newId())
            val resp = request(payload, REQUEST_TIMEOUT_MS) ?: return@withContext false
            jsonBoolean(resp, "ok") == true
        }

    fun disconnect() {
        closeSocket(notify = _isHelloed, expectedGen = null)
    }

    override fun close() {
        closeSocket(notify = false, expectedGen = null)
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private suspend fun openSocket(canonicalUrl: String, mode: ReadMode): PluginResult {
        beginSocket()
        readMode = mode
        lastFailure = null
        val gen = generation.get()
        val openDeferred = CompletableDeferred<Boolean>()
        opened.set(openDeferred)
        val request = Request.Builder().url(canonicalUrl).build()
        val ws = client.newWebSocket(request, Listener(gen))
        socketRef.set(ws)
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { openDeferred.await() }
        opened.compareAndSet(openDeferred, null)
        if (generation.get() != gen) {
            return PluginResult.Error(lastFailure ?: "Connection replaced")
        }
        if (ok != true) {
            // Invalidate so a late onOpen cannot attach to this generation.
            generation.incrementAndGet()
            ws.cancel()
            if (socketRef.get() === ws) socketRef.set(null)
            return if (ok == null) {
                PluginResult.Timeout
            } else {
                PluginResult.Error(lastFailure ?: "WebSocket connection failed")
            }
        }
        return PluginResult.Success
    }

    internal fun beginSocket() {
        generation.incrementAndGet()
        val previous = socketRef.getAndSet(null)
        previous?.cancel()
        _isHelloed = false
        _lastHelloSessionId = null
        failInflight()
        pairAck.getAndSet(null)?.let { if (!it.isCompleted) it.cancel() }
    }

    private fun closeSocket(notify: Boolean, expectedGen: Int?) {
        if (expectedGen != null) {
            if (!generation.compareAndSet(expectedGen, expectedGen + 1)) return
        } else {
            generation.incrementAndGet()
        }
        val wasHelloed = _isHelloed
        _isHelloed = false
        _lastHelloSessionId = null
        val ws = socketRef.getAndSet(null)
        ws?.close(1000, null)
        failInflight()
        pairAck.getAndSet(null)?.let { if (!it.isCompleted) it.cancel() }
        if (notify && wasHelloed) {
            onDisconnected?.invoke()
        }
    }

    private suspend fun request(payload: JSONObject, timeoutMs: Long): JSONObject? {
        val id = jsonString(payload, "id")
        val deferred = CompletableDeferred<JSONObject>()
        if (id != null) inflight[id] = deferred
        val sent = writeMutex.withLock { writeUnlocked(payload) }
        if (!sent) {
            if (id != null) inflight.remove(id)
            return null
        }
        val result = awaitJson(deferred, timeoutMs)
        if (id != null && result == null) inflight.remove(id)
        return result
    }

    private suspend fun awaitJson(
        deferred: CompletableDeferred<JSONObject>,
        timeoutMs: Long,
    ): JSONObject? {
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun writeUnlocked(payload: JSONObject): Boolean {
        val ws = socketRef.get() ?: return false
        return ws.send(payload.toString())
    }

    private fun failInflight() {
        inflight.values.forEach { deferred ->
            if (!deferred.isCompleted) {
                deferred.completeExceptionally(IllegalStateException("socket closed"))
            }
        }
        inflight.clear()
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (generation.get() != gen) return
            socketRef.set(webSocket)
            opened.get()?.let { if (!it.isCompleted) it.complete(true) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation.get() != gen) return
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                Log.d(TAG, "drop non-json frame")
                return
            }
            when (readMode) {
                ReadMode.WAIT_PAIR -> {
                    if (jsonInt(json, "v") == 1 && json.has("ok")) {
                        pairAck.get()?.let { if (!it.isCompleted) it.complete(json) }
                    }
                }
                ReadMode.SESSION -> handleSessionFrame(json)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleSocketDeath(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation.get() != gen) return
            lastFailure = "${t.javaClass.simpleName}: ${t.message}"
            Log.d(TAG, "onFailure ${lastFailure}")
            opened.get()?.let { if (!it.isCompleted) it.complete(false) }
            handleSocketDeath(webSocket)
        }

        private fun handleSocketDeath(webSocket: WebSocket) {
            if (generation.get() != gen) return
            if (socketRef.get() !== webSocket && socketRef.get() != null) return
            val wasHelloed = _isHelloed
            _isHelloed = false
            _lastHelloSessionId = null
            socketRef.compareAndSet(webSocket, null)
            failInflight()
            if (wasHelloed) onDisconnected?.invoke()
        }
    }

    internal fun handleSessionFrame(json: JSONObject) {
        val op = json.opt("op") as? String
        if (op == "hello" && jsonBoolean(json, "ok") == true) {
            val sid = jsonString(json, "sessionId")
            if (!sid.isNullOrBlank()) {
                _lastHelloSessionId = sid
                _isHelloed = true
            }
            jsonString(json, "id")?.let { inflight.remove(it)?.complete(json) }
            return
        }
        if (op == "reply") {
            parseReply(json)?.let { onReply?.invoke(it) }
            return
        }
        if (op == "error") {
            onError?.invoke(parseError(json))
            return
        }
        val id = jsonString(json, "id")
        if (id != null) {
            inflight.remove(id)?.complete(json)
            return
        }
        if (op == "pong") return
        Log.d(TAG, "drop unsolicited op=${op ?: "-"}")
    }

    internal fun putInflight(id: String): CompletableDeferred<JSONObject> {
        val deferred = CompletableDeferred<JSONObject>()
        inflight[id] = deferred
        return deferred
    }

    private fun parseReply(json: JSONObject): PluginMessage? {
        val id = jsonString(json, "id") ?: return null
        val from = jsonString(json, "from") ?: return null
        val text = jsonString(json, "text") ?: return null
        val seq = jsonLong(json, "seq") ?: return null
        val sessionId = _lastHelloSessionId ?: return null
        val at = jsonString(json, "at").orEmpty()
        val agentId = jsonString(json, "agentId")
        val inReplyTo = jsonString(json, "inReplyTo")
        val catchUp = json.optBoolean("catchUp", false)
        val live = json.optBoolean("live", true) && !catchUp
        return PluginMessage(
            id = id,
            from = from,
            text = text,
            at = at,
            seq = seq,
            sessionId = sessionId,
            agentId = agentId,
            inReplyTo = inReplyTo,
            live = live,
            catchUp = catchUp,
        )
    }

    private fun parseHello(json: JSONObject): HelloResult {
        if (jsonBoolean(json, "ok") == true) {
            val sessionId = jsonString(json, "sessionId")?.takeIf { it.isNotBlank() }
                ?: return HelloResult.Error("invalid hello")
            val seq = jsonLong(json, "seq") ?: return HelloResult.Error("invalid hello")
            return HelloResult.Success(sessionId = sessionId, seq = seq)
        }
        return when (val error = jsonString(json, "error")) {
            "unpaired" -> HelloResult.Unpaired
            "wrong_peer" -> HelloResult.WrongPeer
            null -> HelloResult.Error("invalid hello")
            else -> HelloResult.Rejected(error)
        }
    }

    private fun parseSend(json: JSONObject): SendResult {
        if (jsonBoolean(json, "ok") != true) {
            return SendResult.Error(jsonString(json, "error") ?: "Send rejected")
        }
        val echoId = jsonString(json, "echoId") ?: return SendResult.Error("invalid send ack")
        return SendResult.Success(
            id = jsonString(json, "id").orEmpty(),
            from = jsonString(json, "from").orEmpty(),
            text = jsonString(json, "text").orEmpty(),
            at = jsonString(json, "at").orEmpty(),
            echoId = echoId,
        )
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private enum class ReadMode { WAIT_PAIR, SESSION }

    companion object {
        private const val TAG = "SessionClient"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val PAIR_ACK_TIMEOUT_MS = 10_000L
        private const val HELLO_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
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
        val echoId: String = "",
    ) : SendResult()
    data object NotConnected : SendResult()
    data class Error(val message: String) : SendResult()
}

data class PluginMessage(
    val id: String,
    val from: String,
    val text: String,
    val at: String,
    val seq: Long = 0,
    val sessionId: String = "",
    val agentId: String? = null,
    val inReplyTo: String? = null,
    val live: Boolean = true,
    val catchUp: Boolean = false,
)

internal fun buildHelloPayload(
    id: String,
    phonePeer: String,
    pub: String?,
    lastSeenSeq: Long,
    sessionId: String?,
): JSONObject {
    val payload = JSONObject()
        .put("v", 1)
        .put("op", "hello")
        .put("id", id)
        .put("peer", phonePeer)
        .put("lastSeenSeq", lastSeenSeq.toInt())
    if (!pub.isNullOrBlank()) payload.put("pub", pub)
    if (!sessionId.isNullOrBlank()) payload.put("sessionId", sessionId)
    return payload
}

sealed class HelloResult {
    data class Success(val sessionId: String, val seq: Long) : HelloResult()
    data object Unpaired : HelloResult()
    data object WrongPeer : HelloResult()
    data class Rejected(val reason: String) : HelloResult()
    data class Error(val message: String) : HelloResult()
    data object Timeout : HelloResult()
    data object NotConnected : HelloResult()
}

sealed class AgentsResult {
    data class Success(
        val agents: List<SessionAgent>,
        val stale: Boolean = false,
        val lastAgentId: String? = null,
    ) : AgentsResult()
    data object NotConnected : AgentsResult()
    data class Error(val message: String) : AgentsResult()
}

data class SessionAgent(
    val id: String,
    val name: String,
)

data class SessionError(
    val error: String?,
    val detail: String?,
    val inReplyTo: String?,
    val agentId: String?,
) {
    val banner: String get() = agentErrorBanner(detail)
}

internal fun agentErrorBanner(detail: String?): String = when (detail) {
    "unknown_agent" -> "That agent is gone — pick another"
    "not_connected" -> "Not connected"
    else -> "Agent unavailable"
}

internal fun parseError(json: JSONObject): SessionError = SessionError(
    error = jsonString(json, "error"),
    detail = jsonString(json, "detail"),
    inReplyTo = jsonString(json, "inReplyTo"),
    agentId = jsonString(json, "agentId"),
)

internal fun parseAgentsResult(json: JSONObject): AgentsResult {
    if (jsonBoolean(json, "ok") != true) {
        return AgentsResult.Error(jsonString(json, "error") ?: "Agents rejected")
    }
    val arr = json.opt("agents") as? JSONArray
        ?: return AgentsResult.Error("invalid agents")
    val agents = buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.opt(i) as? JSONObject ?: continue
            val id = jsonString(obj, "id") ?: continue
            val name = jsonString(obj, "name") ?: continue
            if (id.isBlank() || name.isBlank()) continue
            add(SessionAgent(id = id, name = name))
        }
    }
    val lastAgentId = jsonString(json, "lastAgentId")?.takeIf { it.isNotBlank() }
    return AgentsResult.Success(
        agents = agents,
        stale = jsonBoolean(json, "stale") ?: false,
        lastAgentId = lastAgentId,
    )
}

internal fun jsonString(json: JSONObject, key: String): String? {
    if (!json.has(key) || json.isNull(key)) return null
    return json.opt(key) as? String
}

internal fun jsonBoolean(json: JSONObject, key: String): Boolean? {
    return json.opt(key) as? Boolean
}

internal fun jsonInt(json: JSONObject, key: String): Int? {
    val value = json.opt(key)
    return when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> if (value is Boolean) null else value.toInt()
        else -> null
    }
}

internal fun jsonLong(json: JSONObject, key: String): Long? {
    val value = json.opt(key)
    return when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Float, is Double -> {
            val d = value.toDouble()
            if (d % 1.0 == 0.0 && d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                d.toLong()
            } else {
                null
            }
        }
        is Number -> {
            if (value is Boolean) return null
            value.toLong()
        }
        else -> null
    }
}
