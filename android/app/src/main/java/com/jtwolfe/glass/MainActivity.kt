package com.jtwolfe.glass

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
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
import com.jtwolfe.glass.pairing.AgentsResult
import com.jtwolfe.glass.pairing.HelloResult
import com.jtwolfe.glass.pairing.PairingInvite
import com.jtwolfe.glass.pairing.PluginResult
import com.jtwolfe.glass.settings.Agent
import com.jtwolfe.glass.settings.AgentRosterState
import com.jtwolfe.glass.settings.VoiceSettings
import com.jtwolfe.glass.settings.WssUrl
import com.jtwolfe.glass.ui.GlassRoot
import com.jtwolfe.glass.ui.theme.GlassTheme
import com.jtwolfe.glass.voice.ListeningState
import com.jtwolfe.glass.voice.SpeechRecognizerHelper
import com.jtwolfe.glass.voice.XaiAudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(application) { pendingText, force ->
            attemptReconnect(pendingText, force)
        }
    }

    private var isAssistant by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)
    private var xaiAuth by mutableStateOf<XaiAuthBundle?>(null)
    private var pairing by mutableStateOf<PairingInvite?>(null)
    private var xaiLoginLoading by mutableStateOf(false)
    private var selectedVoiceId by mutableStateOf(VoiceSettings.DEFAULT_VOICE)
    private var agentRoster by mutableStateOf(AgentRosterState(emptyList()))
    private var isAssistRecording by mutableStateOf(false)
    private var assistTimeoutJob: Job? = null
    private var currentConnectionState by mutableStateOf(ConnectionState.UNPAIRED)

    private var speechHelper: SpeechRecognizerHelper? = null
    private var xaiRecorder: XaiAudioRecorder? = null
    private var pendingAutoListen = false

    private val xaiOAuth = XaiOAuth()
    private var pendingDeviceCode: DeviceCodeResponse? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private val reconnectInFlight = AtomicBoolean(false)
    private val reconnectEpoch = AtomicInteger(0)

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
                    roster = agentRoster,
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
                    onRefreshAgents = ::fetchAgents,
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

        app.onReconnectNeeded = { pending ->
            attemptReconnect(pending)
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
            app.agentSettings.roster.collect { state ->
                agentRoster = state
            }
        }

        // Auto-reconnect on startup if already paired
        if (app.pairingStore.isPaired) {
            lifecycleScope.launch {
                attemptReconnect(null)
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
            val result = withContext(Dispatchers.IO) {
                app.sessionClient.agents()
            }
            when (result) {
                is AgentsResult.Success -> {
                    val agents = result.agents.map { Agent(it.id, it.name) }
                    app.agentSettings.updateAvailableAgents(
                        agents,
                        stale = result.stale,
                        lastAgentId = result.lastAgentId,
                    )
                }
                is AgentsResult.Error ->
                    app.agentSettings.markAgentsFetchFailed(result.message)
                AgentsResult.NotConnected ->
                    app.agentSettings.markAgentsFetchFailed("not_connected")
            }
        }
    }

    private fun attemptReconnect(pendingText: String?, force: Boolean = pendingText != null) {
        if (!force && !reconnectInFlight.compareAndSet(false, true)) return
        if (force) {
            reconnectJob?.cancel()
            reconnectInFlight.set(true)
        }
        val epoch = reconnectEpoch.incrementAndGet()
        reconnectJob = lifecycleScope.launch {
            try {
                unifiedReconnect(pendingText)
            } finally {
                releaseReconnectFlight(epoch)
            }
        }
    }

    private fun takeReconnectFlight(): Int {
        reconnectInFlight.set(true)
        return reconnectEpoch.incrementAndGet()
    }

    private fun releaseReconnectFlight(epoch: Int) {
        if (reconnectEpoch.get() == epoch) {
            reconnectInFlight.set(false)
        }
    }

    private suspend fun unifiedReconnect(pendingText: String?) {
        val app = application as GlassApplication

        if (!app.pairingStore.isPaired) {
            app.updateConnectionState()
            chatViewModel.onSessionDisconnected()
            if (pendingText != null) {
                chatViewModel.onReconnectFailed()
            }
            return
        }

        if (app.sessionClient.isHelloed) {
            app.updateConnectionState()
            chatViewModel.onSessionReady()
            return
        }

        app.setConnectionState(ConnectionState.RECONNECTING)
        chatViewModel.onReconnecting()

        val backoffs = longArrayOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L)
        for (attempt in backoffs.indices) {
            if (!app.pairingStore.isPaired) break
            if (attempt > 0) delay(backoffs[attempt])
            if (app.sessionClient.isHelloed) {
                app.updateConnectionState()
                chatViewModel.onSessionReady()
                return
            }

            val resolved = resolveSessionUrl(null)
            if (resolved == null) {
                app.updateConnectionState()
                chatViewModel.onSessionDisconnected()
                Toast.makeText(this, "Set session URL in Settings", Toast.LENGTH_LONG).show()
                if (pendingText != null) {
                    chatViewModel.onReconnectFailed()
                }
                return
            }
            val (url, source) = resolved
            Log.d(TAG, "reconnect attempt=$attempt source=$source")

            val open = withContext(Dispatchers.IO) {
                app.sessionClient.connectSession(url.canonical)
            }
            if (open !is PluginResult.Success) {
                Log.d(TAG, "reconnect attempt=$attempt connect failed")
                continue
            }

            val hello = withContext(Dispatchers.IO) {
                helloPeer(app, app.pairingStore.currentInvite?.pub)
            }
            when (hello) {
                is HelloResult.Success -> {
                    persistHello(hello)
                    app.pairingStore.saveLastWssUrl(url.canonical)
                    app.updateConnectionState()
                    chatViewModel.onSessionReady()
                    fetchAgents()
                    return
                }
                is HelloResult.Unpaired, is HelloResult.WrongPeer -> {
                    wipeForRescan()
                    return
                }
                else -> {
                    Log.d(TAG, "reconnect attempt=$attempt hello failed")
                    app.sessionClient.disconnect()
                }
            }
        }

        app.updateConnectionState()
        chatViewModel.onSessionDisconnected()
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
        reconnectJob?.cancel()
        val epoch = takeReconnectFlight()
        reconnectJob = lifecycleScope.launch {
            val app = application as GlassApplication
            try {

            val previousPeer = app.pairingStore.pluginPeer
            if (previousPeer != invite.peer) {
                chatViewModel.clearLocal()
                app.pairingStore.clearWatermark()
            }

            app.pairingStore.saveInvite(invite)
            pairing = invite

            if (!invite.isV1) {
                Toast.makeText(
                    this@MainActivity,
                    "Scan a v1 plugin QR to pair",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            val resolved = resolveSessionUrl(invite.wssHint)
            if (resolved == null) {
                app.updateConnectionState()
                Toast.makeText(
                    this@MainActivity,
                    "Set session URL in Settings",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val (url, source) = resolved
            Log.d(TAG, "pair url source=$source")
            if (source == "settings" && !invite.wssHint.isNullOrBlank() &&
                invite.wssHint != url.canonical
            ) {
                Toast.makeText(
                    this@MainActivity,
                    "plugin suggested ${invite.wssHint} (not used — Settings override)",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            app.setConnectionState(ConnectionState.RECONNECTING)
            Toast.makeText(this@MainActivity, "Connecting...", Toast.LENGTH_SHORT).show()

            val pairResult = withContext(Dispatchers.IO) {
                app.sessionClient.connectAndPair(url.canonical, invite.code)
            }
            when (pairResult) {
                is PluginResult.Timeout -> {
                    app.updateConnectionState()
                    Toast.makeText(this@MainActivity, "Connection timed out", Toast.LENGTH_LONG).show()
                    return@launch
                }
                is PluginResult.Rejected -> {
                    app.updateConnectionState()
                    Toast.makeText(
                        this@MainActivity,
                        "Pairing rejected",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                is PluginResult.Error -> {
                    app.updateConnectionState()
                    Toast.makeText(
                        this@MainActivity,
                        "Connection failed: ${pairResult.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                PluginResult.Success -> Unit
            }

            val hello = withContext(Dispatchers.IO) {
                helloPeer(app, invite.pub)
            }
            when (hello) {
                is HelloResult.Success -> {
                    persistHello(hello)
                    app.pairingStore.markPaired(invite.peer)
                    app.pairingStore.saveLastWssUrl(url.canonical)
                    app.updateConnectionState()
                    chatViewModel.onSessionReady()
                    fetchAgents()
                    Toast.makeText(this@MainActivity, "Paired", Toast.LENGTH_SHORT).show()
                }
                is HelloResult.Unpaired, is HelloResult.WrongPeer -> {
                    wipeForRescan()
                }
                is HelloResult.Timeout -> {
                    app.updateConnectionState()
                    Toast.makeText(this@MainActivity, "Connection timed out", Toast.LENGTH_LONG).show()
                }
                is HelloResult.Rejected -> {
                    app.updateConnectionState()
                    Toast.makeText(this@MainActivity, "Pairing rejected", Toast.LENGTH_LONG).show()
                }
                is HelloResult.Error -> {
                    app.updateConnectionState()
                    Toast.makeText(
                        this@MainActivity,
                        "Connection failed: ${hello.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                HelloResult.NotConnected -> {
                    app.updateConnectionState()
                    Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_LONG).show()
                }
            }
            } finally {
                releaseReconnectFlight(epoch)
            }
        }
    }

    private fun clearPairing() {
        lifecycleScope.launch {
            val app = application as GlassApplication
            chatViewModel.clearLocal()
            app.pairingStore.clear()
            app.sessionClient.disconnect()
            app.updateConnectionState()
            chatViewModel.onSessionDisconnected()
            pairing = null
            Toast.makeText(this@MainActivity, "Unpaired", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun resolveSessionUrl(qrHint: String?): Pair<WssUrl, String>? {
        val app = application as GlassApplication
        val settingsRaw = app.wssSettings.current()
        if (settingsRaw.isNotBlank()) {
            val parsed = WssUrl.parse(settingsRaw) ?: return null
            return parsed to "settings"
        }
        if (!qrHint.isNullOrBlank()) {
            val parsed = WssUrl.parse(qrHint)
            if (parsed != null) {
                app.wssSettings.save(parsed.canonical)
                return parsed to "qr_hint"
            }
        }
        val last = app.pairingStore.lastWssUrl
        if (!last.isNullOrBlank()) {
            val parsed = WssUrl.parse(last)
            if (parsed != null) return parsed to "last"
        }
        return null
    }

    private suspend fun helloPeer(app: GlassApplication, pub: String?): HelloResult {
        return app.sessionClient.hello(
            phonePeer = app.pairingStore.phonePeer,
            pub = pub,
            lastSeenSeq = app.pairingStore.lastSeenSeq,
            sessionId = app.pairingStore.sessionId,
        )
    }

    private suspend fun persistHello(hello: HelloResult.Success) {
        val app = application as GlassApplication
        app.pairingStore.persistHelloSession(hello.sessionId, hello.seq)
    }

    private suspend fun wipeForRescan() {
        val app = application as GlassApplication
        chatViewModel.clearLocal()
        app.pairingStore.clear()
        app.sessionClient.disconnect()
        app.updateConnectionState()
        chatViewModel.onSessionDisconnected()
        pairing = null
        Toast.makeText(this, "Scan the QR again", Toast.LENGTH_LONG).show()
    }

    private fun initializeVoice() {
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
    }

    private fun stopReplySpeech() {
        (application as GlassApplication).replySpeechQueue.stopAndClear()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAssistantRole()
        refreshMicPermission()
        if (pendingAutoListen && hasMicPermission) {
            pendingAutoListen = false
            startListening()
        }
        startPing()
        val app = application as GlassApplication
        if (app.pairingStore.isPaired && !app.sessionClient.isHelloed) {
            attemptReconnect(null)
        }
    }

    override fun onPause() {
        super.onPause()
        pingJob?.cancel()
        pingJob = null
        speechHelper?.reset()
        if (!isAssistRecording) {
            xaiRecorder?.cancel()
        }
        stopReplySpeech()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHelper?.reset()
        xaiRecorder?.cancel()
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
        stopReplySpeech()
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
        stopReplySpeech()
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

    private fun startPing() {
        pingJob?.cancel()
        pingJob = lifecycleScope.launch {
            val app = application as GlassApplication
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!app.sessionClient.isHelloed) continue
                val ok = withContext(Dispatchers.IO) { app.sessionClient.ping() }
                if (!ok) {
                    app.sessionClient.disconnect()
                    app.updateConnectionState()
                    attemptReconnect(null)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val ASSIST_TIMEOUT_MS = 10_000L
        private const val PING_INTERVAL_MS = 30_000L
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
