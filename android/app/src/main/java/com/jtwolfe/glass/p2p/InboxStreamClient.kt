@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jtwolfe.glass.p2p

import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.multistream.StrictProtocolBinding
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Glass inbox P2P stream client using libp2p.
 *
 * Protocol: /glass/inbox/v0
 * Frame format: unsigned-varint length + UTF-8 JSON
 *
 * First frame (pairing): {"psk":"<64 hex>"}
 * Response: {status:200,body:{paired:true}}
 *
 * Subsequent frames: HTTP-like JSON with Authorization Bearer
 */
class InboxStreamClient : Closeable {

    private var host: Host? = null
    private var activeStream: InboxStream? = null
    private val mutex = Mutex()

    private var _isPaired = false
    val isPaired: Boolean get() = _isPaired

    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    /**
     * Initialize the libp2p host. Call once before dialing.
     * Uses default Standard configuration: TCP + Noise + Mplex
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (host != null) return@withContext

        // Uses Defaults.Standard which provides TCP, Noise, Mplex
        host = host {
            identity { random() }
        }
        host?.start()?.get(30, TimeUnit.SECONDS)
    }

    /**
     * Dial the inbox peer and open /glass/inbox/v0 stream.
     *
     * @param peerId Inbox peer ID from QR
     * @param addrs Inbox multiaddrs from QR (may include circuit-relay)
     * @param psk 64 hex char PSK from QR for first-frame pairing
     * @param exp ISO-8601 expiration from QR
     * @return PairResult with success/error status
     */
    suspend fun dialAndPair(
        peerId: String,
        addrs: List<String>,
        psk: String,
        exp: String?,
    ): PairResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // Check expiration
                if (exp != null) {
                    val expInstant = runCatching { Instant.parse(exp) }.getOrNull()
                    if (expInstant != null && Instant.now().isAfter(expInstant)) {
                        return@withContext PairResult.Expired
                    }
                }

                val h = host ?: run {
                    start()
                    host ?: return@withContext PairResult.Error("Failed to start host")
                }

                val remotePeerId = PeerId.fromBase58(peerId)

                // Build multiaddrs with peer ID appended, filtering out loopback/unroutable
                val multiaddrs = addrs.mapNotNull { addr ->
                    runCatching {
                        val ma = Multiaddr(addr)
                        if (ma.has(Protocol.P2P)) ma
                        else Multiaddr("$addr/p2p/$peerId")
                    }.getOrNull()
                }.filter { ma -> !isLoopbackOrUnroutable(ma) }

                if (multiaddrs.isEmpty()) {
                    val hadAddrs = addrs.isNotEmpty()
                    return@withContext if (hadAddrs) {
                        PairResult.Error(
                            "Inbox QR advertised loopback — need Jamie's LAN IP, not 127.0.0.1"
                        )
                    } else {
                        PairResult.Error("No valid addresses")
                    }
                }

                // Try each address until one works
                var lastError: Throwable? = null
                for (addr in multiaddrs) {
                    try {
                        val stream = dialStream(h, addr, remotePeerId)
                        val result = performPairing(stream, psk)
                        if (result is PairResult.Success) {
                            activeStream = stream
                            _isPaired = true
                            _isConnected = true
                            return@withContext result
                        }
                        stream.close()
                    } catch (e: Exception) {
                        lastError = e
                    }
                }

                PairResult.Error(lastError?.message ?: "Connection failed")
            } catch (e: TimeoutCancellationException) {
                PairResult.Error("Connection timeout")
            } catch (e: Exception) {
                PairResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun isLoopbackOrUnroutable(ma: Multiaddr): Boolean {
        val str = ma.toString()
        return str.startsWith("/ip4/127.") ||
            str.startsWith("/ip4/0.0.0.0/") ||
            str.startsWith("/ip6/::1/") ||
            str.contains("/dns4/localhost/") ||
            str.contains("/dns6/localhost/") ||
            str.contains("/dnsaddr/localhost/")
    }

    private suspend fun dialStream(
        h: Host,
        addr: Multiaddr,
        remotePeerId: PeerId,
    ): InboxStream = suspendCancellableCoroutine { cont ->
        val binding = InboxProtocolBinding()
        val future = h.newStream<InboxStreamController>(
            listOf(binding.protocolDescriptor.announceProtocols.first()),
            remotePeerId,
            addr,
        )

        future.stream.whenComplete { stream, error ->
            if (error != null) {
                cont.resumeWithException(error)
            } else {
                cont.resume(InboxStream(stream))
            }
        }
    }

    private suspend fun performPairing(stream: InboxStream, psk: String): PairResult {
        return withTimeout(10_000) {
            // Send first frame: {"psk":"<64 hex>"}
            val pairRequest = JSONObject().put("psk", psk).toString()
            stream.send(pairRequest)

            // Wait for response
            val response = stream.receive()
            val json = JSONObject(response)

            val status = json.optInt("status", 0)
            if (status == 200) {
                val body = json.optJSONObject("body")
                if (body?.optBoolean("paired") == true) {
                    PairResult.Success
                } else {
                    PairResult.Error("Pairing not confirmed")
                }
            } else {
                PairResult.Error("Pairing failed: status $status")
            }
        }
    }

    /**
     * Send a message to the inbox.
     * POST /v0/messages {from, text, at}
     */
    suspend fun postMessage(
        token: String,
        from: String,
        text: String,
        at: String,
    ): StreamResponse = mutex.withLock {
        val stream = activeStream ?: return StreamResponse.NotConnected

        val request = JSONObject()
            .put("method", "POST")
            .put("path", "/v0/messages")
            .put("authorization", "Bearer $token")
            .put("body", JSONObject()
                .put("from", from)
                .put("text", text)
                .put("at", at)
            )

        sendRequest(stream, request)
    }

    /**
     * Fetch replies from the inbox.
     * GET /v0/replies?after=&limit=50
     */
    suspend fun getReplies(
        token: String,
        after: String,
        limit: Int = 50,
    ): StreamResponse = mutex.withLock {
        val stream = activeStream ?: return StreamResponse.NotConnected

        val request = JSONObject()
            .put("method", "GET")
            .put("path", "/v0/replies")
            .put("authorization", "Bearer $token")
            .put("query", JSONObject()
                .put("after", after)
                .put("limit", limit)
            )

        sendRequest(stream, request)
    }

    /**
     * Check inbox health.
     * GET /v0/health
     */
    suspend fun getHealth(token: String): StreamResponse = mutex.withLock {
        val stream = activeStream ?: return StreamResponse.NotConnected

        val request = JSONObject()
            .put("method", "GET")
            .put("path", "/v0/health")
            .put("authorization", "Bearer $token")

        sendRequest(stream, request)
    }

    private suspend fun sendRequest(stream: InboxStream, request: JSONObject): StreamResponse {
        return try {
            withTimeout(30_000) {
                stream.send(request.toString())
                val response = stream.receive()
                val json = JSONObject(response)
                StreamResponse.Success(
                    status = json.optInt("status", 0),
                    body = json.optJSONObject("body")?.toString() ?: json.optString("body", ""),
                )
            }
        } catch (e: Exception) {
            _isConnected = false
            StreamResponse.Error(e.message ?: "Request failed")
        }
    }

    /**
     * Close the connection and stop the host.
     */
    override fun close() {
        activeStream?.close()
        activeStream = null
        host?.stop()
        host = null
        _isPaired = false
        _isConnected = false
    }

    /**
     * Disconnect the stream but keep the host running.
     */
    fun disconnect() {
        activeStream?.close()
        activeStream = null
        _isConnected = false
    }
}

sealed class PairResult {
    object Success : PairResult()
    object Expired : PairResult()
    data class Error(val message: String) : PairResult()
}

sealed class StreamResponse {
    data class Success(val status: Int, val body: String) : StreamResponse()
    object NotConnected : StreamResponse()
    data class Error(val message: String) : StreamResponse()
}

/**
 * Wrapper around libp2p Stream for /glass/inbox/v0 protocol.
 * Handles unsigned-varint length-prefixed JSON frames.
 */
class InboxStream(private val stream: Stream) : Closeable {

    private val receiveQueue = ConcurrentLinkedQueue<CompletableDeferred<String>>()
    private val receiveBuffer = ByteArrayOutputStream()
    @Volatile private var closed = false

    init {
        stream.pushHandler(object : SimpleChannelInboundHandler<ByteBuf>() {
            override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                if (closed) return
                val bytes = ByteArray(msg.readableBytes())
                msg.readBytes(bytes)
                processReceivedBytes(bytes)
            }

            override fun channelInactive(ctx: ChannelHandlerContext) {
                closed = true
                receiveQueue.forEach { it.completeExceptionally(Exception("Stream closed")) }
                receiveQueue.clear()
            }

            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                closed = true
                receiveQueue.forEach { it.completeExceptionally(cause) }
                receiveQueue.clear()
            }
        })
    }

    private fun processReceivedBytes(bytes: ByteArray) {
        synchronized(receiveBuffer) {
            receiveBuffer.write(bytes)
            tryDecodeFrames()
        }
    }

    private fun tryDecodeFrames() {
        while (true) {
            val data = receiveBuffer.toByteArray()
            if (data.isEmpty()) return

            try {
                val input = ByteArrayInputStream(data)
                val frame = FrameCodec.decodeFrame(input)
                val remaining = input.readAllBytes()

                receiveBuffer.reset()
                receiveBuffer.write(remaining)

                val deferred = receiveQueue.poll()
                deferred?.complete(frame)
            } catch (_: Exception) {
                return
            }
        }
    }

    fun send(json: String) {
        if (closed) throw Exception("Stream closed")
        val frame = FrameCodec.encodeFrame(json)
        val buf = Unpooled.wrappedBuffer(frame)
        stream.writeAndFlush(buf)
    }

    suspend fun receive(): String = suspendCancellableCoroutine { cont ->
        if (closed) {
            cont.resumeWithException(Exception("Stream closed"))
            return@suspendCancellableCoroutine
        }

        val deferred = CompletableDeferred<String>()
        receiveQueue.add(deferred)

        synchronized(receiveBuffer) {
            tryDecodeFrames()
        }

        deferred.invokeOnCompletion { error ->
            when {
                error != null -> cont.resumeWithException(error)
                deferred.isCompleted -> cont.resume(runCatching { deferred.getCompleted() }.getOrElse { "" })
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { stream.close() }
    }
}

/**
 * Controller interface for /glass/inbox/v0 protocol.
 */
interface InboxStreamController

/**
 * Protocol binding for /glass/inbox/v0
 */
class InboxProtocolBinding : StrictProtocolBinding<InboxStreamController>(
    PROTOCOL_ID,
    InboxProtocolHandler(),
)

class InboxProtocolHandler : io.libp2p.protocol.ProtocolHandler<InboxStreamController>(
    Long.MAX_VALUE,
    Long.MAX_VALUE,
) {
    override fun onStartInitiator(stream: Stream): CompletableFuture<InboxStreamController> {
        return CompletableFuture.completedFuture(object : InboxStreamController {})
    }

    override fun onStartResponder(stream: Stream): CompletableFuture<InboxStreamController> {
        return CompletableFuture.completedFuture(object : InboxStreamController {})
    }
}

const val PROTOCOL_ID = "/glass/inbox/v0"
