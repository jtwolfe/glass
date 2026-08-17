package com.jtwolfe.glass.pairing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClientTest {

    @Test
    fun parseAgentsReadsStaleAndLastAgentId() {
        val json = JSONObject()
            .put("ok", true)
            .put("stale", true)
            .put("lastAgentId", "028324f9-fde3-40b3-be75-717df409b2dd")
            .put(
                "agents",
                JSONArray().put(
                    JSONObject().put("id", "a").put("name", "Ashleigh"),
                ),
            )
        val result = parseAgentsResult(json) as AgentsResult.Success
        assertTrue(result.stale)
        assertEquals("028324f9-fde3-40b3-be75-717df409b2dd", result.lastAgentId)
        assertEquals(listOf(SessionAgent("a", "Ashleigh")), result.agents)
    }

    @Test
    fun parseAgentsDefaultsWhenStaleAndLastAgentOmitted() {
        val json = JSONObject()
            .put("ok", true)
            .put("agents", JSONArray())
        val result = parseAgentsResult(json) as AgentsResult.Success
        assertFalse(result.stale)
        assertNull(result.lastAgentId)
        assertTrue(result.agents.isEmpty())
    }

    @Test
    fun parseAgentsTreatsJsonNullLastAgentIdAsAbsent() {
        val json = JSONObject()
            .put("ok", true)
            .put("agents", JSONArray())
            .put("stale", false)
        json.put("lastAgentId", JSONObject.NULL)
        val result = parseAgentsResult(json) as AgentsResult.Success
        assertFalse(result.stale)
        assertNull(result.lastAgentId)
    }

    @Test
    fun unsolicitedErrorWithFreshIdInvokesOnErrorAndDoesNotCompleteInflight() {
        val client = SessionClient()
        try {
            val send = client.putInflight("send-1")
            val stolen = client.putInflight("fresh-uuid")
            var seen: SessionError? = null
            client.onError = { seen = it }
            val frame = JSONObject()
                .put("v", 1)
                .put("op", "error")
                .put("error", "agent_unavailable")
                .put("id", "fresh-uuid")
                .put("inReplyTo", "echo-1")
                .put("agentId", "dead")
                .put("detail", "unknown_agent")
            client.handleSessionFrame(frame)
            assertNotNull(seen)
            assertEquals("unknown_agent", seen!!.detail)
            assertEquals("That agent is gone — pick another", seen.banner)
            assertFalse(send.isCompleted)
            assertFalse(stolen.isCompleted)
        } finally {
            client.close()
        }
    }

    @Test
    fun replyIsClassifiedBeforeInflightIdMap() {
        val client = SessionClient()
        try {
            val inflight = client.putInflight("reply-id")
            var errors = 0
            client.onError = { errors++ }
            val frame = JSONObject()
                .put("op", "reply")
                .put("id", "reply-id")
                .put("from", "Ashleigh")
                .put("text", "hi")
                .put("seq", 1)
            client.handleSessionFrame(frame)
            assertFalse(inflight.isCompleted)
            assertEquals(0, errors)
        } finally {
            client.close()
        }
    }

    @Test
    fun helloOkThenReplySameThreadIsNotDropped() {
        val client = SessionClient()
        try {
            val helloId = "hello-1"
            val deferred = client.putInflight(helloId)
            var seen: PluginMessage? = null
            client.onReply = { seen = it }

            val helloOk = JSONObject()
                .put("v", 1)
                .put("ok", true)
                .put("id", helloId)
                .put("op", "hello")
                .put("sessionId", "sess-new")
                .put("seq", 6)
            client.handleSessionFrame(helloOk)
            assertTrue(client.isHelloed)
            assertEquals("sess-new", client.lastHelloSessionId)
            assertTrue(deferred.isCompleted)

            val reply = JSONObject()
                .put("op", "reply")
                .put("id", "r0")
                .put("from", "Ashleigh")
                .put("text", "I'll check.")
                .put("seq", 0)
                .put("live", false)
                .put("catchUp", true)
            client.handleSessionFrame(reply)

            assertNotNull(seen)
            assertEquals("sess-new", seen!!.sessionId)
            assertEquals(0L, seen.seq)
            assertTrue(seen.catchUp)
            assertFalse(seen.live)
        } finally {
            client.close()
        }
    }

    @Test
    fun beginSocketClearsLastHelloSessionId() {
        val client = SessionClient()
        try {
            client.handleSessionFrame(
                JSONObject()
                    .put("ok", true)
                    .put("op", "hello")
                    .put("id", "hello-1")
                    .put("sessionId", "sess-new")
                    .put("seq", 1),
            )
            assertEquals("sess-new", client.lastHelloSessionId)
            assertTrue(client.isHelloed)
            client.beginSocket()
            assertNull(client.lastHelloSessionId)
            assertFalse(client.isHelloed)
        } finally {
            client.close()
        }
    }

    @Test
    fun parseReplyDefaultsLiveTrue() {
        val client = SessionClient()
        try {
            client.handleSessionFrame(
                JSONObject()
                    .put("ok", true)
                    .put("op", "hello")
                    .put("id", "hello-1")
                    .put("sessionId", "sess-a")
                    .put("seq", 1),
            )
            var seen: PluginMessage? = null
            client.onReply = { seen = it }
            client.handleSessionFrame(
                JSONObject()
                    .put("op", "reply")
                    .put("id", "r1")
                    .put("from", "Ashleigh")
                    .put("text", "hi")
                    .put("seq", 1),
            )
            assertNotNull(seen)
            assertTrue(seen!!.live)
            assertFalse(seen.catchUp)
        } finally {
            client.close()
        }
    }

    @Test
    fun helloPayloadIncludesLastSeenSeqAndOptionalSessionId() {
        val withSid = buildHelloPayload("id1", "p".repeat(52), null, 3, "sess-a")
        assertEquals(3, withSid.getInt("lastSeenSeq"))
        assertEquals("sess-a", withSid.getString("sessionId"))
        val withoutSid = buildHelloPayload("id1", "p".repeat(52), null, -1, null)
        assertEquals(-1, withoutSid.getInt("lastSeenSeq"))
        assertFalse(withoutSid.has("sessionId"))
    }

    @Test
    fun errorBannerNeverShowsRawExceptionText() {
        assertEquals("Agent unavailable", agentErrorBanner("Cannot connect to host"))
        assertEquals("Agent unavailable", agentErrorBanner("timeout"))
        assertEquals("Agent unavailable", agentErrorBanner("no_gateway"))
        assertEquals("Agent unavailable", agentErrorBanner("gateway_error"))
        assertEquals("Agent unavailable", agentErrorBanner("no_agent"))
        assertEquals("Agent unavailable", agentErrorBanner("rejected"))
        assertEquals("Agent unavailable", agentErrorBanner("not_implemented"))
        assertEquals("Not connected", agentErrorBanner("not_connected"))
    }
}
