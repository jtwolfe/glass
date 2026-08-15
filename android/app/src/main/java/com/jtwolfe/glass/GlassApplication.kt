package com.jtwolfe.glass

import android.app.Application
import com.jtwolfe.glass.inbox.InboxSettings

class GlassApplication : Application() {
    lateinit var inboxSettings: InboxSettings
        private set

    override fun onCreate() {
        super.onCreate()
        inboxSettings = InboxSettings(this)
    }
}
