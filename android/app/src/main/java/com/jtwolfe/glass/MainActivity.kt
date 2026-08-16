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

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(application) { pendingText ->
            lifecycleScope.launch { attemptReconnect(pendingText) }
        }
    }

    private var isAssistant by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)
    private var xaiAuth by mutableStateOf<XaiAuthBundle?>(null)
    private var pairing by mutableStateOf<PairingInvite?>(null)
    private var xaiLoginLoading by mutableStateOf(false)
    private var selectedVoiceId by mutableStateOf(VoiceSettings.DEFAULT_VOICE)
    private var availableAgents by mutableStateOf(listOf(AgentSettings.DEFAULT_AGENT))
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

        app.onWebRtcDisconnected = {
            lifecycleScope.launch {
                attemptReconnect(null)
            }
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
        val webRtc = app.webRtcConnection ?: return

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                webRtc.agents()
            }
            if (result is DataChannelAgentsResult.Success) {
                val agents = result.agents.map { Agent(it.id, it.name) }
                app.agentSettings.updateAvailableAgents(agents)
            }
        }
    }

    private suspend fun attemptReconnect(pendingText: String?) {
        val app = application as GlassApplication
        val invite = app.pairingStore.currentInvite

        if (invite == null || !invite.isV1 || invite.isExpired) {
            app.updateConnectionStateFromInvite()
            chatViewModel.onWebRtcDisconnected()
            if (pendingText != null) {
                chatViewModel.onReconnectFailed()
            }
            return
        }

        app.setConnectionState(ConnectionState.RECONNECTING)
        chatViewModel.onReconnecting()

        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            attempts++
            delay(2000L * attempts)

            val currentInvite = app.pairingStore.currentInvite
            if (currentInvite == null || currentInvite.isExpired) {
                break
            }

            val webRtc = app.createWebRtcConnection(currentInvite) ?: break

            val result = withContext(Dispatchers.IO) {
                webRtc.connect()
            }

            when (result) {
                is ConnectResult.Success, is ConnectResult.AlreadyConnected -> {
                    app.setConnectionState(ConnectionState.CONNECTED)
                    chatViewModel.onWebRtcConnected()
                    fetchAgents()
                    return
                }
                else -> {
                    app.closeWebRtcConnection()
                }
            }
        }

        app.updateConnectionStateFromInvite()
        chatViewModel.onWebRtcDisconnected()
        if (pendingText != null) {
            chatViewModel.onReconnectFailed()
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

            // Save the invite
            app.pairingStore.save(invite)
            pairing = invite

            if (invite.isV1) {
                // v1: Use ntfy.sh for WebRTC signaling (not LAN discovery)
                // Topic = SHA-256("glass-pair/v1\n" + peer + "\n" + pub + "\n" + code)
                // Chat NEVER goes to ntfy - only WebRTC offer/answer/ICE
                Toast.makeText(
                    this@MainActivity,
                    "Connecting via ntfy signaling...",
                    Toast.LENGTH_SHORT,
                ).show()

                val webRtc = app.createWebRtcConnection(invite)
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
                    is ConnectResult.Success -> {
                        app.setConnectionState(ConnectionState.CONNECTED)
                        chatViewModel.onWebRtcConnected()
                        fetchAgents()
                        Toast.makeText(
                            this@MainActivity,
                            "Connected",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is ConnectResult.AlreadyConnected -> {
                        app.setConnectionState(ConnectionState.CONNECTED)
                        chatViewModel.onWebRtcConnected()
                        fetchAgents()
                    }
                    is ConnectResult.Timeout -> {
                        app.closeWebRtcConnection()
                        app.updateConnectionStateFromInvite()
                        Toast.makeText(
                            this@MainActivity,
                            "Connection timed out — tap to retry",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is ConnectResult.Error -> {
                        app.closeWebRtcConnection()
                        app.updateConnectionStateFromInvite()
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
            app.closeWebRtcConnection()
            app.pluginClient.disconnect()
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

        chatViewModel.newAshleighMessages.onEach { message ->
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
            .setTitle("Talk to Ashleigh")
            .setMessage(
                "Glass uses the microphone so Jamie can talk to Ashleigh. " +
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

    companion object {
        private const val ASSIST_TIMEOUT_MS = 10_000L
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
