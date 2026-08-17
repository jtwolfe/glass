package com.jtwolfe.glass

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.chat.ChatRepository
import com.jtwolfe.glass.pairing.PairingStore
import com.jtwolfe.glass.pairing.SessionClient
import com.jtwolfe.glass.settings.AgentSettings
import com.jtwolfe.glass.settings.VoiceSettings
import com.jtwolfe.glass.settings.WssSettings
import com.jtwolfe.glass.voice.ReplySpeechQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GlassApplication : Application() {
    lateinit var xaiAuthStore: XaiAuthStore
        private set

    lateinit var pairingStore: PairingStore
        private set

    lateinit var voiceSettings: VoiceSettings
        private set

    lateinit var agentSettings: AgentSettings
        private set

    lateinit var wssSettings: WssSettings
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var replySpeechQueue: ReplySpeechQueue
        private set

    val sessionClient: SessionClient by lazy { SessionClient() }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ConnectionState.UNPAIRED)
    val connectionState = _connectionState.asStateFlow()

    var onReconnectNeeded: ((pendingText: String?) -> Unit)? = null

    @Volatile
    var isForeground: Boolean = false
        private set

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun updateConnectionState() {
        _connectionState.value = when {
            sessionClient.isHelloed -> ConnectionState.CONNECTED
            pairingStore.isPaired -> ConnectionState.OFFLINE_PAIRED
            else -> ConnectionState.UNPAIRED
        }
    }

    override fun onCreate() {
        super.onCreate()
        xaiAuthStore = XaiAuthStore(this)
        pairingStore = PairingStore(this)
        voiceSettings = VoiceSettings(this)
        agentSettings = AgentSettings(this)
        wssSettings = WssSettings(this)

        replySpeechQueue = ReplySpeechQueue(
            application = this,
            pairingStore = pairingStore,
            xaiAuthStore = xaiAuthStore,
            voiceSettings = voiceSettings,
            scope = applicationScope,
            isProcessStarted = { isForeground },
        )
        chatRepository = ChatRepository(
            context = this,
            sessionClient = sessionClient,
            pairingStore = pairingStore,
            speechQueue = replySpeechQueue,
            isForeground = { isForeground },
            hydrateScope = applicationScope,
        )
        sessionClient.onReply = { msg ->
            applicationScope.launch { chatRepository.acceptReply(msg) }
        }
        sessionClient.onError = { err ->
            applicationScope.launch { chatRepository.setAgentError(err) }
        }
        sessionClient.onDisconnected = {
            updateConnectionState()
            onReconnectNeeded?.invoke(null)
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                isForeground = false
                if (this@GlassApplication::replySpeechQueue.isInitialized) {
                    replySpeechQueue.stopAndClear()
                }
            }
        })
        isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        updateConnectionState()
    }

    override fun onTerminate() {
        super.onTerminate()
        sessionClient.close()
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
