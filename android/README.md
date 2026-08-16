# Glass Android

Voice assistant app that connects to a glass-peer over WebRTC for chat.

## Quick Start

1. **Install** the APK (build from source or from release).

2. **Set as default assistant**: Settings → Apps → Default apps → Digital assistant app → Glass.

3. **Configure ntfy URL**: In app Settings, enter the public ntfy URL (e.g., `https://glass.example.com/ntfy`).

4. **Grant microphone** when prompted on first voice use.

5. **Scan the pairing QR** in Settings → Pair. The QR contains:

   ```json
   {"v":1,"peer":"<52-char base32>","pub":"<64 hex>","code":"<8 Crockford>","exp":"<ISO>"}
   ```

   - No host, no IP in QR — ntfy URL is configured separately
   - Code expires in ~5 minutes by default

6. **Talk** — Long-press home to open chat and speak.

## Architecture

```
Phone                            ntfy                          glass-peer
  │                               │                               │
  │── scan QR ──────────────────►│                               │
  │                               │                               │
  │── POST offer ───────────────►│◄── subscribe ─────────────────│
  │                               │                               │
  │◄── answer ───────────────────│◄── POST answer ───────────────│
  │                               │                               │
  │◄─── ICE candidates ──────────│◄─── ICE candidates ───────────│
  │                               │                               │
  │◄══════════ WebRTC DataChannel (encrypted, P2P) ══════════════►│
  │                               │                               │
  │── {op:hello, peer} ──────────────────────────────────────────►│
  │                               │                               │
  │◄══════════════ chat messages (never via ntfy) ═══════════════►│
```

### Signaling (ntfy)

- Topic is SHA-256 hash of `peer + pub + code` — unguessable
- Messages: `{"v":1,"t":"offer|answer|ice","sdp":"..."}`
- After DataChannel opens, ntfy is done

### Chat (WebRTC)

- DataChannel labeled "glass-pair"
- Protocol: JSON messages
  - `{op:"hello", peer:"<phone_peer_id>"}` — establish stable topic
  - `{v:1, op:"send", from:"jamie", text:"...", at:"<ISO>"}` — send message
  - `{v:1, op:"replies", after:"<ISO>", limit:50}` — fetch replies
  - `{v:1, op:"agents"}` — list available agents
- STUN only, no TURN — fail closed if NAT prevents direct connection

## Voice

### Input

- **Mic button**: Hold to talk, release to send
- **On assist open**: Automatically starts listening
- **xAI STT** (when logged in): Speech → api.x.ai/v1/stt
- **Fallback**: Android on-device SpeechRecognizer

### Output

- **xAI TTS** (when logged in): Reply → api.x.ai/v1/tts
- **Fallback**: Android TextToSpeech

### xAI Login

xAI credentials are for STT/TTS only, not the message bus. Login is optional — the app works with on-device speech without xAI.

## Build

```bash
cd android

# Create local.properties with your SDK path
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# Build debug APK
./gradlew assembleDebug

# Install to connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17+
- Android SDK 35 (compileSdk)
- minSdk 29 (Android 10)

## Pairing States

| State | Description |
|-------|-------------|
| Not paired | No invite, show "Scan QR" prompt |
| Invite pending | Have invite, waiting for WebRTC connection |
| Paired | Have stable topic, can reconnect without new QR |

### Remint

Scanning a new QR replaces the existing pair. The peer clears `phone_peer` and starts fresh on the new invite topic.

## Configuration

### In-App Settings

- **ntfy URL**: Public signaling server URL
- **xAI Login**: Optional, for cloud STT/TTS
- **Agent Selection**: Choose from available agents (after pairing)

### Build Configuration

Copy `local.properties.example` to `local.properties` and set:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Layout

```
android/
  app/                      applicationId com.jtwolfe.glass
    src/main/
      java/.../glass/
        assist/             VoiceInteractionService
        auth/               xAI OAuth
        chat/               Chat UI and ViewModel
        inbox/              Message types
        pairing/            QR scanning and state
        rtc/                WebRTC and ntfy signaling
        settings/           Agent settings
        ui/                 Compose UI
        voice/              STT/TTS helpers
```

## Legacy (Not Used)

The following are legacy paths and not part of the current architecture:

- **HTTP inbox**: Parked
- **P2P libp2p stream**: Removed
- **LAN/mDNS discovery**: Removed
- **Circuit relay**: Removed

Current path: ntfy signaling → WebRTC DataChannel → encrypted P2P chat.
