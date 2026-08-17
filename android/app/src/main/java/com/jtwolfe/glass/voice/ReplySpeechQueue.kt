package com.jtwolfe.glass.voice

import android.app.Application
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.jtwolfe.glass.auth.TokenResult
import com.jtwolfe.glass.auth.XaiAuthStore
import com.jtwolfe.glass.pairing.PairingStore
import com.jtwolfe.glass.settings.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal sealed class SpeechJob {
    abstract val seq: Long
    abstract val sessionId: String
    abstract val gen: Int

    data class Mpeg(
        override val seq: Long,
        override val sessionId: String,
        val bytes: ByteArray,
        override val gen: Int,
    ) : SpeechJob()

    data class Device(
        override val seq: Long,
        override val sessionId: String,
        val text: String,
        override val gen: Int,
    ) : SpeechJob()
}

internal interface SpeechPlayback {
    fun playMpeg(
        file: File,
        onComplete: () -> Unit,
        onError: () -> Unit,
        canStart: () -> Boolean,
    ): Boolean
    fun speakDevice(text: String, onComplete: () -> Unit, canStart: () -> Boolean): Boolean
    fun stopCurrent()
}

/**
 * Sequential live-reply speech. Application-owned.
 * [offer] starts the next xAI synth immediately (prefetch) while the current clip plays.
 * A new live reply must not stop() the previous clip. Only [stopAndClear] barges in.
 */
class ReplySpeechQueue internal constructor(
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val isProcessStarted: () -> Boolean,
    private val synthesize: suspend (String) -> ByteArray?,
    private val persistLastSpoken: suspend (sessionId: String, seq: Long) -> Unit,
    private val playback: SpeechPlayback,
    private val onMain: (block: () -> Unit) -> Unit,
) {
    constructor(
        application: Application,
        pairingStore: PairingStore,
        xaiAuthStore: XaiAuthStore,
        voiceSettings: VoiceSettings,
        scope: CoroutineScope,
        isProcessStarted: () -> Boolean,
        xaiVoiceClient: XaiVoiceClient = XaiVoiceClient(),
    ) : this(
        scope = scope,
        cacheDir = application.cacheDir,
        isProcessStarted = isProcessStarted,
        synthesize = { text -> synthesizeXai(xaiAuthStore, voiceSettings, xaiVoiceClient, text) },
        persistLastSpoken = { sid, seq -> pairingStore.persistLastSpoken(sid, seq) },
        playback = AndroidSpeechPlayback(application),
        onMain = ::postToMain,
    )

    private val generation = AtomicInteger(0)
    private val playToken = AtomicInteger(0)
    private val lock = Any()
    private val queue = ArrayDeque<SpeechSlot>()

    @Volatile
    private var playing = false

    val currentGeneration: Int get() = generation.get()

    fun offer(text: String, seq: Long, sessionId: String) {
        // Background-accepted rows must never speak later on resume.
        if (!isProcessStarted()) return
        val gen = generation.get()
        if (!isProcessStarted() || generation.get() != gen) return
        val slot = SpeechSlot(seq = seq, sessionId = sessionId, text = text, gen = gen)
        synchronized(lock) {
            if (!isProcessStarted() || generation.get() != gen) return
            queue.addLast(slot)
        }
        // Prefetch: start this synth immediately, do not wait for the queue to go idle.
        scope.launch {
            if (generation.get() != gen || !isProcessStarted()) return@launch
            val bytes = runCatching { synthesize(text) }.getOrNull()
            if (generation.get() != gen || !isProcessStarted()) {
                slot.mpegFile?.delete()
                return@launch
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val file = File(cacheDir, "reply-$seq.mp3")
                runCatching { file.writeBytes(bytes) }
                    .onSuccess {
                        slot.mpegFile = file
                        slot.job = SpeechJob.Mpeg(seq, sessionId, bytes, gen)
                    }
                    .onFailure {
                        slot.job = SpeechJob.Device(seq, sessionId, text, gen)
                    }
            } else {
                slot.job = SpeechJob.Device(seq, sessionId, text, gen)
            }
            if (generation.get() != gen || !isProcessStarted()) {
                slot.mpegFile?.delete()
                return@launch
            }
            slot.ready.complete(Unit)
            onMain { startNext() }
        }
        onMain { startNext() }
    }

    fun startNext() {
        if (playing) return
        if (!isProcessStarted()) {
            dropCurrentGenSlots()
            return
        }
        val slot: SpeechSlot
        synchronized(lock) {
            while (true) {
                val head = queue.firstOrNull() ?: return
                if (head.gen != generation.get()) {
                    queue.removeFirst()
                    head.mpegFile?.delete()
                    continue
                }
                if (!head.ready.isCompleted) return
                queue.removeFirst()
                slot = head
                playing = true
                break
            }
        }
        play(slot)
    }

    fun stopAndClear() {
        generation.incrementAndGet()
        playToken.incrementAndGet()
        val leftover: List<SpeechSlot>
        synchronized(lock) {
            leftover = queue.toList()
            queue.clear()
        }
        leftover.forEach { it.mpegFile?.delete() }
        playing = false
        playback.stopCurrent()
    }

    private fun dropCurrentGenSlots() {
        val gen = generation.get()
        val dropped = mutableListOf<SpeechSlot>()
        synchronized(lock) {
            val iter = queue.iterator()
            while (iter.hasNext()) {
                val slot = iter.next()
                if (slot.gen == gen) {
                    iter.remove()
                    dropped.add(slot)
                }
            }
        }
        dropped.forEach { it.mpegFile?.delete() }
    }

    private fun stillCurrent(gen: Int): Boolean =
        gen == generation.get() && isProcessStarted()

    private fun play(slot: SpeechSlot) {
        if (!stillCurrent(slot.gen)) {
            slot.mpegFile?.delete()
            playing = false
            return
        }
        val gen = slot.gen
        val token = playToken.incrementAndGet()
        val mpegFile = slot.mpegFile
        if (mpegFile != null && mpegFile.exists()) {
            if (!stillCurrent(gen)) {
                mpegFile.delete()
                playing = false
                return
            }
            val started = runCatching {
                playback.playMpeg(
                    file = mpegFile,
                    onComplete = {
                        mpegFile.delete()
                        finishJob(gen, token)
                    },
                    onError = {
                        mpegFile.delete()
                        finishJob(gen, token)
                    },
                    canStart = { stillCurrent(gen) },
                )
            }.getOrDefault(false)
            if (started) {
                scope.launch { persistLastSpoken(slot.sessionId, slot.seq) }
                return
            }
            mpegFile.delete()
            if (!stillCurrent(gen)) {
                playing = false
                return
            }
        }
        speakDevice(slot, gen, token)
    }

    private fun speakDevice(slot: SpeechSlot, gen: Int, token: Int) {
        if (!stillCurrent(gen)) {
            playing = false
            return
        }
        val started = runCatching {
            playback.speakDevice(
                text = slot.text,
                onComplete = { finishJob(gen, token) },
                canStart = { stillCurrent(gen) },
            )
        }.getOrDefault(false)
        if (started) {
            scope.launch { persistLastSpoken(slot.sessionId, slot.seq) }
        } else {
            finishJob(gen, token)
        }
    }

    private fun finishJob(gen: Int, token: Int) {
        if (gen != generation.get() || token != playToken.get()) return
        playing = false
        onMain { startNext() }
    }

    private class SpeechSlot(
        val seq: Long,
        val sessionId: String,
        val text: String,
        val gen: Int,
    ) {
        val ready = CompletableDeferred<Unit>()
        @Volatile var job: SpeechJob? = null
        @Volatile var mpegFile: File? = null
    }

    private class AndroidSpeechPlayback(application: Application) : SpeechPlayback {
        private val tts = TtsHelper(application)
        private var player: MediaPlayer? = null

        override fun playMpeg(
            file: File,
            onComplete: () -> Unit,
            onError: () -> Unit,
            canStart: () -> Boolean,
        ): Boolean {
            releasePlayer()
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                releasePlayer()
                onComplete()
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                onError()
                true
            }
            mp.prepare()
            if (!canStart()) {
                releasePlayer()
                return false
            }
            mp.start()
            return true
        }

        override fun speakDevice(
            text: String,
            onComplete: () -> Unit,
            canStart: () -> Boolean,
        ): Boolean {
            if (!canStart()) return false
            tts.onDone = onComplete
            val started = tts.speak(text)
            if (!started) tts.onDone = {}
            return started
        }

        override fun stopCurrent() {
            tts.onDone = {}
            releasePlayer()
            tts.stop()
        }

        private fun releasePlayer() {
            player?.apply {
                runCatching { stop() }
                release()
            }
            player = null
        }
    }

    companion object {
        private fun postToMain(block: () -> Unit) {
            val main = Looper.getMainLooper()
            if (Looper.myLooper() == main) {
                block()
            } else {
                Handler(main).post(block)
            }
        }

        private suspend fun synthesizeXai(
            xaiAuthStore: XaiAuthStore,
            voiceSettings: VoiceSettings,
            xaiVoiceClient: XaiVoiceClient,
            text: String,
        ): ByteArray? {
            if (text.isBlank()) return null
            val bearer = when (val tokenResult = xaiAuthStore.getOrRefreshAccessToken()) {
                is TokenResult.Valid -> tokenResult.accessToken
                else -> return null
            }
            val voiceId = voiceSettings.voiceId.first()
            return withContext(Dispatchers.IO) {
                when (val result = xaiVoiceClient.synthesize(bearer, text, voiceId)) {
                    is TtsResult.Success -> result.audio
                    is TtsResult.Error -> null
                }
            }
        }
    }
}
