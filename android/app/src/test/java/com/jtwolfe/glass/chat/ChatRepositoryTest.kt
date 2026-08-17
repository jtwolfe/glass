package com.jtwolfe.glass.chat

import com.jtwolfe.glass.inbox.V0Message
import com.jtwolfe.glass.pairing.PluginMessage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {

    @Test
    fun insertOrDedupeInsertsLowerSeqNotAppend() {
        val sid = "sess-a"
        val first = replyRow(sid, 2, "two")
        val afterFirst = insertOrDedupeReply(emptyList(), first)!!
        val afterLow = insertOrDedupeReply(afterFirst, replyRow(sid, 0, "zero"))!!
        val afterMid = insertOrDedupeReply(afterLow, replyRow(sid, 1, "one"))!!
        assertEquals(listOf(0L, 1L, 2L), afterMid.map { it.seq })
        assertEquals(listOf("zero", "one", "two"), afterMid.map { it.text })
        assertNull(insertOrDedupeReply(afterMid, replyRow(sid, 1, "dup")))
    }

    @Test
    fun acceptReplyCatchUpAdvancesLastSeenWithoutSpeech() = runBlocking {
        val wm = FakeWatermarks(sessionId = "sess-a")
        val offered = mutableListOf<Long>()
        val repo = ChatRepository(
            persist = InMemoryChatPersist(),
            watermarks = wm,
            offerSpeech = { _, seq, _ -> offered.add(seq) },
            isForeground = { true },
        )
        repo.acceptReply(
            pluginReply(seq = 4, sessionId = "sess-a", live = false, catchUp = true),
        )
        assertEquals(listOf(4L), wm.seen.map { it.second })
        assertTrue(offered.isEmpty())
        assertEquals(1, repo.messages.value.size)
        assertEquals("I'll check.", repo.messages.value.single().text)
    }

    @Test
    fun acceptReplyLiveBackgroundPersistsWithoutSpeech() = runBlocking {
        val wm = FakeWatermarks(sessionId = "sess-a")
        val offered = mutableListOf<Long>()
        val repo = ChatRepository(
            persist = InMemoryChatPersist(),
            watermarks = wm,
            offerSpeech = { _, seq, _ -> offered.add(seq) },
            isForeground = { false },
        )
        repo.acceptReply(pluginReply(seq = 0, sessionId = "sess-a", live = true))
        assertEquals(listOf(0L), wm.seen.map { it.second })
        assertTrue(offered.isEmpty())
    }

    @Test
    fun acceptReplyLiveForegroundOffersSpeech() = runBlocking {
        val wm = FakeWatermarks(sessionId = "sess-a")
        val offered = mutableListOf<Long>()
        val repo = ChatRepository(
            persist = InMemoryChatPersist(),
            watermarks = wm,
            offerSpeech = { _, seq, _ -> offered.add(seq) },
            isForeground = { true },
        )
        repo.acceptReply(pluginReply(seq = 0, sessionId = "sess-a", live = true))
        assertEquals(listOf(0L), offered)
        repo.acceptReply(
            pluginReply(seq = 0, sessionId = "sess-a", live = false, catchUp = true),
        )
        assertEquals(listOf(0L), offered)
        assertEquals(1, repo.messages.value.size)
    }

    @Test
    fun concurrentAppendOutgoingAndAcceptReplyKeepsBothRows() = runBlocking {
        repeat(40) {
            val persist = InMemoryChatPersist()
            val repo = ChatRepository(
                persist = persist,
                watermarks = FakeWatermarks(sessionId = "sess-a"),
                offerSpeech = { _, _, _ -> },
                isForeground = { false },
            )
            val outgoing = V0Message.outgoing("hi $it")
            val reply = pluginReply(seq = it.toLong(), sessionId = "sess-a")
            coroutineScope {
                launch { repo.appendOutgoing(outgoing) }
                launch { repo.acceptReply(reply) }
            }
            val msgs = repo.messages.value
            assertEquals("iteration $it", 2, msgs.size)
            assertTrue(msgs.any { row -> row.isOutgoing && row.text == "hi $it" })
            assertTrue(msgs.any { row -> row.seq == it.toLong() && !row.isOutgoing })
            assertEquals(2, persist.items.size)
        }
    }

    @Test
    fun acceptReplyInsertsLaterLowerSeqInOrder() = runBlocking {
        val repo = ChatRepository(
            persist = InMemoryChatPersist(),
            watermarks = FakeWatermarks(sessionId = "sess-a"),
            isForeground = { false },
        )
        repo.acceptReply(pluginReply(seq = 2, sessionId = "sess-a", text = "two"))
        repo.acceptReply(pluginReply(seq = 0, sessionId = "sess-a", text = "zero"))
        repo.acceptReply(pluginReply(seq = 1, sessionId = "sess-a", text = "one"))
        assertEquals(listOf(0L, 1L, 2L), repo.messages.value.map { it.seq })
    }

    private fun pluginReply(
        seq: Long,
        sessionId: String,
        text: String = "I'll check.",
        live: Boolean = true,
        catchUp: Boolean = false,
    ) = PluginMessage(
        id = "r-$seq",
        from = "Ashleigh",
        text = text,
        at = "2026-08-17T12:00:00.000Z",
        seq = seq,
        sessionId = sessionId,
        live = live,
        catchUp = catchUp,
    )

    private fun replyRow(sessionId: String, seq: Long, text: String) = V0Message(
        id = "r-$seq",
        from = "Ashleigh",
        text = text,
        at = "2026-08-17T12:00:00.000Z",
        seq = seq,
        sessionId = sessionId,
    )

    private class InMemoryChatPersist(
        initial: List<V0Message> = emptyList(),
    ) : ChatPersist {
        var items: List<V0Message> = initial
        override suspend fun load(): List<V0Message> = items
        override suspend fun save(messages: List<V0Message>) {
            items = messages
        }
    }

    private class FakeWatermarks(
        override var sessionId: String? = null,
        override var lastSpokenSeq: Long = -1,
    ) : ChatWatermarks {
        val seen = mutableListOf<Pair<String, Long>>()
        override suspend fun persistLastSeen(sessionId: String, seq: Long) {
            seen.add(sessionId to seq)
            this.sessionId = sessionId
        }
        override suspend fun clear() {
            sessionId = null
            lastSpokenSeq = -1
            seen.clear()
        }
    }
}
