package com.jtwolfe.glass

import android.app.Application
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.InboxSettings
import com.jtwolfe.glass.p2p.InboxStreamClient
import com.jtwolfe.glass.pairing.PairingStore

class GlassApplication : Application() {
    lateinit var inboxSettings: InboxSettings
        private set

    lateinit var xaiAuthStore: XaiAuthStore
        private set

    lateinit var pairingStore: PairingStore
        private set

    val inboxStreamClient: InboxStreamClient by lazy { InboxStreamClient() }

    override fun onCreate() {
        super.onCreate()
        inboxSettings = InboxSettings(this)
        xaiAuthStore = XaiAuthStore(this)
        pairingStore = PairingStore(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        inboxStreamClient.close()
    }
}
