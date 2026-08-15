package com.jtwolfe.glass.rtc

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTC DataChannel connection for glass-pair v1.
 *
 * Signaling via ntfy.sh topic (computed from peer/pub/code).
 * ICE: stun:stun.l.google.com:19302 ONLY. No TURN.
 * Fail closed if DataChannel doesn't form (hard NAT).
 *
 * Phone flow:
 * 1. Compute topic from QR fields
 * 2. Subscribe to ntfy topic (listen for answer/ICE)
 * 3. Create WebRTC offer, POST to ntfy
 * 4. Wait for answer from ntfy
 * 5. When DataChannel opens, ntfy is done
 *
 * DataChannel protocol (UTF-8 JSON lines, same as PluginClient):
 *   {"v":1,"op":"send","from":"jamie","text":"…","at":"<ISO>"}
 *   {"v":1,"op":"replies","after":"<ISO>","limit":50}
 *   Optional: "authorization":"Bearer <token>"
 *
 * Never from=ashleigh on send. Chat NEVER goes to ntfy.
 */
class WebRtcPeerConnection(
    private val context: Context,
    private val signaling: NtfySignaling,
) : Closeable {

    companion object {
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
        private const val DATA_CHANNEL_LABEL = "glass-pair"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L

        @Volatile
        private var factoryInitialized = false

        private fun initFactory(context: Context) {
            if (factoryInitialized) return
            synchronized(this) {
                if (factoryInitialized) return
                val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                factoryInitialized = true
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    private val pendingResponses = ConcurrentLinkedQueue<CompletableDeferred<String>>()
    private var signalingJob: Job? = null
    private val channelOpenDeferred = CompletableDeferred<Boolean>()
    private val closed = AtomicBoolean(false)

    /**
     * Connect to the plugin via WebRTC.
     * Creates offer, publishes to ntfy, waits for answer, establishes DataChannel.
     *
     * @return ConnectResult with success/error status
     */
    suspend fun connect(): ConnectResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_isConnected) return@withContext ConnectResult.AlreadyConnected

            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    connectInternal()
                }
            } catch (_: TimeoutCancellationException) {
                closeInternal()
                ConnectResult.Timeout
            } catch (e: Exception) {
                closeInternal()
                ConnectResult.Error(e.message ?: "Connection failed")
            }
        }
    }

    private suspend fun connectInternal(): ConnectResult {
        initFactory(context)

        val eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder(STUN_SERVER).createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = createPeerConnectionObserver()
        peerConnection = factory?.createPeerConnection(rtcConfig, observer)
            ?: return ConnectResult.Error("Failed to create PeerConnection")

        val dcInit = DataChannel.Init().apply {
            ordered = true
            maxRetransmits = -1
        }
        dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, dcInit)
        dataChannel?.registerObserver(createDataChannelObserver())

        signalingJob = signaling.subscribe()
            .onEach { msg -> handleSignalingMessage(msg) }
            .launchIn(scope)

        val offerSdp = createOffer() ?: return ConnectResult.Error("Failed to create offer")
        peerConnection?.setLocalDescription(SimpleSdpObserver(), offerSdp)

        if (!signaling.publishOffer(offerSdp.description)) {
            return ConnectResult.Error("Failed to publish offer to ntfy")
        }

        val success = channelOpenDeferred.await()
        if (!success) {
            return ConnectResult.Error("DataChannel failed to open")
        }

        _isConnected = true
        return ConnectResult.Success
    }

    private suspend fun createOffer(): SessionDescription? {
        val deferred = CompletableDeferred<SessionDescription?>()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                deferred.complete(sdp)
            }
            override fun onCreateFailure(error: String?) {
                deferred.complete(null)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)

        return deferred.await()
    }

    private fun handleSignalingMessage(msg: SignalingMessage) {
        when (msg) {
            is SignalingMessage.Answer -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, msg.sdp)
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), sdp)
            }
            is SignalingMessage.Ice -> {
                if (msg.candidate.isNotBlank()) {
                    val parts = msg.candidate.split(" ")
                    if (parts.size >= 2) {
                        val candidate = IceCandidate("0", 0, msg.candidate)
                        peerConnection?.addIceCandidate(candidate)
                    }
                }
            }
            is SignalingMessage.Offer -> {
                // Ignore offers (we're the offerer)
            }
        }
    }

    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                scope.launch {
                    signaling.publishIce(it.sdp)
                }
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.DISCONNECTED,
                PeerConnection.IceConnectionState.CLOSED -> {
                    if (!channelOpenDeferred.isCompleted) {
                        channelOpenDeferred.complete(false)
                    }
                    _isConnected = false
                }
                else -> {}
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            when (state) {
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    if (!channelOpenDeferred.isCompleted) {
                        channelOpenDeferred.complete(false)
                    }
                    _isConnected = false
                }
                else -> {}
            }
        }
    }

    private fun createDataChannelObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}

        override fun onStateChange() {
            val state = dataChannel?.state()
            when (state) {
                DataChannel.State.OPEN -> {
                    if (!channelOpenDeferred.isCompleted) {
                        channelOpenDeferred.complete(true)
                    }
                }
                DataChannel.State.CLOSED -> {
                    _isConnected = false
                    pendingResponses.forEach { it.complete("") }
                    pendingResponses.clear()
                }
                else -> {}
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            buffer?.let { buf ->
                val data = ByteArray(buf.data.remaining())
                buf.data.get(data)
                val message = String(data, Charsets.UTF_8)
                pendingResponses.poll()?.complete(message)
            }
        }
    }

    /**
     * Send a message to the plugin via DataChannel.
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
    ): DataChannelSendResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected) {
                return@withContext DataChannelSendResult.NotConnected
            }

            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    sendInternal(from, text, at, token)
                }
            } catch (_: TimeoutCancellationException) {
                DataChannelSendResult.Error("Request timeout")
            } catch (e: Exception) {
                DataChannelSendResult.Error(e.message ?: "Send failed")
            }
        }
    }

    private suspend fun sendInternal(from: String, text: String, at: String, token: String?): DataChannelSendResult {
        val dc = dataChannel ?: return DataChannelSendResult.NotConnected

        val request = JSONObject()
            .put("v", 1)
            .put("op", "send")
            .put("from", from)
            .put("text", text)
            .put("at", at)

        if (!token.isNullOrBlank()) {
            request.put("authorization", "Bearer $token")
        }

        val responseDeferred = CompletableDeferred<String>()
        pendingResponses.add(responseDeferred)

        val data = request.toString().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
        if (!dc.send(buffer)) {
            pendingResponses.remove(responseDeferred)
            return DataChannelSendResult.Error("Failed to send")
        }

        val response = responseDeferred.await()
        if (response.isBlank()) {
            return DataChannelSendResult.Error("Connection closed")
        }

        return parseSendResponse(response)
    }

    private fun parseSendResponse(line: String): DataChannelSendResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                DataChannelSendResult.Success(
                    id = json.optString("id", ""),
                    from = json.optString("from", ""),
                    text = json.optString("text", ""),
                    at = json.optString("at", ""),
                )
            } else {
                val error = json.optString("error", "").ifBlank { null }
                DataChannelSendResult.Error(error ?: "Send rejected")
            }
        } catch (_: Exception) {
            DataChannelSendResult.Error("Invalid response")
        }
    }

    /**
     * Fetch replies from the plugin via DataChannel.
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
    ): DataChannelRepliesResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!_isConnected) {
                return@withContext DataChannelRepliesResult.NotConnected
            }

            try {
                withTimeout(REQUEST_TIMEOUT_MS) {
                    repliesInternal(after, limit, token)
                }
            } catch (_: TimeoutCancellationException) {
                DataChannelRepliesResult.Error("Request timeout")
            } catch (e: Exception) {
                DataChannelRepliesResult.Error(e.message ?: "Replies fetch failed")
            }
        }
    }

    private suspend fun repliesInternal(after: String, limit: Int, token: String?): DataChannelRepliesResult {
        val dc = dataChannel ?: return DataChannelRepliesResult.NotConnected

        val request = JSONObject()
            .put("v", 1)
            .put("op", "replies")
            .put("after", after)
            .put("limit", limit)

        if (!token.isNullOrBlank()) {
            request.put("authorization", "Bearer $token")
        }

        val responseDeferred = CompletableDeferred<String>()
        pendingResponses.add(responseDeferred)

        val data = request.toString().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
        if (!dc.send(buffer)) {
            pendingResponses.remove(responseDeferred)
            return DataChannelRepliesResult.Error("Failed to send")
        }

        val response = responseDeferred.await()
        if (response.isBlank()) {
            return DataChannelRepliesResult.Error("Connection closed")
        }

        return parseRepliesResponse(response)
    }

    private fun parseRepliesResponse(line: String): DataChannelRepliesResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                val messagesArray = json.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<DataChannelMessage>()
                for (i in 0 until messagesArray.length()) {
                    val msg = messagesArray.optJSONObject(i) ?: continue
                    messages.add(
                        DataChannelMessage(
                            id = msg.optString("id", ""),
                            from = msg.optString("from", ""),
                            text = msg.optString("text", ""),
                            at = msg.optString("at", ""),
                        )
                    )
                }
                DataChannelRepliesResult.Success(messages)
            } else {
                val error = json.optString("error", "").ifBlank { null }
                DataChannelRepliesResult.Error(error ?: "Replies rejected")
            }
        } catch (_: Exception) {
            DataChannelRepliesResult.Error("Invalid response")
        }
    }

    fun disconnect() {
        closeInternal()
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        if (!closed.compareAndSet(false, true)) return

        signalingJob?.cancel()
        signalingJob = null

        dataChannel?.close()
        dataChannel = null

        peerConnection?.close()
        peerConnection = null

        factory?.dispose()
        factory = null

        signaling.close()

        _isConnected = false
        pendingResponses.forEach { it.complete("") }
        pendingResponses.clear()

        if (!channelOpenDeferred.isCompleted) {
            channelOpenDeferred.complete(false)
        }

        scope.cancel()
    }
}

sealed class ConnectResult {
    data object Success : ConnectResult()
    data object AlreadyConnected : ConnectResult()
    data object Timeout : ConnectResult()
    data class Error(val message: String) : ConnectResult()
}

sealed class DataChannelSendResult {
    data class Success(
        val id: String,
        val from: String,
        val text: String,
        val at: String,
    ) : DataChannelSendResult()
    data object NotConnected : DataChannelSendResult()
    data class Error(val message: String) : DataChannelSendResult()
}

sealed class DataChannelRepliesResult {
    data class Success(val messages: List<DataChannelMessage>) : DataChannelRepliesResult()
    data object NotConnected : DataChannelRepliesResult()
    data class Error(val message: String) : DataChannelRepliesResult()
}

data class DataChannelMessage(
    val id: String,
    val from: String,
    val text: String,
    val at: String,
)

private class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
