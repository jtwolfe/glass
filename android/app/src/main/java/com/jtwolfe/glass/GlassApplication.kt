package com.jtwolfe.glass

import android.app.Application
import android.util.Log
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.InboxSettings
import com.jtwolfe.glass.p2p.InboxStreamClient
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.pairing.PairingStore
import com.jtwolfe.glass.pairing.PluginClient
import com.jtwolfe.glass.rtc.NtfySignaling
import com.jtwolfe.glass.rtc.WebRtcPeerConnection
import com.jtwolfe.glass.rtc.WssSessionClient
import com.jtwolfe.glass.settings.AgentSettings
import com.jtwolfe.glass.settings.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class GlassApplication : Application() {
    companion object {
        private const val TAG = "GlassApplication"
    }

    lateinit var inboxSettings: InboxSettings
        private set

    lateinit var xaiAuthStore: XaiAuthStore
        private set

    lateinit var pairingStore: PairingStore
        private set

    lateinit var voiceSettings: VoiceSettings
        private set

    lateinit var agentSettings: AgentSettings
        private set

    val inboxStreamClient: InboxStreamClient by lazy { InboxStreamClient() }

    val pluginClient: PluginClient by lazy { PluginClient() }

    @Volatile
    var webRtcConnection: WebRtcPeerConnection? = null
        private set

    @Volatile
    var wssClient: WssSessionClient? = null
        private set

    private val _connectionState = MutableStateFlow(ConnectionState.UNPAIRED)
    val connectionState = _connectionState.asStateFlow()

    /** Single-flight guard: true if a reconnect attempt is in progress */
    val reconnectInFlight = AtomicBoolean(false)

    /** Single-flight guard: true if WebRTC handshake is in progress */
    val webRtcHandshakeInFlight = AtomicBoolean(false)

    var onWebRtcDisconnected: (() -> Unit)? = null
    var onWssDisconnected: (() -> Unit)? = null
    var onWssConnected: (() -> Unit)? = null
    var onReconnectNeeded: ((pendingText: String?) -> Unit)? = null

    fun createWebRtcConnectionForFirstPair(invite: PairingInvite): WebRtcPeerConnection? {
        val peer = invite.peer
        val pub = invite.pub ?: return null
        val code = invite.code

        webRtcConnection?.close()
        webRtcHandshakeInFlight.set(true)

        val signaling = NtfySignaling.fromInvite(peer, pub, code)
        val connection = WebRtcPeerConnection(this, signaling) {
            Log.d(TAG, "WebRTC first-pair disconnected")
            webRtcHandshakeInFlight.set(false)
            // Don't immediately flip state - let reconnect logic handle it
            // Only update if no reconnect is in flight and WSS is not connected
            if (!reconnectInFlight.get()) {
                updateConnectionState()
            }
            onWebRtcDisconnected?.invoke()
        }
        webRtcConnection = connection
        return connection
    }

    fun createWebRtcConnectionForReconnect(): WebRtcPeerConnection? {
        // Single-flight guard: don't create new PC if handshake is in flight
        if (webRtcHandshakeInFlight.get()) {
            Log.d(TAG, "createWebRtcConnectionForReconnect: handshake in flight, skipping")
            return null
        }

        val stableTopic = pairingStore.stableTopic ?: return null

        webRtcConnection?.close()
        webRtcHandshakeInFlight.set(true)

        val signaling = NtfySignaling.fromStableTopic(stableTopic)
        val connection = WebRtcPeerConnection(this, signaling) {
            Log.d(TAG, "WebRTC reconnect disconnected")
            webRtcHandshakeInFlight.set(false)
            // Don't immediately flip state - let reconnect logic handle it
            // Only update if no reconnect is in flight and WSS is not connected
            if (!reconnectInFlight.get()) {
                updateConnectionState()
            }
            onWebRtcDisconnected?.invoke()
        }
        webRtcConnection = connection
        return connection
    }

    /** Clear handshake flag when connection attempt completes (success or fail) */
    fun clearWebRtcHandshakeFlag() {
        webRtcHandshakeInFlight.set(false)
    }

    /**
     * Create WSS session client for the paired phone.
     * WSS is preferred when available; WebRTC DC is WiFi/fallback.
     */
    fun createWssClient(): WssSessionClient {
        Log.d(TAG, "createWssClient: creating new client")
        wssClient?.close()

        val client = WssSessionClient(
            onDisconnected = {
                Log.d(TAG, "WSS disconnected callback")
                // Don't immediately flip state - let reconnect logic handle it
                if (!reconnectInFlight.get()) {
                    updateConnectionState()
                }
                onWssDisconnected?.invoke()
            },
            onConnected = {
                Log.d(TAG, "WSS connected callback")
                updateConnectionState()
                onWssConnected?.invoke()
            },
        )
        wssClient = client
        return client
    }

    /** Get or create WSS client (don't close existing) */
    fun getOrCreateWssClient(): WssSessionClient {
        val existing = wssClient
        return if (existing != null && !existing.isClosed) {
            Log.d(TAG, "getOrCreateWssClient: reusing existing client")
            existing
        } else {
            Log.d(TAG, "getOrCreateWssClient: creating new client (existing=${existing != null})")
            createWssClient()
        }
    }

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Update connection state based on WSS OR DataChannel status.
     * CONNECTED if WSS is open OR DataChannel is open.
     */
    fun updateConnectionState() {
        val wssOpen = wssClient?.isConnected == true
        val dcOpen = webRtcConnection?.isConnected == true
        _connectionState.value = when {
            wssOpen || dcOpen -> ConnectionState.CONNECTED
            pairingStore.isPaired -> ConnectionState.OFFLINE_PAIRED
            else -> ConnectionState.UNPAIRED
        }
    }

    /**
     * Check if any talk path is connected (WSS or DataChannel).
     */
    val isAnyPathConnected: Boolean
        get() = wssClient?.isConnected == true || webRtcConnection?.isConnected == true

    fun closeWebRtcConnection() {
        webRtcConnection?.close()
        webRtcConnection = null
        updateConnectionState()
    }

    fun closeWssClient() {
        wssClient?.close()
        wssClient = null
        updateConnectionState()
    }

    fun closeAllConnections() {
        wssClient?.close()
        wssClient = null
        webRtcConnection?.close()
        webRtcConnection = null
        updateConnectionState()
    }

    override fun onCreate() {
        super.onCreate()
        inboxSettings = InboxSettings(this)
        xaiAuthStore = XaiAuthStore(this)
        pairingStore = PairingStore(this)
        voiceSettings = VoiceSettings(this)
        agentSettings = AgentSettings(this)
        updateConnectionState()
    }

    override fun onTerminate() {
        super.onTerminate()
        inboxStreamClient.close()
        pluginClient.close()
        wssClient?.close()
        webRtcConnection?.close()
    }
}

enum class ConnectionState {
    CONNECTED,
    RECONNECTING,
    OFFLINE_PAIRED,
    UNPAIRED,
}

val ConnectionState.displayText: String
    get() = when (this) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.RECONNECTING -> "Reconnecting..."
        ConnectionState.OFFLINE_PAIRED -> "Offline"
        ConnectionState.UNPAIRED -> "Not paired"
    }
