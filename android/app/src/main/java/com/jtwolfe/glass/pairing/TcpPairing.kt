package com.jtwolfe.glass.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP pairing for glass-pair v1.
 *
 * After mDNS discovery finds the plugin, connect via TCP and exchange one JSON line:
 *
 * Request:  {"v":1,"code":"<8 Crockford>"}\n
 * Response: {"v":1,"ok":true}\n  → paired
 *           anything else        → not paired, show error
 *
 * No libp2p, no unsigned-varint, no PSK, no provision ECDH.
 * The code binds the one-time invite. Plugin keeps provision material.
 */
object TcpPairing {

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val OVERALL_TIMEOUT_MS = 10000L

    /**
     * Connect to the plugin via TCP and perform pairing handshake.
     *
     * @param host Resolved host from mDNS (no baked IPs)
     * @param port Resolved port from mDNS
     * @param code 8-char Crockford code from QR
     * @return TcpPairResult with success/error status
     */
    suspend fun pair(host: String, port: Int, code: String): TcpPairResult =
        withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(OVERALL_TIMEOUT_MS) {
                pairInternal(host, port, code)
            }
            result ?: TcpPairResult.Timeout
        }

    private fun pairInternal(host: String, port: Int, code: String): TcpPairResult {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            val request = JSONObject()
                .put("v", 1)
                .put("code", code)
                .toString()

            writer.write(request)
            writer.write("\n")
            writer.flush()

            val responseLine = reader.readLine()
                ?: return TcpPairResult.Error("Connection closed by plugin")

            return parseResponse(responseLine)
        } catch (e: java.net.SocketTimeoutException) {
            return TcpPairResult.Timeout
        } catch (e: java.net.ConnectException) {
            return TcpPairResult.Error("Could not connect to plugin: ${e.message}")
        } catch (e: Exception) {
            return TcpPairResult.Error(e.message ?: "Connection failed")
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun parseResponse(line: String): TcpPairResult {
        return try {
            val json = JSONObject(line)
            val v = json.optInt("v", -1)
            val ok = json.optBoolean("ok", false)

            if (v == 1 && ok) {
                TcpPairResult.Success
            } else {
                val error = json.optString("error", "").ifBlank { null }
                TcpPairResult.Rejected(error ?: "Plugin did not accept pairing")
            }
        } catch (_: Exception) {
            TcpPairResult.Error("Invalid response from plugin")
        }
    }
}

sealed class TcpPairResult {
    data object Success : TcpPairResult()
    data object Timeout : TcpPairResult()
    data class Rejected(val reason: String) : TcpPairResult()
    data class Error(val message: String) : TcpPairResult()
}
