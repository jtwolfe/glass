package com.jtwolfe.glass.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WssUrlTest {
    @Test
    fun parseAcceptsDocumentedSessionUrl() {
        val parsed = WssUrl.parse("wss://chat.example.com/session")
        assertNotNull(parsed)
        assertEquals("wss://chat.example.com/session", parsed!!.canonical)
    }

    @Test
    fun parseCanonicalizesBareHostToSession() {
        val parsed = WssUrl.parse("wss://chat.example.com")
        assertNotNull(parsed)
        assertEquals("wss://chat.example.com/session", parsed!!.canonical)
    }

    @Test
    fun parseCanonicalizesRootPathToSession() {
        val parsed = WssUrl.parse("wss://chat.example.com/")
        assertNotNull(parsed)
        assertEquals("wss://chat.example.com/session", parsed!!.canonical)
    }

    @Test
    fun parseKeepsExplicitPathPrefix() {
        val parsed = WssUrl.parse("wss://existing.example.com/glass/")
        assertNotNull(parsed)
        assertEquals("wss://existing.example.com/glass/", parsed!!.canonical)
    }

    @Test
    fun parseAcceptsPublicWssHost() {
        val parsed = WssUrl.parse("wss://assistant.example.org/session")
        assertNotNull(parsed)
        assertEquals("wss://assistant.example.org/session", parsed!!.canonical)
    }

    @Test
    fun parseAcceptsLanWs() {
        val parsed = WssUrl.parse("ws://192.168.0.10:8711/session")
        assertNotNull(parsed)
        assertEquals("ws://192.168.0.10:8711/session", parsed!!.canonical)
    }

    @Test
    fun parseRejectsLoopback() {
        assertNull(WssUrl.parse("ws://127.0.0.1:8711/session"))
        assertNull(WssUrl.parse("wss://127.0.0.1:8711/session"))
        assertNull(WssUrl.parse("ws://localhost:8711/session"))
    }

    @Test
    fun parseRejectsPublicWsAndHttp() {
        assertNull(WssUrl.parse("ws://chat.example.com/session"))
        assertNull(WssUrl.parse("https://chat.example.com/session"))
        assertNull(WssUrl.parse("http://chat.example.com/session"))
    }

    @Test
    fun parseRejectsUserinfo() {
        assertNull(WssUrl.parse("wss://user:pass@chat.example.com/session"))
    }

    @Test
    fun parseRejectsQueryAndFragment() {
        assertNull(WssUrl.parse("wss://chat.example.com/session?code=K7M2Q9WH"))
        assertNull(WssUrl.parse("wss://chat.example.com/session#frag"))
    }
}
