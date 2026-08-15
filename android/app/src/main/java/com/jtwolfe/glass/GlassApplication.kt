package com.jtwolfe.glass

import android.app.Application
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.inbox.InboxSettings
import com.jtwolfe.glass.pairing.PairingStore

class GlassApplication : Application() {
    lateinit var inboxSettings: InboxSettings
        private set

    lateinit var xaiAuthStore: XaiAuthStore
        private set

    lateinit var pairingStore: PairingStore
        private set

    override fun onCreate() {
        super.onCreate()
        inboxSettings = InboxSettings(this)
        xaiAuthStore = XaiAuthStore(this)
        pairingStore = PairingStore(this)
    }
}
