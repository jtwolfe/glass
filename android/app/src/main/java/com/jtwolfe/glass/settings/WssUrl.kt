package com.jtwolfe.glass.settings

import java.net.InetAddress
import java.net.URI

/**
 * Session URL. wss:// for a public host; ws:// only to a LAN/private IP.
 * 127.0.0.1 is rejected — on the phone that is the phone, not the plugin.
 */
data class WssUrl(val canonical: String) {
    companion object {
        fun parse(raw: String): WssUrl? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val uri = try {
                URI(text)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "wss" && scheme != "ws") return null
            if (!uri.userInfo.isNullOrEmpty()) return null
            if (!uri.rawQuery.isNullOrEmpty()) return null
            if (!uri.rawFragment.isNullOrEmpty()) return null

            val host = uri.host?.trim().orEmpty()
            if (host.isEmpty()) return null
            if (isLoopbackHost(host)) return null
            if (scheme == "ws" && !isPrivateLanHost(host)) return null

            val path = when (val p = uri.path.orEmpty()) {
                "", "/" -> "/session"
                else -> p
            }

            val hostPart = if (host.contains(':')) "[$host]" else host
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            return WssUrl("$scheme://$hostPart$portPart$path")
        }

        private fun isLoopbackHost(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true)) return true
            return runCatching {
                InetAddress.getByName(host).isLoopbackAddress
            }.getOrDefault(false)
        }

        private fun isPrivateLanHost(host: String): Boolean {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (addr.isLoopbackAddress) return false
            if (addr.isSiteLocalAddress) return true
            val bytes = addr.address
            if (bytes.size == 4) {
                val a = bytes[0].toInt() and 0xff
                val b = bytes[1].toInt() and 0xff
                // Tailscale / CGNAT 100.64.0.0/10
                if (a == 100 && b in 64..127) return true
            }
            return false
        }
    }
}
