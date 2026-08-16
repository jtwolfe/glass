package com.jtwolfe.glass.p2p

import com.jtwolfe.glass.pairing.PairingInvite
import java.io.Closeable

/**
 * Subscribe to gossipsub topic /glass/pair/<code> to receive pairing invite.
 *
 * Used when user types the 8-char Crockford code AND has a relay configured.
 * The inbox publishes the full glass-pair/v0 JSON once as raw UTF-8 bytes (NOT varint-prefixed).
 *
 * NOTE: Gossipsub typed-code path requires additional jvm-libp2p pubsub API integration.
 * QR scan is the primary pairing path. Typed codes work when inbox PR #3 relay is live.
 */
class PairSubscriber : Closeable {

    /**
     * Subscribe to pairing topic via relay and wait for invite JSON.
     *
     * @param code 8-char Crockford code (A-Z2-7)
     * @param relayAddr Relay multiaddr (must be configured, do not invent)
     * @param timeoutMs How long to wait for the invite
     * @return PairingInvite if received, null if timeout or error
     */
    suspend fun subscribeAndWait(
        code: String,
        relayAddr: String,
        timeoutMs: Long = 90_000,
    ): PairingInvite? {
        // TODO(quay): Gossipsub typed-code subscription needs jvm-libp2p pubsub integration.
        // Topic: /glass/pair/<code>
        // Message: raw UTF-8 JSON (same as QR format, NOT varint-prefixed)
        // For now, users should scan the QR code instead.
        return null
    }

    override fun close() {
        // No resources to clean up in stub
    }
}

/**
 * Combined pairing flow for typed codes.
 * 1. Subscribe to gossipsub topic /glass/pair/<code>
 * 2. Receive full glass-pair/v0 JSON
 * 3. Dial and do QR stream steps
 */
class TypedCodePairing(
    private val subscriber: PairSubscriber = PairSubscriber(),
    private val streamClient: InboxStreamClient = InboxStreamClient(),
) : Closeable {

    /**
     * Perform typed-code pairing.
     *
     * @param code 8-char Crockford code
     * @param relayAddr Relay multiaddr (required for typed codes)
     * @return PairResult
     */
    suspend fun pairWithCode(
        code: String,
        relayAddr: String,
    ): TypedCodeResult {
        // Step 1: Subscribe to topic and get invite
        val invite = subscriber.subscribeAndWait(code, relayAddr)
            ?: return TypedCodeResult.InviteTimeout

        if (invite.isExpired) {
            return TypedCodeResult.Expired
        }

        val psk = invite.psk
            ?: return TypedCodeResult.Error("Invite missing PSK")

        // Step 2: Dial and pair (same as QR flow)
        streamClient.start()
        val result = streamClient.dialAndPair(
            peerId = invite.peer,
            addrs = invite.addrs,
            psk = psk,
            exp = invite.exp,
        )

        return when (result) {
            is PairResult.Success -> TypedCodeResult.Success(invite, streamClient)
            is PairResult.Expired -> TypedCodeResult.Expired
            is PairResult.Error -> TypedCodeResult.Error(result.message)
        }
    }

    override fun close() {
        subscriber.close()
        streamClient.close()
    }
}

sealed class TypedCodeResult {
    data class Success(val invite: PairingInvite, val client: InboxStreamClient) : TypedCodeResult()
    object InviteTimeout : TypedCodeResult()
    object Expired : TypedCodeResult()
    data class Error(val message: String) : TypedCodeResult()
}
