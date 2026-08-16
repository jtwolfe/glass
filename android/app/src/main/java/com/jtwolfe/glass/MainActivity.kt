package com.jtwolfe.glass

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jtwolfe.glass.auth.DeviceCodeResponse
import com.jtwolfe.glass.auth.XaiAuthBundle
import com.jtwolfe.glass.auth.XaiOAuth
import com.jtwolfe.glass.chat.ChatViewModel
import com.jtwolfe.glass.chat.SttError
import com.jtwolfe.glass.chat.TranscribeResult
import com.jtwolfe.glass.displayText
import com.jtwolfe.glass.p2p.PairResult
import com.jtwolfe.glass.pairing.DiscoveryState
import com.jtwolfe.glass.pairing.LanDiscovery
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.pairing.PluginResult
import com.jtwolfe.glass.rtc.ConnectResult
import com.jtwolfe.glass.rtc.DataChannelAgentsResult
import com.jtwolfe.glass.rtc.WssAgentsResult
import com.jtwolfe.glass.rtc.WssConnectResult
import com.jtwolfe.glass.settings.Agent
import com.jtwolfe.glass.settings.AgentSettings
import com.jtwolfe.glass.settings.VoiceSettings
import com.jtwolfe.glass.ui.GlassRoot
import com.jtwolfe.glass.ui.theme.GlassTheme
import com.jtwolfe.glass.voice.ListeningState
import com.jtwolfe.glass.voice.SpeechRecognizerHelper
import com.jtwolfe.glass.voice.TtsHelper
import com.jtwolfe.glass.voice.XaiAudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val ASSIST_TIMEOUT_MS = 10_000L
    }

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(application) { pendingText ->
            lifecycleScope.launch { unifiedReconnect(pendingText) }
        }
    }

    private var isAssistant by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)
    private var xaiAuth by mutableStateOf<XaiAuthBundle?>(null)
    private var pairing by mutableStateOf<PairingInvite?>(null)
    private var xaiLoginLoading by mutableStateOf(false)
    private var selectedVoiceId by mutableStateOf(VoiceSettings.DEFAULT_VOICE)
    private var availableAgents by mutableStateOf<List<Agent>>(emptyList())
    private var isAssistRecording by mutableStateOf(false)
    private var assistTimeoutJob: Job? = null
    private var currentConnectionState by mutableStateOf(ConnectionState.UNPAIRED)

    private var ttsHelper: TtsHelper? = null
    private var speechHelper: SpeechRecognizerHelper? = null
    private var xaiRecorder: XaiAudioRecorder? = null
    private var replyPlayer: MediaPlayer? = null
    private var pendingAutoListen = false

    private val xaiOAuth = XaiOAuth()
    private var pendingDeviceCode: DeviceCodeResponse? = null
    private var lanDiscovery: LanDiscovery? = null

    /** Job for the unified reconnect loop */
    private var reconnectJob: Job? = null

    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshAssistantRole()
    }

    private val micPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            startListening()
        } else {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshAssistantRole()
        refreshMicPermission()
        initializeVoice()
        loadAuthState()

        setContent {
            GlassTheme {
                GlassRoot(
                    viewModel = chatViewModel,
                    xaiAuth = xaiAuth,
                    pairing = pairing,
                    connectionState = currentConnectionState,
                    isDefaultAssistant = isAssistant,
                    hasMicPermission = hasMicPermission,
                    xaiLoginLoading = xaiLoginLoading,
                    selectedVoiceId = selectedVoiceId,
                    availableAgents = availableAgents,
                    onRequestAssistantRole = ::requestAssistantRole,
                    onOpenAssistantSettings = ::openAssistantSettingsFallback,
                    onRequestMicPermission = ::requestMicPermission,
                    onStartListening = ::startListening,
                    onStopListening = ::stopListening,
                    onXaiLogin = ::startXaiLogin,
                    onXaiLogout = ::xaiLogout,
                    onSavePairing = ::savePairing,
                    onClearPairing = ::clearPairing,
                    onVoiceSelected = ::selectVoice,
                    onAgentSelected = ::selectAgent,
                )
            }
        }
        handleAssistIntent(intent)
    }

    private fun loadAuthState() {
        val app = application as GlassApplication
        xaiAuth = app.xaiAuthStore.currentBundle
        pairing = app.pairingStore.currentInvite

        lifecycleScope.launch {
            app.xaiAuthStore.state.collect { bundle ->
                xaiAuth = bundle
            }
        }

        lifecycleScope.launch {
            app.pairingStore.state.collect { invite ->
                pairing = invite
            }
        }

        // On WebRTC disconnect: trigger unified reconnect (WSS first, then WebRTC)
        // Single-flight: only start if not already reconnecting
        app.onWebRtcDisconnected = {
            Log.d(TAG, "onWebRtcDisconnected callback")
            lifecycleScope.launch {
                unifiedReconnect(null)
            }
        }

        // On WSS disconnect: trigger unified reconnect
        app.onWssDisconnected = {
            Log.d(TAG, "onWssDisconnected callback")
            lifecycleScope.launch {
                unifiedReconnect(null)
            }
        }

        // On WSS connect: update state and fetch agents
        app.onWssConnected = {
            Log.d(TAG, "onWssConnected callback")
            chatViewModel.onWssConnected()
            fetchAgents()
        }

        lifecycleScope.launch {
            app.connectionState.collect { state ->
                currentConnectionState = state
                chatViewModel.updateConnectionStatus(state)
            }
        }

        lifecycleScope.launch {
            app.voiceSettings.voiceId.collect { voiceId ->
                selectedVoiceId = voiceId
            }
        }

        lifecycleScope.launch {
            app.agentSettings.loadCachedAgents()
            app.agentSettings.availableAgents.collect { agents ->
                availableAgents = agents
            }
        }

        // Auto-reconnect on startup if already paired
        if (app.pairingStore.isPaired) {
            lifecycleScope.launch {
                unifiedReconnect(null)
            }
        }
    }

    private fun selectVoice(voiceId: String) {
        val app = application as GlassApplication
        lifecycleScope.launch {
            app.voiceSettings.setVoiceId(voiceId)
        }
    }

    private fun selectAgent(agent: Agent) {
        val app = application as GlassApplication
        lifecycleScope.launch {
            app.agentSettings.setSelectedAgent(agent)
        }
    }

    private fun fetchAgents() {
        val app = application as GlassApplication

        lifecycleScope.launch {
            // Try WSS first (preferred) - only if connected
            val wss = app.wssClient
            if (wss != null && wss.isConnected) {
                val result = withContext(Dispatchers.IO) {
                    wss.agents()
                }
                if (result is WssAgentsResult.Success) {
                    val agents = result.agents.map { Agent(it.id, it.name) }
                    app.agentSettings.updateAvailableAgents(agents)
                    return@launch
                }
            }

            // Fall back to WebRTC DataChannel - only if connected
            val webRtc = app.webRtcConnection
            if (webRtc != null && webRtc.isConnected) {
                val result = withContext(Dispatchers.IO) {
                    webRtc.agents()
                }
                if (result is DataChannelAgentsResult.Success) {
                    val agents = result.agents.map { Agent(it.id, it.name) }
                    app.agentSettings.updateAvailableAgents(agents)
                }
            }
            // If neither is connected, don't spam HTTP - agents will be fetched when a path connects
        }
    }

    /**
     * Unified reconnect: WSS first, then WebRTC as fallback.
     *
     * Single-flight: only one reconnect loop runs at a time.
     * If already connected (WSS or DC), do nothing.
     * WSS failures do not unpair. WebRTC failures do not unpair.
     * Stay RECONNECTING while in flight (once), not Offline/Reconnecting flash.
     */
    private suspend fun unifiedReconnect(pendingText: String?) {
        val app = application as GlassApplication

        val isPaired = app.pairingStore.isPaired
        val wssConnected = app.wssClient?.isConnected == true
        val dcConnected = app.webRtcConnection?.isConnected == true
        val inFlight = app.reconnectInFlight.get()

        Log.d(TAG, "unifiedReconnect: entry - isPaired=$isPaired wssConnected=$wssConnected dcConnected=$dcConnected inFlight=$inFlight pendingText=${pendingText != null}")

        // Not paired? Nothing to reconnect
        if (!isPaired) {
            Log.d(TAG, "unifiedReconnect: not paired, skipping")
            app.updateConnectionState()
            if (pendingText != null) {
                chatViewModel.onReconnectFailed()
            }
            return
        }

        // Already connected? Nothing to do
        if (wssConnected || dcConnected) {
            Log.d(TAG, "unifiedReconnect: already connected (wss=$wssConnected dc=$dcConnected)")
            app.updateConnectionState()
            return
        }

        // Single-flight guard: only one reconnect at a time
        if (!app.reconnectInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "unifiedReconnect: already in flight, skipping")
            return
        }

        Log.d(TAG, "unifiedReconnect: starting reconnect loop")

        // Set RECONNECTING state once
        app.setConnectionState(ConnectionState.RECONNECTING)
        chatViewModel.onReconnecting()

        val phonePeer = app.pairingStore.phonePeer
        val invite = app.pairingStore.currentInvite
        val pub = invite?.pub

        Log.d(TAG, "unifiedReconnect: phonePeer=${phonePeer.take(12)}... pub=${pub?.take(12) ?: "null"}...")

        try {
            // Phase 1: Try WSS with backoff (preferred path)
            var wssAttempts = 0
            val maxWssAttempts = 5
            while (wssAttempts < maxWssAttempts && app.pairingStore.isPaired) {
                wssAttempts++
                val backoffMs = 2000L * wssAttempts.coerceAtMost(4)

                Log.d(TAG, "unifiedReconnect: WSS attempt $wssAttempts/$maxWssAttempts")

                val wss = app.getOrCreateWssClient()
                Log.d(TAG, "unifiedReconnect: got WSS client, calling connect")
                val result = withContext(Dispatchers.IO) {
                    wss.connect(phonePeer, pub)
                }
                Log.d(TAG, "unifiedReconnect: WSS connect returned: $result")

                when (result) {
                    is WssConnectResult.Success, is WssConnectResult.AlreadyConnected -> {
                        Log.d(TAG, "unifiedReconnect: WSS SUCCESS on attempt $wssAttempts")
                        app.updateConnectionState()
                        chatViewModel.onWssConnected()
                        fetchAgents()
                        return
                    }
                    else -> {
                        // Already logged above
                    }
                }

                // Check if we got connected via another path while waiting
                if (app.isAnyPathConnected) {
                    Log.d(TAG, "unifiedReconnect: connected via other path during WSS retry")
                    app.updateConnectionState()
                    return
                }

                Log.d(TAG, "unifiedReconnect: WSS failed, waiting ${backoffMs}ms before retry")
                delay(backoffMs)
            }

            // Phase 2: Try WebRTC as fallback (only if WSS is not up)
            // Skip if WSS connected during WSS retry
            if (!app.isAnyPathConnected && app.pairingStore.isPaired) {
                Log.d(TAG, "unifiedReconnect: falling back to WebRTC")

                var webRtcAttempts = 0
                val maxWebRtcAttempts = 3
                while (webRtcAttempts < maxWebRtcAttempts && app.pairingStore.isPaired) {
                    webRtcAttempts++
                    val backoffMs = 2000L * webRtcAttempts

                    // Check if WSS connected while we were waiting
                    if (app.wssClient?.isConnected == true) {
                        Log.d(TAG, "unifiedReconnect: WSS connected during WebRTC retry")
                        app.updateConnectionState()
                        return
                    }

                    Log.d(TAG, "unifiedReconnect: WebRTC attempt $webRtcAttempts")

                    val webRtc = app.createWebRtcConnectionForReconnect()
                    if (webRtc == null) {
                        // Handshake already in flight, wait and retry
                        Log.d(TAG, "unifiedReconnect: WebRTC handshake in flight, waiting")
                        delay(backoffMs)
                        continue
                    }

                    val result = withContext(Dispatchers.IO) {
                        webRtc.connect()
                    }
                    app.clearWebRtcHandshakeFlag()

                    when (result) {
                        is ConnectResult.Success, is ConnectResult.AlreadyConnected -> {
                            Log.d(TAG, "unifiedReconnect: WebRTC connected")
                            app.updateConnectionState()
                            chatViewModel.onWebRtcConnected()
                            fetchAgents()
                            return
                        }
                        else -> {
                            Log.d(TAG, "unifiedReconnect: WebRTC attempt $webRtcAttempts failed: $result")
                            // Don't close - let the PC live for potential late answer
                        }
                    }

                    delay(backoffMs)
                }
            }

            // All attempts failed
            Log.d(TAG, "unifiedReconnect: all attempts failed")
            app.updateConnectionState()
            if (pendingText != null) {
                chatViewModel.onReconnectFailed()
            }
        } finally {
            app.reconnectInFlight.set(false)
            Log.d(TAG, "unifiedReconnect: done")
        }
    }

    private fun startXaiLogin() {
        if (xaiLoginLoading) return
        xaiLoginLoading = true

        lifecycleScope.launch {
            try {
                val deviceCode = withContext(Dispatchers.IO) {
                    xaiOAuth.requestDeviceCode()
                }
                pendingDeviceCode = deviceCode

                val uri = deviceCode.verificationUriComplete
                    ?: "${deviceCode.verificationUri}?user_code=${deviceCode.userCode}"

                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()

                try {
                    customTabsIntent.launchUrl(this@MainActivity, Uri.parse(uri))
                } catch (_: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                }

                showDeviceCodeDialog(deviceCode)
                pollForToken(deviceCode)
            } catch (e: Exception) {
                xaiLoginLoading = false
                Toast.makeText(
                    this@MainActivity,
                    "Login failed: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showDeviceCodeDialog(deviceCode: DeviceCodeResponse) {
        AlertDialog.Builder(this)
            .setTitle("xAI Login")
            .setMessage(
                "If the browser didn't open automatically, go to:\n\n" +
                    "${deviceCode.verificationUri}\n\n" +
                    "and enter code:\n\n${deviceCode.userCode}",
            )
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel") { _, _ ->
                pendingDeviceCode = null
                xaiLoginLoading = false
            }
            .show()
    }

    private fun pollForToken(deviceCode: DeviceCodeResponse) {
        lifecycleScope.launch {
            var interval = deviceCode.interval
            val deadline = deviceCode.expiresAt

            while (isActive && pendingDeviceCode != null && Instant.now().isBefore(deadline)) {
                delay(interval * 1000L)

                val result = withContext(Dispatchers.IO) {
                    xaiOAuth.pollDeviceToken(deviceCode.deviceCode)
                }

                when {
                    result.ok && result.accessToken != null -> {
                        val bundle = XaiAuthBundle.fromTokenResponse(
                            accessToken = result.accessToken,
                            refreshToken = result.refreshToken,
                            expiresIn = result.expiresIn,
                            idToken = result.idToken,
                        )
                        val app = application as GlassApplication
                        app.xaiAuthStore.save(bundle)
                        xaiAuth = bundle
                        pendingDeviceCode = null
                        xaiLoginLoading = false
                        Toast.makeText(
                            this@MainActivity,
                            "Logged in as ${bundle.email ?: "xAI user"}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    result.slowDown -> {
                        interval = (interval + 5).coerceAtMost(60)
                    }
                    result.pending -> {
                        // Keep polling
                    }
                    else -> {
                        pendingDeviceCode = null
                        xaiLoginLoading = false
                        Toast.makeText(
                            this@MainActivity,
                            "Login failed: ${result.detail ?: result.error}",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                }
            }

            if (pendingDeviceCode != null) {
                pendingDeviceCode = null
                xaiLoginLoading = false
                Toast.makeText(this@MainActivity, "Login expired", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun xaiLogout() {
        lifecycleScope.launch {
            val app = application as GlassApplication
            app.xaiAuthStore.clear()
            xaiAuth = null
            Toast.makeText(this@MainActivity, "Logged out", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePairing(invite: PairingInvite) {
        lifecycleScope.launch {
            val app = application as GlassApplication

            // Save the invite (first-pair only)
            app.pairingStore.saveInvite(invite)
            pairing = invite

            if (invite.isV1) {
                // v1: Use ntfy for WebRTC signaling (not LAN discovery)
                // First-pair topic = SHA-256("glass-pair/v1\n" + peer + "\n" + pub + "\n" + code)
                // Chat NEVER goes to ntfy - only WebRTC offer/answer/ICE
                Toast.makeText(
                    this@MainActivity,
                    "Connecting...",
                    Toast.LENGTH_SHORT,
                ).show()

                val webRtc = app.createWebRtcConnectionForFirstPair(invite)
                if (webRtc == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Invalid v1 invite: missing pub field",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                val connectResult = withContext(Dispatchers.IO) {
                    webRtc.connect()
                }

                when (connectResult) {
                    is ConnectResult.Success, is ConnectResult.AlreadyConnected -> {
                        app.clearWebRtcHandshakeFlag()

                        // Send hello to establish stable topic for reconnects
                        val phonePeer = app.pairingStore.phonePeer
                        val pluginPeer = invite.peer
                        val helloSent = withContext(Dispatchers.IO) {
                            webRtc.sendHello(phonePeer)
                        }
                        if (helloSent) {
                            app.pairingStore.markPaired(pluginPeer)
                        }

                        app.setConnectionState(ConnectionState.CONNECTED)
                        chatViewModel.onWebRtcConnected()
                        fetchAgents()

                        // Also try to connect WSS (preferred path when available)
                        // WSS connect failure is not an error - WebRTC is working
                        // Run in background - don't block pairing completion
                        lifecycleScope.launch {
                            val pub = invite.pub
                            val wss = app.getOrCreateWssClient()
                            val result = withContext(Dispatchers.IO) {
                                wss.connect(phonePeer, pub)
                            }
                            if (result is WssConnectResult.Success) {
                                Log.d(TAG, "savePairing: WSS also connected")
                                app.updateConnectionState()
                                chatViewModel.onWssConnected()
                            } else {
                                Log.d(TAG, "savePairing: WSS connect failed (OK, WebRTC is working): $result")
                            }
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "Paired",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is ConnectResult.Timeout -> {
                        app.clearWebRtcHandshakeFlag()
                        app.closeWebRtcConnection()
                        app.updateConnectionState()
                        Toast.makeText(
                            this@MainActivity,
                            "Connection timed out — tap to retry",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is ConnectResult.Error -> {
                        app.clearWebRtcHandshakeFlag()
                        app.closeWebRtcConnection()
                        app.updateConnectionState()
                        Toast.makeText(
                            this@MainActivity,
                            "Connection failed: ${connectResult.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } else {
                // v0 (legacy): PSK-based P2P dial
                val psk = invite.psk
                if (psk != null && invite.addrs.isNotEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Connecting to inbox P2P...",
                        Toast.LENGTH_SHORT,
                    ).show()

                    val streamClient = app.inboxStreamClient
                    withContext(Dispatchers.IO) {
                        streamClient.start()
                    }

                    val result = withContext(Dispatchers.IO) {
                        streamClient.dialAndPair(
                            peerId = invite.peer,
                            addrs = invite.addrs,
                            psk = psk,
                            exp = invite.exp,
                        )
                    }

                    when (result) {
                        is PairResult.Success -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Paired with inbox (P2P connected)",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        is PairResult.Expired -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Invite expired. Please scan a new QR code.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        is PairResult.Error -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Paired (HTTPS fallback): ${result.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Paired with inbox (HTTPS fallback)",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun clearPairing() {
        lifecycleScope.launch {
            val app = application as GlassApplication
            app.pairingStore.clear()
            app.closeAllConnections()
            app.pluginClient.disconnect()
            chatViewModel.onWssDisconnected()
            chatViewModel.onWebRtcDisconnected()
            chatViewModel.onPluginDisconnected()
            lanDiscovery?.reset()
            lanDiscovery = null
            pairing = null
            Toast.makeText(this@MainActivity, "Unpaired", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeVoice() {
        ttsHelper = TtsHelper(this)
        speechHelper = SpeechRecognizerHelper(this) { transcript ->
            chatViewModel.onVoiceTranscript(transcript)
        }
        xaiRecorder = XaiAudioRecorder(this)

        speechHelper?.state?.onEach { speechState ->
            val isListening = speechState.listeningState == ListeningState.LISTENING ||
                speechState.listeningState == ListeningState.PROCESSING
            chatViewModel.onListeningStateChange(isListening, speechState.partialTranscript)
            speechState.errorMessage?.let { error ->
                runOnUiThread {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        }?.launchIn(lifecycleScope)

        chatViewModel.newAssistantMessages.onEach { message ->
            val id = message.id

            // Try xAI TTS first when logged in
            val xaiAudio = withContext(Dispatchers.IO) {
                chatViewModel.synthesizeXaiTts(message.text)
            }
            if (xaiAudio != null && xaiAudio.isNotEmpty()) {
                playReplyMpeg(xaiAudio)
                return@onEach
            }

            // Fall back to inbox audio endpoint
            val inboxMpeg = if (!id.isNullOrBlank()) {
                withContext(Dispatchers.IO) { chatViewModel.fetchReplyAudio(id) }
            } else {
                null
            }
            if (inboxMpeg != null && inboxMpeg.isNotEmpty()) {
                playReplyMpeg(inboxMpeg)
                return@onEach
            }

            // Final fallback: on-device TTS
            ttsHelper?.speak(message.text)
        }.launchIn(lifecycleScope)
    }

    private fun playReplyMpeg(bytes: ByteArray) {
        stopReplyAudio()
        ttsHelper?.stop()
        val file = File(cacheDir, "ashleigh-reply.mp3")
        file.writeBytes(bytes)
        replyPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopReplyAudio() }
            setOnErrorListener { _, _, _ ->
                stopReplyAudio()
                true
            }
            prepare()
            start()
        }
    }

    private fun stopReplyAudio() {
        replyPlayer?.apply {
            runCatching { stop() }
            release()
        }
        replyPlayer = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatViewModel.onAssistOpened()
        handleAssistIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAssistantRole()
        refreshMicPermission()
        chatViewModel.refresh()
        if (pendingAutoListen && hasMicPermission) {
            pendingAutoListen = false
            startListening()
        }

        // On app resume while paired: check connection and reconnect if needed
        val app = application as GlassApplication
        if (app.pairingStore.isPaired && !app.isAnyPathConnected) {
            Log.d(TAG, "onResume: paired but not connected, triggering reconnect")
            lifecycleScope.launch {
                unifiedReconnect(null)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        speechHelper?.reset()
        if (!isAssistRecording) {
            xaiRecorder?.cancel()
        }
        stopReplyAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHelper?.reset()
        xaiRecorder?.cancel()
        stopReplyAudio()
        ttsHelper?.shutdown()
    }

    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST) {
            if (hasMicPermission) {
                startAssistListening()
            } else {
                pendingAutoListen = true
                requestMicPermission()
            }
        }
    }

    private fun refreshMicPermission() {
        hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicPermission() {
        if (hasMicPermission) return
        AlertDialog.Builder(this)
            .setTitle("Voice Input")
            .setMessage(
                "Glass uses the microphone for voice input. " +
                    "When logged into xAI, speech is sent to api.x.ai/v1/stt. " +
                    "Otherwise, Android's on-device recognizer is used. " +
                    "The transcript is posted as Jamie's message.",
            )
            .setPositiveButton("Allow") { _, _ ->
                micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun startListening() {
        if (!hasMicPermission) {
            requestMicPermission()
            return
        }
        stopReplyAudio()
        ttsHelper?.stop()
        assistTimeoutJob?.cancel()
        isAssistRecording = false

        val app = application as GlassApplication
        if (app.xaiAuthStore.isLoggedIn) {
            val started = xaiRecorder?.startRecording() == true
            if (started) {
                chatViewModel.onListeningStateChange(true, "")
            } else {
                chatViewModel.setSttError(SttError("Failed to start recording"))
            }
        } else {
            speechHelper?.startListening()
        }
    }

    private fun startAssistListening() {
        if (!hasMicPermission) {
            requestMicPermission()
            return
        }
        stopReplyAudio()
        ttsHelper?.stop()
        assistTimeoutJob?.cancel()

        val app = application as GlassApplication
        if (app.xaiAuthStore.isLoggedIn) {
            val started = xaiRecorder?.startRecording() == true
            if (started) {
                isAssistRecording = true
                chatViewModel.onListeningStateChange(true, "")
                assistTimeoutJob = lifecycleScope.launch {
                    delay(ASSIST_TIMEOUT_MS)
                    if (isAssistRecording && xaiRecorder?.isActive == true) {
                        stopAssistListening()
                    }
                }
            } else {
                chatViewModel.setSttError(SttError("Failed to start recording"))
            }
        } else {
            speechHelper?.startListening()
        }
    }

    private fun stopAssistListening() {
        assistTimeoutJob?.cancel()
        assistTimeoutJob = null
        isAssistRecording = false
        stopListening()
    }

    private fun stopListening() {
        assistTimeoutJob?.cancel()
        assistTimeoutJob = null
        isAssistRecording = false

        if (xaiRecorder?.isActive == true) {
            chatViewModel.onListeningStateChange(false, "")
            lifecycleScope.launch {
                val audio = xaiRecorder?.stopRecording()
                if (audio == null || audio.isEmpty()) {
                    chatViewModel.setSttError(SttError("No audio captured"))
                    return@launch
                }

                chatViewModel.onListeningStateChange(true, "Transcribing...")
                val result = withContext(Dispatchers.IO) {
                    chatViewModel.transcribeAudio(audio)
                }
                chatViewModel.onListeningStateChange(false, "")

                when (result) {
                    is TranscribeResult.Success -> {
                        chatViewModel.dismissSttError()
                        chatViewModel.onVoiceTranscript(result.text)
                    }
                    is TranscribeResult.Error -> {
                        chatViewModel.setSttError(SttError(
                            message = result.message,
                            isAuthError = result.isAuthError,
                            httpCode = result.httpCode,
                        ))
                    }
                    is TranscribeResult.NotLoggedIn -> {
                        chatViewModel.setSttError(SttError(
                            message = "Not logged in — tap to login with xAI",
                            isAuthError = true,
                        ))
                    }
                }
            }
        } else {
            speechHelper?.stopListening()
        }
    }

    private fun refreshAssistantRole() {
        isAssistant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            roles != null &&
                roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            false
        }
    }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                try {
                    roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    return
                } catch (_: Exception) {
                    // fall through to Settings
                }
            }
        }
        openAssistantSettingsFallback()
    }

    private fun openAssistantSettingsFallback() {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
    }
}
