package com.jtwolfe.glass

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jtwolfe.glass.chat.ChatViewModel
import com.jtwolfe.glass.ui.GlassRoot
import com.jtwolfe.glass.ui.theme.GlassTheme
import com.jtwolfe.glass.voice.ListeningState
import com.jtwolfe.glass.voice.SpeechRecognizerHelper
import com.jtwolfe.glass.voice.TtsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels { ChatViewModel.factory(application) }

    private var isAssistant by mutableStateOf(false)
    private var hasMicPermission by mutableStateOf(false)

    private var ttsHelper: TtsHelper? = null
    private var speechHelper: SpeechRecognizerHelper? = null
    private var replyPlayer: MediaPlayer? = null
    private var pendingAutoListen = false

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
        setContent {
            GlassTheme {
                GlassRoot(
                    viewModel = chatViewModel,
                    isDefaultAssistant = isAssistant,
                    hasMicPermission = hasMicPermission,
                    onRequestAssistantRole = ::requestAssistantRole,
                    onOpenAssistantSettings = ::openAssistantSettingsFallback,
                    onRequestMicPermission = ::requestMicPermission,
                    onStartListening = ::startListening,
                    onStopListening = ::stopListening,
                )
            }
        }
        handleAssistIntent(intent)
    }

    private fun initializeVoice() {
        ttsHelper = TtsHelper(this)
        speechHelper = SpeechRecognizerHelper(this) { transcript ->
            chatViewModel.onVoiceTranscript(transcript)
        }

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
            val mpeg = if (!id.isNullOrBlank()) {
                withContext(Dispatchers.IO) { chatViewModel.fetchReplyAudio(id) }
            } else {
                null
            }
            if (mpeg != null && mpeg.isNotEmpty()) {
                playReplyMpeg(mpeg)
            } else {
                ttsHelper?.speak(message.text)
            }
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
        stopReplyAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechHelper?.reset()
        stopReplyAudio()
        ttsHelper?.shutdown()
    }

    private fun handleAssistIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST) {
            if (hasMicPermission) {
                startListening()
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
                    "Speech is handled on-device by Android's recognizer unless " +
                    "the inbox cloud STT is mounted. The transcript is posted " +
                    "as Jamie's message, same as the keyboard.",
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
        speechHelper?.startListening()
    }

    private fun stopListening() {
        speechHelper?.stopListening()
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
