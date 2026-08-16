package com.jtwolfe.glass

import android.app.Application
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.InboxSettings
import com.jtwolfe.glass.p2p.InboxStreamClient
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.pairing.PairingStore
import com.jtwolfe.glass.pairing.PluginClient
import com.jtwolfe.glass.rtc.NtfySignaling
import com.jtwolfe.glass.rtc.WebRtcPeerConnection
import com.jtwolfe.glass.settings.AgentSettings
import com.jtwolfe.glass.settings.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GlassApplication : Application() {
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

    private val _connectionState = MutableStateFlow(ConnectionState.UNPAIRED)
    val connectionState = _connectionState.asStateFlow()

    var onWebRtcDisconnected: (() -> Unit)? = null
    var onReconnectNeeded: ((pendingText: String?) -> Unit)? = null

    fun createWebRtcConnectionForFirstPair(invite: PairingInvite): WebRtcPeerConnection? {
        val peer = invite.peer
        val pub = invite.pub ?: return null
        val code = invite.code

        webRtcConnection?.close()

        val signaling = NtfySignaling.fromInvite(peer, pub, code)
        val connection = WebRtcPeerConnection(this, signaling) {
            _connectionState.value = if (pairingStore.isPaired) {
                ConnectionState.OFFLINE_PAIRED
            } else {
                ConnectionState.UNPAIRED
            }
            onWebRtcDisconnected?.invoke()
        }
        webRtcConnection = connection
        return connection
    }

    fun createWebRtcConnectionForReconnect(): WebRtcPeerConnection? {
        val stableTopic = pairingStore.stableTopic ?: return null

        webRtcConnection?.close()

        val signaling = NtfySignaling.fromStableTopic(stableTopic)
        val connection = WebRtcPeerConnection(this, signaling) {
            _connectionState.value = if (pairingStore.isPaired) {
                ConnectionState.OFFLINE_PAIRED
            } else {
                ConnectionState.UNPAIRED
            }
            onWebRtcDisconnected?.invoke()
        }
        webRtcConnection = connection
        return connection
    }

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun updateConnectionState() {
        val dcOpen = webRtcConnection?.isConnected == true
        _connectionState.value = when {
            dcOpen -> ConnectionState.CONNECTED
            pairingStore.isPaired -> ConnectionState.OFFLINE_PAIRED
            else -> ConnectionState.UNPAIRED
        }
    }

    fun closeWebRtcConnection() {
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
