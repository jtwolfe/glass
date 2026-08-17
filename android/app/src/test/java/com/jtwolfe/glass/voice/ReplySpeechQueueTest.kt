package com.jtwolfe.glass.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class ReplySpeechQueueTest {

    @Test
    fun secondOfferPrefetchesBeforeFirstCompletes() = runBlocking {
        withQueue { harness ->
            val secondSynthStarted = CompletableDeferred<Unit>()
            val firstPlayStarted = CompletableDeferred<Unit>()
            val firstPlayComplete = CompletableDeferred<Unit>()
            harness.synthesize = { text ->
                harness.synthOrder.add(text)
                if (text == "two") secondSynthStarted.complete(Unit)
                byteArrayOf(1, 2, 3)
            }
            harness.playback.onPlayMpeg = { _, onComplete, _, canStart ->
                if (!canStart()) {
                    false
                } else {
                    harness.playStarts.add("mpeg")
                    if (harness.playStarts.size == 1) {
                        firstPlayStarted.complete(Unit)
                        firstPlayComplete.invokeOnCompletion { onComplete() }
                    } else {
                        onComplete()
                    }
                    true
                }
            }

            harness.queue.offer("one", 0, "sess-a")
            harness.queue.offer("two", 1, "sess-a")

            withTimeout(1_000) { secondSynthStarted.await() }
            withTimeout(1_000) { firstPlayStarted.await() }
            assertEquals(listOf("one", "two"), harness.synthOrder.toList())
            assertEquals(1, harness.playStarts.size)
            assertFalse(firstPlayComplete.isCompleted)

            firstPlayComplete.complete(Unit)
            withTimeout(1_000) {
                while (harness.playStarts.size < 2) delay(5)
            }
            assertEquals(2, harness.playStarts.size)
            assertEquals(listOf(0L, 1L), harness.spoken.toList())
        }
    }

    @Test
    fun stopAndClearDiscardsInFlightSynth() = runBlocking {
        withQueue { harness ->
            val synthStarted = CompletableDeferred<Unit>()
            val synthGate = CompletableDeferred<ByteArray?>()
            harness.synthesize = {
                synthStarted.complete(Unit)
                synthGate.await()
            }

            harness.queue.offer("one", 0, "sess-a")
            withTimeout(1_000) { synthStarted.await() }
            val genBefore = harness.queue.currentGeneration
            harness.queue.stopAndClear()
            assertTrue(harness.queue.currentGeneration > genBefore)
            synthGate.complete(byteArrayOf(1, 2, 3))
            delay(50)
            assertTrue(harness.playStarts.isEmpty())
            assertTrue(harness.spoken.isEmpty())
        }
    }

    @Test
    fun lateSynthAfterStopDoesNotPlayOnStartNext() = runBlocking {
        withQueue { harness ->
            val synthStarted = CompletableDeferred<Unit>()
            val synthGate = CompletableDeferred<ByteArray?>()
            harness.synthesize = {
                synthStarted.complete(Unit)
                synthGate.await()
            }

            harness.queue.offer("one", 0, "sess-a")
            withTimeout(1_000) { synthStarted.await() }
            harness.queue.stopAndClear()
            synthGate.complete(byteArrayOf(9))
            delay(30)
            harness.processStarted = true
            harness.queue.startNext()
            delay(30)
            assertTrue(harness.playStarts.isEmpty())
            assertTrue(harness.spoken.isEmpty())
        }
    }

    @Test
    fun recheckStartedBeforePlayDoesNotBumpLastSpoken() = runBlocking {
        withQueue { harness ->
            harness.synthesize = {
                harness.processStarted = false
                byteArrayOf(1)
            }
            harness.queue.offer("one", 0, "sess-a")
            delay(50)
            assertTrue(harness.playStarts.isEmpty())
            assertTrue(harness.spoken.isEmpty())
        }
    }

    @Test
    fun offerWhileBackgroundedDoesNotSynthOrSpeakAfterResume() = runBlocking {
        withQueue { harness ->
            var synthCalls = 0
            harness.synthesize = {
                synthCalls++
                byteArrayOf(1)
            }
            harness.processStarted = false
            harness.queue.offer("bg", 0, "sess-a")
            delay(30)
            assertEquals(0, synthCalls)
            assertTrue(harness.playStarts.isEmpty())
            harness.processStarted = true
            harness.queue.startNext()
            delay(30)
            assertEquals(0, synthCalls)
            assertTrue(harness.playStarts.isEmpty())
            assertTrue(harness.spoken.isEmpty())
        }
    }

    @Test
    fun startNextDropsCurrentGenWhenBackgrounded() = runBlocking {
        withQueue { harness ->
            val firstComplete = CompletableDeferred<Unit>()
            harness.synthesize = { byteArrayOf(1) }
            harness.playback.onPlayMpeg = { _, onComplete, _, canStart ->
                if (!canStart()) {
                    false
                } else {
                    harness.playStarts.add("mpeg")
                    if (harness.playStarts.size == 1) {
                        firstComplete.invokeOnCompletion { onComplete() }
                    } else {
                        onComplete()
                    }
                    true
                }
            }
            harness.queue.offer("one", 0, "sess-a")
            harness.queue.offer("two", 1, "sess-a")
            withTimeout(1_000) {
                while (harness.playStarts.size < 1) delay(5)
            }
            delay(20)
            harness.processStarted = false
            firstComplete.complete(Unit)
            delay(30)
            assertEquals(1, harness.playStarts.size)
            harness.processStarted = true
            harness.queue.startNext()
            delay(30)
            assertEquals(1, harness.playStarts.size)
            assertEquals(listOf(0L), harness.spoken.toList())
        }
    }

    @Test
    fun deviceErrorCompletesJobSoNextCanStart() = runBlocking {
        withQueue { harness ->
            harness.synthesize = { null }
            harness.playback.onSpeak = { text, onComplete, canStart ->
                if (!canStart()) {
                    false
                } else {
                    harness.playStarts.add(text)
                    onComplete()
                    true
                }
            }
            harness.queue.offer("one", 0, "sess-a")
            harness.queue.offer("two", 1, "sess-a")
            withTimeout(1_000) {
                while (harness.playStarts.size < 2) delay(5)
            }
            assertEquals(listOf("one", "two"), harness.playStarts.toList())
            assertEquals(listOf(0L, 1L), harness.spoken.toList())
        }
    }

    @Test
    fun speakErrorDoesNotPersistAndStartsNext() = runBlocking {
        withQueue { harness ->
            harness.synthesize = { null }
            harness.playback.onSpeak = { text, onComplete, canStart ->
                when {
                    !canStart() -> false
                    text == "one" -> {
                        harness.playStarts.add("err")
                        false
                    }
                    else -> {
                        harness.playStarts.add(text)
                        onComplete()
                        true
                    }
                }
            }
            harness.queue.offer("one", 0, "sess-a")
            harness.queue.offer("two", 1, "sess-a")
            withTimeout(1_000) {
                while (harness.playStarts.size < 2) delay(5)
            }
            assertEquals(listOf("err", "two"), harness.playStarts.toList())
            assertEquals(listOf(1L), harness.spoken.toList())
        }
    }

    @Test
    fun secondClipDoesNotStopFirst() = runBlocking {
        withQueue { harness ->
            val firstComplete = CompletableDeferred<Unit>()
            harness.synthesize = { byteArrayOf(1) }
            harness.playback.onPlayMpeg = { _, onComplete, _, canStart ->
                if (!canStart()) {
                    false
                } else {
                    harness.playStarts.add("mpeg")
                    if (harness.playStarts.size == 1) {
                        firstComplete.invokeOnCompletion { onComplete() }
                    } else {
                        onComplete()
                    }
                    true
                }
            }
            harness.queue.offer("one", 0, "sess-a")
            withTimeout(1_000) {
                while (harness.playStarts.size < 1) delay(5)
            }
            harness.queue.offer("two", 1, "sess-a")
            delay(40)
            assertEquals(0, harness.playback.stopCount)
            assertEquals(1, harness.playStarts.size)
            firstComplete.complete(Unit)
            withTimeout(1_000) {
                while (harness.playStarts.size < 2) delay(5)
            }
            assertEquals(0, harness.playback.stopCount)
        }
    }

    @Test
    fun staleCompletionAfterStopAndClearDoesNotCutNewClip() = runBlocking {
        withQueue { harness ->
            var staleComplete: (() -> Unit)? = null
            val thirdPlaying = CompletableDeferred<Unit>()
            val thirdComplete = CompletableDeferred<Unit>()
            harness.synthesize = { byteArrayOf(1) }
            harness.playback.onPlayMpeg = { _, onComplete, _, canStart ->
                if (!canStart()) {
                    false
                } else {
                    harness.playStarts.add("mpeg")
                    when (harness.playStarts.size) {
                        1 -> staleComplete = onComplete
                        2 -> {
                            thirdPlaying.complete(Unit)
                            thirdComplete.invokeOnCompletion { onComplete() }
                        }
                        else -> onComplete()
                    }
                    true
                }
            }
            harness.queue.offer("one", 0, "sess-a")
            withTimeout(1_000) {
                while (harness.playStarts.size < 1) delay(5)
            }
            harness.queue.stopAndClear()
            harness.queue.offer("three", 2, "sess-a")
            withTimeout(1_000) { thirdPlaying.await() }
            harness.queue.offer("four", 3, "sess-a")
            delay(30)
            assertEquals(2, harness.playStarts.size)
            staleComplete!!.invoke()
            delay(40)
            assertEquals(2, harness.playStarts.size)
            thirdComplete.complete(Unit)
            withTimeout(1_000) {
                while (harness.playStarts.size < 3) delay(5)
            }
            assertEquals(3, harness.playStarts.size)
        }
    }

    private suspend fun withQueue(block: suspend (Harness) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = File(System.getProperty("java.io.tmpdir"), "reply-q-${System.nanoTime()}")
        dir.mkdirs()
        val harness = Harness(scope, dir)
        try {
            block(harness)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private class RecordingPlayback : SpeechPlayback {
        var stopCount = 0
        var onPlayMpeg: (File, () -> Unit, () -> Unit, () -> Boolean) -> Boolean =
            { _, onComplete, _, canStart ->
                if (!canStart()) false else {
                    onComplete()
                    true
                }
            }
        var onSpeak: (String, () -> Unit, () -> Boolean) -> Boolean =
            { _, onComplete, canStart ->
                if (!canStart()) false else {
                    onComplete()
                    true
                }
            }

        override fun playMpeg(
            file: File,
            onComplete: () -> Unit,
            onError: () -> Unit,
            canStart: () -> Boolean,
        ): Boolean = onPlayMpeg(file, onComplete, onError, canStart)

        override fun speakDevice(text: String, onComplete: () -> Unit, canStart: () -> Boolean): Boolean =
            onSpeak(text, onComplete, canStart)

        override fun stopCurrent() {
            stopCount++
        }
    }

    private class Harness(
        scope: CoroutineScope,
        cacheDir: File,
    ) {
        val synthOrder = Collections.synchronizedList(mutableListOf<String>())
        val playStarts = Collections.synchronizedList(mutableListOf<String>())
        val spoken = Collections.synchronizedList(mutableListOf<Long>())
        val playback = RecordingPlayback()
        @Volatile var processStarted = true
        @Volatile var synthesize: suspend (String) -> ByteArray? = { byteArrayOf(1) }

        val queue = ReplySpeechQueue(
            scope = scope,
            cacheDir = cacheDir,
            isProcessStarted = { processStarted },
            synthesize = { text -> synthesize(text) },
            persistLastSpoken = { _, seq -> spoken.add(seq) },
            playback = playback,
            onMain = { it() },
        )
    }
}
