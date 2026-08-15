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

class GlassApplication : Application() {
    lateinit var inboxSettings: InboxSettings
        private set

    lateinit var xaiAuthStore: XaiAuthStore
        private set

    lateinit var pairingStore: PairingStore
        private set

    val inboxStreamClient: InboxStreamClient by lazy { InboxStreamClient() }

    val pluginClient: PluginClient by lazy { PluginClient() }

    @Volatile
    var webRtcConnection: WebRtcPeerConnection? = null
        private set

    fun createWebRtcConnection(invite: PairingInvite): WebRtcPeerConnection? {
        val peer = invite.peer
        val pub = invite.pub ?: return null
        val code = invite.code

        webRtcConnection?.close()

        val signaling = NtfySignaling(peer, pub, code)
        val connection = WebRtcPeerConnection(this, signaling)
        webRtcConnection = connection
        return connection
    }

    fun closeWebRtcConnection() {
        webRtcConnection?.close()
        webRtcConnection = null
    }

    override fun onCreate() {
        super.onCreate()
        inboxSettings = InboxSettings(this)
        xaiAuthStore = XaiAuthStore(this)
        pairingStore = PairingStore(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        inboxStreamClient.close()
        pluginClient.close()
        webRtcConnection?.close()
    }
}
