package com.jtwolfe.glass.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * LAN discovery for glass-pair v1 using mDNS/NSD.
 *
 * Service type: _glass-pair._tcp.
 * Instance name: <52 char unpadded base32 of 32-byte device id>
 *   (DNS labels max at 63 chars; 64-hex would fail on Avahi/NsdManager)
 *
 * Discovery flow:
 * 1. Phone scans v1 QR → gets peer (52-char base32 or 64-hex), pub, code, exp
 * 2. Phone derives 52-char base32 mDNS instance name from peer
 * 3. Phone browses _glass-pair._tcp. on LAN
 * 4. If instance name equals target: found plugin on LAN
 * 5. Take host:port from NSD advertisement only (no baked values)
 * 6. Store resolved host in memory only (never git)
 * 7. If nothing found in ~10s: fail-closed, "Plugin not on this LAN"
 */
class LanDiscovery(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    @Volatile
    private var resolvedHost: ResolvedPlugin? = null

    val currentResolvedHost: ResolvedPlugin?
        get() = resolvedHost

    /**
     * Browse for plugin advertising the given instance name on LAN.
     * Timeout after ~10 seconds if not found.
     *
     * @param instanceName 52-char unpadded base32 mDNS instance name (from invite.mdnsInstanceName)
     * @return ResolvedPlugin if found, null otherwise
     */
    suspend fun discoverPlugin(instanceName: String): ResolvedPlugin? =
        withContext(Dispatchers.IO) {
            _state.value = DiscoveryState.Searching
            resolvedHost = null

            val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                discoverPluginInternal(instanceName.uppercase())
            }

            if (result != null) {
                resolvedHost = result
                _state.value = DiscoveryState.Found(result)
            } else {
                _state.value = DiscoveryState.NotFound
            }

            result
        }

    private suspend fun discoverPluginInternal(targetInstanceName: String): ResolvedPlugin? =
        suspendCancellableCoroutine { cont ->
            var listener: NsdManager.DiscoveryListener? = null
            var resolved = false

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    // Keep searching
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    if (resolved || serviceInfo == null) return

                    val host = serviceInfo.host
                    val port = serviceInfo.port
                    if (host != null && port > 0 && !isLoopbackOrUnroutable(host)) {
                        resolved = true
                        try {
                            listener?.let { nsdManager.stopServiceDiscovery(it) }
                        } catch (_: Exception) {
                        }
                        cont.resume(
                            ResolvedPlugin(
                                instanceName = targetInstanceName,
                                host = host.hostAddress ?: host.canonicalHostName,
                                port = port,
                            )
                        )
                    }
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {
                    // Discovery started
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    if (!resolved && cont.isActive) {
                        cont.resume(null)
                    }
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (resolved || serviceInfo == null) return

                    val serviceName = serviceInfo.serviceName?.uppercase() ?: ""
                    if (serviceName == targetInstanceName) {
                        try {
                            nsdManager.resolveService(serviceInfo, resolveListener)
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    // Service lost, keep searching
                }

                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    // Ignore
                }
            }

            cont.invokeOnCancellation {
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (_: Exception) {
                }
            }

            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                cont.resume(null)
            }
        }

    fun reset() {
        resolvedHost = null
        _state.value = DiscoveryState.Idle
    }

    companion object {
        const val SERVICE_TYPE = "_glass-pair._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 10_000L

        private fun isLoopbackOrUnroutable(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress) return true

            val hostAddr = addr.hostAddress ?: return false
            return hostAddr.startsWith("127.") ||
                hostAddr.startsWith("172.17.") ||
                hostAddr == "::1" ||
                hostAddr == "0.0.0.0"
        }
    }
}

/**
 * Resolved plugin host info (memory only, never persisted to git).
 * host:port taken from NSD advertisement only.
 */
data class ResolvedPlugin(
    val instanceName: String,
    val host: String,
    val port: Int,
)

sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data object Searching : DiscoveryState()
    data class Found(val plugin: ResolvedPlugin) : DiscoveryState()
    data object NotFound : DiscoveryState()
}
