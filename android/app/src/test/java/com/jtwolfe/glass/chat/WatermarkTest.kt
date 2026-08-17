package com.jtwolfe.glass.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkTest {
    @Test
    fun speaksNextSeqOnSameSessionWhenLiveAndForeground() {
        assertTrue(
            Watermark.shouldSpeak(
                seq = 0,
                sessionId = "sess-a",
                currentSessionId = "sess-a",
                lastSpokenSeq = -1,
                live = true,
                foreground = true,
            ),
        )
    }

    @Test
    fun silentWhenSeqNotGreaterThanLastSpoken() {
        assertFalse(
            Watermark.shouldSpeak(
                seq = 3,
                sessionId = "sess-a",
                currentSessionId = "sess-a",
                lastSpokenSeq = 3,
                live = true,
                foreground = true,
            ),
        )
        assertFalse(
            Watermark.shouldSpeak(
                seq = 2,
                sessionId = "sess-a",
                currentSessionId = "sess-a",
                lastSpokenSeq = 3,
                live = true,
                foreground = true,
            ),
        )
    }

    @Test
    fun silentWhenSessionIdMismatches() {
        assertFalse(
            Watermark.shouldSpeak(
                seq = 0,
                sessionId = "sess-b",
                currentSessionId = "sess-a",
                lastSpokenSeq = -1,
                live = true,
                foreground = true,
            ),
        )
    }

    @Test
    fun silentWhenSeqOrSessionMissing() {
        assertFalse(
            Watermark.shouldSpeak(
                seq = null,
                sessionId = "sess-a",
                currentSessionId = "sess-a",
                lastSpokenSeq = -1,
                live = true,
                foreground = true,
            ),
        )
        assertFalse(
            Watermark.shouldSpeak(
                seq = 0,
                sessionId = null,
                currentSessionId = "sess-a",
                lastSpokenSeq = -1,
                live = true,
                foreground = true,
            ),
        )
        assertFalse(
            Watermark.shouldSpeak(
                seq = 0,
                sessionId = "sess-a",
                currentSessionId = null,
                lastSpokenSeq = -1,
                live = true,
                foreground = true,
            ),
        )
    }

    @Test
    fun silentWhenNotLive() {
        assertFalse(
            Watermark.shouldSpeak(
                seq = 0,
                sessionId = "sess-a",
                currentSessionId = "sess-a",
                lastSpokenSeq = -1,
                live = false,
                foreground = true,
            ),
        )
    }

    @Test
    fun silentWhenNotForeground() {
        assertFalse(
            Watermark.shouldSpeak(
                seq = 0,
                sessionId = "sess-a",
                currentSessionId = "sess-a",
                lastSpokenSeq = -1,
                live = true,
                foreground = false,
            ),
        )
    }

    @Test
    fun advanceOnlyIncreases() {
        assertEquals(4L, Watermark.advance(3, 4))
        assertEquals(3L, Watermark.advance(3, 3))
        assertEquals(3L, Watermark.advance(3, 1))
        assertEquals(0L, Watermark.advance(-1, 0))
    }

    @Test
    fun helloResetSeqRewindsBothWatermarksOnNewSession() {
        assertEquals(5L, Watermark.helloResetSeq("old", "new", 6))
        assertEquals(-1L, Watermark.helloResetSeq(null, "new", 0))
        assertNull(Watermark.helloResetSeq("same", "same", 6))
    }
}
