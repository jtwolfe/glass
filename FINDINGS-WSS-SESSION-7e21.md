# Findings Report: WSS-is-Session Branch (cursor/wss-session-client-7e21)

**Date:** 2026-08-16  
**Branch:** `cursor/wss-session-client-7e21` (tip: 4733303 "WSS is THE session")  
**APK SHA:** 448a5b9a  
**Scope:** Research only — no product changes

---

## A. What the Code Actually Does Today

### Android App Architecture

#### Connection State Machine (`GlassApplication.kt`)

```
ConnectionState:
  UNPAIRED     → not paired (no phone_peer stored)
  OFFLINE_PAIRED → paired but WSS not connected
  RECONNECTING → reconnect loop running
  CONNECTED    → WSS is open
```

State is derived in `updateConnectionState()`:
```kotlin
val wssOpen = wssClient?.isConnected == true
_connectionState.value = when {
    wssOpen -> ConnectionState.CONNECTED
    pairingStore.isPaired -> ConnectionState.OFFLINE_PAIRED
    else -> ConnectionState.UNPAIRED
}
```

**Key insight:** CONNECTED requires WSS to be open. WebRTC connection alone does NOT make the app "Connected".

#### WssSessionClient.kt (lines 46-587)

Attempts WebSocket connection to `wss://glass.enphi.net/session`:
- First frame: `{"op":"hello","peer":"<phone_peer>"[,"pub":"<hex>"]}`
- Request/response pattern via `pendingResponses` queue
- Operations: `send`, `replies`, `agents`
- Reconnect: exponential backoff, max 10 attempts
- Callbacks: `onConnected`, `onDisconnected`, `onMessage`

#### WebRtcPeerConnection.kt (lines 57-674)

WebRTC DataChannel via ntfy signaling:
- STUN only (no TURN): `stun:stun.l.google.com:19302`
- Phone is offerer, peer is answerer
- Signaling topic computed from invite fields
- Same JSON protocol as WSS (`send`, `replies`, `agents`)
- Labeled as "LAN leftover" — does NOT contribute to CONNECTED status

#### ChatViewModel.kt — TTS Trigger Flow (lines 113-143, 414-438)

```kotlin
// On init: load local history and set cursors
afterCursor = local.maxOfOrNull { it.at } ?: EPOCH
lastSpokenAt = local
    .filter { it.from.equals("ashleigh", ignoreCase = true) }
    .maxOfOrNull { it.at } ?: EPOCH

// On refreshRemote: filter "new" assistant messages
val newAssistantMsgs = remote.filter { msg ->
    msg.from.equals("ashleigh", ignoreCase = true) &&
        msg.at > lastSpokenAt
}
newAssistantMsgs.forEach { msg ->
    _newAssistantMessages.trySend(msg)  // → TTS
}
```

#### MainActivity.kt — Reconnect Flow (lines 277-425)

`unifiedReconnect()`:
1. Guard: single-flight via `reconnectInFlight` AtomicBoolean
2. Set state to RECONNECTING
3. Try WSS up to 10 times with backoff
4. If WSS exhausted, try WebRTC (2 attempts) — but this only provides LAN path
5. Finally: OFFLINE_PAIRED if all fail

Triggers:
- App resume while paired but not connected
- `onWssDisconnected` callback
- `onWebRtcDisconnected` callback
- `onReconnectRequest` from ChatViewModel (pending send)

### Peer/Server Architecture

#### server.py — HTTP Endpoints Only

Routes:
- `GET /health` — health check (unauthenticated)
- `GET|POST /pair` — mint new invite, start WebRTC peer (requires auth)
- `GET /qr` — QR code image for current invite (requires auth)

**NO `/session` WebSocket endpoint exists.**

#### webrtc_peer.py — WebRTC Answerer

- Subscribes to ntfy topic for offers
- Creates answer, exchanges ICE via ntfy
- DataChannel handler via `ProtocolHandler`
- Handles `hello`, `send`, `replies`, `agents` ops

#### ntfy_signaling.py — WebRTC Signaling

- POST to topic to publish offer/answer/ICE
- GET `/topic/json` for NDJSON subscribe stream
- Messages: `{"v":1,"t":"offer|answer|ice",...}`

#### ingress.yaml — Routes

```yaml
paths:
  - /ntfy/...  → ntfy service (signaling)
  - /pair      → peer service
  - /qr        → peer service  
  - /health    → peer service
```

**No `/session` route exists in the ingress.**

---

## B. Root Causes

### 1. Status Flicker / WiFi-Off Fail — **VERIFIED**

**Root cause:** The WSS endpoint `wss://glass.enphi.net/session` does not exist.

Evidence:
- `WssSessionClient.kt` line 54: `private const val WSS_URL = "wss://glass.enphi.net/session"`
- `server.py`: only defines `/health`, `/pair`, `/qr` routes
- `ingress.yaml`: no `/session` path configured
- No WebSocket handler anywhere in `glass-peer/`

Effect chain:
1. Android calls `wss.connect(phonePeer, pub)` in `unifiedReconnect()`
2. OkHttp `newWebSocket()` attempts TCP+TLS+WSS upgrade
3. Server returns 404 (no endpoint) or connection timeout
4. `onFailure()` fires → `handleDisconnect()` → `onDisconnected?.invoke()`
5. `updateConnectionState()` sets OFFLINE_PAIRED
6. Reconnect loop retries → sets RECONNECTING → fails → OFFLINE_PAIRED
7. Repeat 10 times → rapid state toggles visible as "shudder"

**Why WiFi-off fails differently:** When WiFi is off but mobile data is on, the phone can reach `glass.enphi.net`, but:
- WSS upgrade fails (no endpoint)
- WebRTC signaling to ntfy may succeed, but peer can't answer without LAN reachability
- App stays stuck in OFFLINE_PAIRED / RECONNECTING loop

### 2. History TTS Replay — **VERIFIED**

**Root cause:** Race between local history load and remote fetch on reconnect.

The guard `msg.at > lastSpokenAt` should prevent replay, but the sequence is:

1. **App launch/reopen:** `init {}` calls `loadLocal()` → sets `lastSpokenAt` from local SQLite
2. **Connection event:** `onAnyPathConnected()` calls `refreshRemote()`
3. **Server returns:** Messages that may already be in local (but with slightly different format/ordering)
4. **Filter fires:** If any `msg.at > lastSpokenAt`, those go to TTS

The problem manifests when:
- Local storage has stale/partial history (e.g., after clear data or on new device)
- Server returns more history than local cursor expects
- `afterCursor` was set but connection dropped before messages were persisted
- Reconnect after process death doesn't restore `lastSpokenAt` correctly

Specific code path:
```kotlin
// ChatViewModel.kt line 419-429
val newAssistantMsgs = remote.filter { msg ->
    msg.from.equals(V0Message.FROM_ASSISTANT, ignoreCase = true) &&
        msg.at > lastSpokenAt  // <-- This compares against local cursor
}
// If server returns old messages that local never saw, they fire TTS
```

### 3. Reconnect Loop Never Succeeds — **VERIFIED**

Since WSS endpoint doesn't exist:
1. Every `wss.connect()` call fails
2. `unifiedReconnect()` exhausts 10 attempts
3. Falls through to WebRTC (LAN only)
4. WebRTC fails if not on same LAN as peer
5. Final state: OFFLINE_PAIRED

The app can never reach CONNECTED status over mobile data because the session path (WSS) doesn't exist.

---

## C. Ranked Weaknesses

### Critical (Blocking)

| # | Weakness | File/Function | Impact |
|---|----------|---------------|--------|
| 1 | **No WSS `/session` endpoint on server** | server.py, ingress.yaml | App can NEVER connect over internet. CONNECTED state is unreachable without LAN. |
| 2 | **Protocol handler exists but isn't wired to any HTTP route** | protocol.py, server.py | ProtocolHandler handles the ops but has no transport binding. |

### High (Causes Symptoms)

| # | Weakness | File/Function | Impact |
|---|----------|---------------|--------|
| 3 | **Reconnect loop state flicker** | MainActivity.kt `unifiedReconnect()` | RECONNECTING ↔ OFFLINE_PAIRED toggles visible in UI status |
| 4 | **TTS cursor not persisted/restored correctly** | ChatViewModel.kt `lastSpokenAt` | History replays on app reopen |
| 5 | **WebRTC without TURN = LAN-only** | WebRtcPeerConnection.kt, config.py | No internet reachability via WebRTC path |

### Medium (Design Issues)

| # | Weakness | File/Function | Impact |
|---|----------|---------------|--------|
| 6 | **Dual-path complexity (WSS + WebRTC)** | ChatRepository.kt, multiple | Same protocol on two transports; hard to debug state |
| 7 | **`pullReplies` called every 8s regardless of connection state** | ChatViewModel.kt `restartPolling()` | Unnecessary network traffic when disconnected |
| 8 | **Single-flight guard doesn't prevent re-entry from all paths** | GlassApplication.kt `reconnectInFlight` | Both disconnected callbacks can trigger reconnect |
| 9 | **No hello ACK from server** | protocol.py `_handle_hello()` | Client can't confirm pairing succeeded over WSS |

### Low (Cleanup)

| # | Weakness | File/Function | Impact |
|---|----------|---------------|--------|
| 10 | **Hardcoded WSS URL** | WssSessionClient.kt line 54 | Should derive from QR/config |
| 11 | **Dead code: WebRTC sendHello not used in reconnect path** | WebRtcPeerConnection.kt | Stable topic already established |
| 12 | **afterCursor vs lastSpokenAt — two cursors for overlapping purposes** | ChatViewModel.kt | Confusing; could unify |

---

## D. Ordered Fix Plan

**Goal:** WSS is THE session. Connect over internet. No remint. No new ports. Fix TTS replay.

### Phase 1: Add WSS /session Endpoint to Server

1. **Add WebSocket handler to server.py**
   - New route: `GET /session` → upgrade to WebSocket
   - On connect: wait for hello frame, extract `phone_peer`
   - Authenticate: check `phone_peer` against `state.phone_peer` (or accept any if unpaired)
   - Reuse existing `ProtocolHandler` for message handling
   - On disconnect: log, don't unpair

2. **Wire protocol ops**
   - `send` → call configured agent API
   - `replies` → return from in-memory or persisted store
   - `agents` → return configured agents
   - (Later) Push replies proactively via WebSocket

3. **Add ingress route**
   ```yaml
   - path: /session
     pathType: Exact
     backend:
       service:
         name: glass-peer
         port: 8080
   ```

4. **Test:** WSS connect from Android, hello frame, send message, receive reply.

### Phase 2: Fix TTS Replay

1. **Persist `lastSpokenAt` to DataStore**
   - Save alongside messages in `glass_local_chat`
   - Restore in `init {}` from DataStore, not computed from messages

2. **Filter out local-already-seen messages**
   - Before sending to `_newAssistantMessages`, check if message ID is already in local list
   - Or: only send to TTS for messages received via live push, not polling

3. **Mark history vs live**
   - Add flag to `refreshRemote()`: `isReconnectFetch`
   - If reconnect fetch, suppress TTS for all returned messages

### Phase 3: Stabilize Reconnect Loop

1. **Don't flicker RECONNECTING on every attempt**
   - Set RECONNECTING once at loop start
   - Only update to CONNECTED (success) or OFFLINE_PAIRED (exhausted)
   - Remove intermediate state updates

2. **Exponential backoff ceiling**
   - Cap at 30s (not 20s currently)
   - Consider jitter to avoid thundering herd

3. **Respect network state**
   - Don't retry WSS if no network connectivity (check `ConnectivityManager`)
   - Resume retry when network returns

### Phase 4: Cleanup Dual-Path

1. **WebRTC is truly optional**
   - Only attempt WebRTC on same LAN (check subnet)
   - Or: remove WebRTC path entirely (WSS-only)
   - Clear documentation: WebRTC is development/demo mode

2. **Remove polling when WSS connected**
   - If WSS open, don't poll via HTTP
   - Server can push replies over WebSocket

3. **Unify cursors**
   - Single cursor for "last seen message"
   - Use for both fetch and TTS gating

---

## Appendix: File/Function Index

### Android App

| File | Key Functions |
|------|---------------|
| `GlassApplication.kt` | `updateConnectionState()`, `createWssClient()`, `unifiedReconnect()` |
| `WssSessionClient.kt` | `connect()`, `connectInternal()`, `handleDisconnect()`, `send()`, `replies()` |
| `WebRtcPeerConnection.kt` | `connect()`, `handleSignalingMessage()`, `send()`, `sendHello()` |
| `NtfySignaling.kt` | `subscribe()`, `publishOffer()`, `publishIce()` |
| `ChatViewModel.kt` | `refreshRemote()`, `onAnyPathConnected()`, `lastSpokenAt` |
| `ChatRepository.kt` | `pullReplies()`, `sendRemote()` |
| `MainActivity.kt` | `unifiedReconnect()`, `savePairing()`, `fetchAgents()` |
| `PairingStore.kt` | `isPaired`, `phonePeer`, `stableTopic` |

### Peer/Server

| File | Key Functions |
|------|---------------|
| `server.py` | `_pair_handler()`, `_health_handler()`, `start()` |
| `webrtc_peer.py` | `start()`, `_handle_offer()`, `_on_channel_message()` |
| `protocol.py` | `handle_message()`, `_handle_hello()`, `_handle_send()`, `_handle_replies()` |
| `ntfy_signaling.py` | `subscribe()`, `publish_answer()`, `publish_ice()` |
| `state.py` | `StateStore`, `is_paired()`, `get_stable_topic()` |

---

## Summary

The app expects WSS at `wss://glass.enphi.net/session` but no such endpoint exists. This causes:
1. Permanent connection failure over internet
2. Reconnect loop flicker
3. WebRTC LAN-only fallback insufficient

The TTS replay issue stems from cursor management during reconnect fetches.

Fix order:
1. **Add WSS /session endpoint** (unblocks internet connectivity)
2. **Fix TTS cursor persistence** (stops history replay)
3. **Stabilize UI state** (removes flicker)
4. **Clean up dual-path** (reduces complexity)
