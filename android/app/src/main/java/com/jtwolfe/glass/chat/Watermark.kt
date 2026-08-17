package com.jtwolfe.glass.chat

object Watermark {
    fun shouldSpeak(
        seq: Long?,
        sessionId: String?,
        currentSessionId: String?,
        lastSpokenSeq: Long,
        live: Boolean,
        foreground: Boolean,
    ): Boolean {
        if (!live || !foreground) return false
        if (seq == null) return false
        if (sessionId.isNullOrBlank() || currentSessionId.isNullOrBlank()) return false
        if (sessionId != currentSessionId) return false
        return seq > lastSpokenSeq
    }

    /** Next watermark if [incoming] is newer; otherwise [current]. */
    fun advance(current: Long, incoming: Long): Long =
        if (incoming > current) incoming else current

    /**
     * On a new hello-ok session, both lastSeen and lastSpoken become [helloSeq] - 1.
     * Same session keeps existing watermarks (returns null).
     */
    fun helloResetSeq(previousSessionId: String?, newSessionId: String, helloSeq: Long): Long? {
        if (previousSessionId == newSessionId) return null
        return helloSeq - 1
    }
}
