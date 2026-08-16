# Glass

Voice assistant bridge: phone talks to a peer over WebRTC, the peer relays to agents.

## Architecture

```
┌─────────────┐                              ┌─────────────┐
│   Phone     │   ntfy signaling (HTTPS)     │  glass-peer │
│  (Android)  │◄────────────────────────────►│ (container) │
│             │                              │             │
│  - QR scan  │   WebRTC DataChannel         │  - /pair    │
│  - Voice    │◄────────────────────────────►│  - /qr      │
│  - Chat UI  │   (encrypted, P2P)           │  - Agents   │
└─────────────┘                              └─────────────┘
        │                                           │
        │  xAI STT/TTS (voice only)                 │
        └───────────────────────────────────────────┘
```

### Components

1. **Android app** — Voice assistant that replaces default. Long-press home → talk → chat UI.
2. **glass-peer** — Container that accepts WebRTC connections and handles agent communication.
3. **ntfy** — Signaling server for WebRTC offer/answer/ICE exchange. Chat never goes through ntfy.

### Pairing Flow

1. Operator calls `/pair` (authenticated) → generates QR with `{v, peer, pub, code, exp}`
2. Phone scans QR → computes ntfy topic as SHA-256 hash
3. Phone creates WebRTC offer → publishes to ntfy topic
4. Peer receives offer → creates answer → publishes to ntfy topic
5. Both exchange ICE candidates via ntfy
6. DataChannel opens → phone sends `{op: "hello", peer: "<phone_peer_id>"}`
7. Peer persists phone_peer → stable topic computed for reconnects
8. **Reminting** a new QR clears the existing pair and starts fresh

### Security Model

- **QR contains no secrets**: Only peer identity, public key, one-time code, and expiry
- **Topics are unguessable**: SHA-256 of invite fields → 64-char hex
- **Chat is encrypted**: WebRTC DTLS, never through ntfy after handshake
- **STUN only**: No TURN relay. Fail closed if NAT prevents direct connection
- **Consume-once invite**: After hello, stable topic replaces invite topic

See [SECURITY.md](SECURITY.md) for full threat model.

## Quick Start

No secrets in this repo. Inbox auth is configured outside git.

---

## xAI / Grok Login

The Android app supports xAI OAuth login for direct Grok STT/TTS from the phone.

### How to log in

1. Open the app → Settings → **xAI / Grok Login**
2. Tap **Login with xAI**
3. A browser opens to `auth.x.ai` — sign in with your xAI account
4. Enter the device code if prompted
5. Return to the app — you'll see "Logged in" with your email

### Where the token lives

- **EncryptedSharedPreferences** on the device (`glass_xai_auth`)
- Protected by Android Keystore (AES-256-GCM)
- Access token, refresh token, expiry, email
- Never committed to git, never logged, never sent to the inbox

### What it does

When logged in:
- **STT**: Voice is sent directly to `api.x.ai/v1/stt` (model: `grok-stt`)
- **TTS**: Replies are synthesized via `api.x.ai/v1/tts` (voice: `eve`)
- The xAI bearer **never leaves the phone** — not sent to the inbox public URL

When logged out or offline:
- **STT**: Falls back to Android's on-device `SpeechRecognizer`
- **TTS**: Falls back to Android's on-device `TextToSpeech`

### Logout

Settings → xAI / Grok Login → **Logout** wipes the encrypted store.

---

## Inbox Pairing (glass-pair/v0)

The phone pairs with the inbox via QR code or short code. Product path is P2P over libp2p.

### QR format (from inbox)

```json
{
  "v": 0,
  "peer": "<inbox libp2p peer id>",
  "addrs": ["/ip4/10.0.0.1/tcp/4001", "/p2p/<relay>/p2p-circuit"],
  "proto": "/glass/inbox/v0",
  "code": "K7M2Q9WH",
  "psk": "<64 hex chars, 32-byte swarm key>",
  "exp": "2026-08-16T08:00:00Z"
}
```

- `peer`: Inbox libp2p peer ID
- `addrs`: Inbox multiaddrs (may include circuit-relay)
- `proto`: Stream protocol after pair (`/glass/inbox/v0`)
- `code`: 8 char Crockford (A-Z2-7), also printed for manual entry
- `psk`: Private swarm key (QR only, 64 hex = 32 bytes) — stored encrypted, never git
- `exp`: ISO-8601, 15 minutes from mint

### How to pair

1. Open the app → Settings → **Pair Inbox**
2. **Scan the QR** from your inbox setup screen, or
3. **Enter the short code** (requires relay configured)
4. After successful scan, you'll see "Paired (HTTPS fallback until swarm is live)"

### Pairing state

- Stored in **EncryptedSharedPreferences** (`glass_pairing`)
- PSK never committed to git, never logged
- Persists across app restarts

### P2P vs HTTPS

- **Product path**: P2P over libp2p protocol `/glass/inbox/v0` with Noise + PSK
- **Current transport**: HTTPS fallback until Quay locks the swarm
- **Advanced settings**: Manual HTTPS URL/token configuration (hidden by default)

### After pair

Communication uses protocol `/glass/inbox/v0`:
- `POST /v0/messages {from, text, at}` → 201
- `GET /v0/replies?after=&limit=50` → `{messages}`
- `GET /v0/health`

Authorization: `Bearer $GLASS_PHONE_TOKEN` (inbox phone token, not the xAI bearer).

---

## On-device fallback

Voice always works, even without login or pairing:

| Feature | With xAI login | Without login |
|---------|---------------|---------------|
| STT | `api.x.ai/v1/stt` | Android `SpeechRecognizer` |
| TTS | `api.x.ai/v1/tts` | Android `TextToSpeech` |

Fallback triggers on:
- Not logged in
- Offline / network error
- xAI API returns error
- Inbox `/v0/stt` returns 503

---

## OAuth client ID

The app uses the public xAI OIDC client (same as Grok CLI / OpenClaw):

```
Client ID: b1a00492-073a-47ea-816f-4c329264a828
Scope: openid profile email offline_access grok-cli:access api:access
```

### Potential blocker

If xAI requires a registered Android redirect URI for production:
- Currently uses device-code flow (no redirect needed)
- Chrome Custom Tabs open `verification_uri_complete`
- User authorizes in browser, app polls for token

If xAI blocks this client ID for Android apps, we'll need to register a custom redirect URI scheme (`com.jtwolfe.glass://oauth/callback`).

---

## libp2p (future)

P2P transport is stubbed but not yet active:

```kotlin
// TODO(quay): Add io.libp2p:jvm-libp2p when glass-pair swarm is live.
// Currently HTTPS inbox remains the transport; P2P is the product path.
```

The pairing UI captures and stores the peer ID, multiaddrs, and PSK. Once Quay locks the transport, the app will connect via libp2p Noise with the PSK as a pre-shared key.
