# Glass Android

Jamie ↔ Ashleigh chat that can replace the default assistant on long-press home.

Nash owns this client. Quay owns the public HTTPS inbox. No routing, no other teams, no secrets in git.

## Assistant role

The app registers as a `VoiceInteractionService` and handles `ACTION_ASSIST`. On first run / Settings, it requests `RoleManager.ROLE_ASSISTANT` and falls back to system voice-input / default-assistant settings.

Long-press home (or any assist gesture) opens the Ashleigh chat and **immediately starts listening** for voice input.

## Voice input & output

### Microphone permission

The app requests `RECORD_AUDIO` permission when you first try to use voice. A brief rationale explains that Jamie's voice goes to Ashleigh.

### Hold-to-talk

- **Mic button** in the composer: hold to talk, release to send
- **On assist open** (long-press home): automatically starts listening
- Default: Android `SpeechRecognizer` (on-device STT, no paid cloud)
- Optional: `POST /v0/stt` multipart field `file` → `{text}` when SuperGrok is mounted on the inbox host. `503 credential_unavailable` fails closed to on-device STT. The phone does not send a model name (host sets grok-stt).
- Final transcript posts to the inbox just like typed messages

### Spoken replies

When Ashleigh replies (`GET /v0/replies`):

1. Try `GET /v0/replies/{id}/audio` (audio/mpeg, phone token).
2. `503 credential_unavailable` or any miss → Android `TextToSpeech` of Ashleigh's text.

TTS / mpeg stops if you start a new voice input.

### Keyboard fallback

The typed composer remains available.

## P2P Stream (glass-pair/v0)

After scanning the inbox QR code, the phone dials the inbox via libp2p and opens the `/glass/inbox/v0` stream protocol.

### Pairing handshake

1. Phone scans QR → parses `{v,peer,addrs,proto,code,psk,exp}`.
2. Phone dials inbox multiaddrs (TCP + Noise + Mplex).
3. Opens stream `/glass/inbox/v0`.
4. First frame: `{"psk":"<64 hex from QR>"}` (unsigned-varint length-prefixed JSON).
5. Inbox verifies PSK and replies: `{status:200,body:{paired:true}}`.
6. Subsequent frames: HTTP-like verbs with `Authorization: Bearer $GLASS_PHONE_TOKEN`.

### Frame format

All frames are **unsigned-varint** (multiformats) length prefix followed by UTF-8 JSON bytes. Same format in both directions.

### Stream API

After pairing, the stream accepts JSON requests:

- `POST /v0/messages {from,text,at}` → `{status:201,body:{id,from,text,at}}`
- `GET /v0/replies?after=&limit=50` → `{status:200,body:{messages:[...]}}`
- `GET /v0/health` → `{status:200,body:{ok:true}}`

Each request includes `"authorization":"Bearer <token>"` in the JSON (the inbox phone token from Settings, NOT the xAI bearer).

### Transport fallback

If the P2P stream fails to connect or drops, the app falls back to HTTPS. The stream client automatically reconnects on next send/poll.

### Typed-code pairing (needs relay)

When the user types the 8-char Crockford code instead of scanning QR:

1. Phone subscribes to gossipsub topic `/glass/pair/<CODE>`.
2. Inbox publishes the full glass-pair/v0 JSON as raw UTF-8 bytes (NOT varint-prefixed).
3. Phone parses the invite and performs the same dial + PSK handshake.

**Blocker**: Typed-code path requires a relay multiaddr configured. Without relay, users must scan the QR code. The gossipsub subscription is stubbed pending inbox PR #3 relay availability.

### jvm-libp2p on Android

The app uses `io.libp2p:jvm-libp2p:1.3.5-RELEASE` for P2P transport. This compiles and runs on Android (minSdk 29+). The library provides:

- TCP transport
- Noise security (NoiseXXSecureChannel)
- Mplex stream multiplexing
- Gossipsub pubsub (API integrated, typed-code path stubbed)

No custom PSK/pnet is used — the PSK is sent as the first-frame payload for pairing verification.

---

## Inbox v0 (HTTPS fallback)

Contract message:

```json
{"from":"jamie"|"ashleigh","text":"...","at":"<ISO-8601>"}
```

Phone paths (Quay):

- `POST {base}/v0/messages` — send. Body always `"from":"jamie"`. Expect 201 `{id,from,text,at}`.
- `GET {base}/v0/replies?after=<ISO-8601>&limit=50` — poll Ashleigh replies. `after` is exclusive on `at`. Expect 200 `{messages:[...]}`.
- `POST {base}/v0/stt` — multipart `file=<audio>`. 200 `{text}`. 503 → on-device STT.
- `GET {base}/v0/replies/{id}/audio` — audio/mpeg. 503 → on-device TTS.
- `GET {base}/v0/health` — no auth, `{ok:true}`.
- `Authorization: Bearer <token>` (`GLASS_PHONE_TOKEN` via `local.properties`).

Do **not** call `GET /v0/messages`. That path is pane-only (Ashleigh).

Configure **outside git**:

1. Copy `local.properties.example` → `local.properties` and set `glass.inbox.url` / `glass.inbox.token`, **and/or**
2. Enter URL + token on the in-app Settings screen (DataStore, device-only).

Runtime settings override BuildConfig when non-empty. If both are unset, chat stays **local-only**. Network errors never crash the app. Do not commit the live tunnel URL or token.

## How to run

1. Open the `android/` directory in Android Studio (not the repo root).
2. Generate the Gradle wrapper if missing:

   ```
   gradle wrapper --gradle-version 8.11.1
   ```

   or let Android Studio create `gradle/wrapper/gradle-wrapper.jar`.
3. Set `sdk.dir`, `glass.inbox.url`, and `glass.inbox.token` in `local.properties`.
4. Sideload the debug APK (or the GitHub Release `apk-debug-0.1.0`).
5. Grant Glass as the default assistant.
6. Long-press home to open Ashleigh.

## Layout

```
android/          this project (Quay can add inbox/ later at repo root)
  app/            applicationId com.jtwolfe.glass
```

minSdk 29, compile/target 35.
