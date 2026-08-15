# Glass Android

Jamie ↔ Ashleigh chat that can replace the default assistant on long-press home.

Nash owns this client. Plugin is the pair target. Phone scans only. No container. HTTP inbox is parked.

---

## Quick Start: Install → Pair → Talk

1. **Install** the debug APK from GitHub Release `apk-debug-0.1.0` (`glass-debug-0.1.0.apk`). Sideload on device; allow unknown sources when prompted.

2. **Set as default assistant**: Settings → Apps → Default apps → Digital assistant app → Glass.

3. **Grant microphone** when prompted on first voice use.

4. **Scan the plugin v1 QR** in Settings → Pair Plugin. The QR is raw JSON (UTF-8, not a URL):

   ```json
   {"v":1,"peer":"<64 hex>","pub":"<64 hex>","code":"<8 Crockford>","exp":"<ISO>"}
   ```

   - `peer`: SHA-256 hex of the plugin Ed25519 device public key (64 hex chars)
   - `pub`: X25519 ephemeral provision public key (64 hex chars)
   - `code`: 8-char Crockford code (A-HJ-NP-Z2-9, no I L O 0 1)
   - `exp`: ISO-8601 expiration (~15 min)
   - No `addrs`, no `psk`. Phone does NOT mint.

5. **Phone browses `_glass-pair._tcp.`** on LAN to find the plugin.
   - Prefers instance name matching `peer` (64 hex)
   - Skips loopback (127.x), docker bridge (172.17.x)
   - If not found in ~10s: **fail-closed** — "Plugin not on this LAN"
   - If found: "Found plugin on LAN — waiting for pair accept"

6. **Talk** when pair accept is ready. Messages go to Ashleigh.

### Pairing protocol notes (v1)

- Phone scans only. Phone does NOT call GET /v0/pair.
- After scan, phone browses mDNS/NSD for `_glass-pair._tcp.` on LAN.
- Plugin advertises with instance name = peer (64 hex).
- LAN host stored in memory only (not git).
- Off-LAN connections fail closed — no relay, no fallback host.
- **Typed 8-char code pairing** (`/glass/pair/<code>` gossipsub) is **stubbed** — scan the QR or paste JSON.
- Routed agent is plugin config (default Ashleigh). Phone does not pick the agent.

### Service type

The phone browses for mDNS service type:

```
_glass-pair._tcp.
```

The plugin should advertise the same service type with instance name = peer (64 hex).

---

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
- Final transcript posts just like typed messages

### Spoken replies

When Ashleigh replies:

1. Try xAI TTS if logged in (bearer on device only)
2. Fall back to Android `TextToSpeech`

TTS stops if you start a new voice input.

### Keyboard fallback

The typed composer remains available.

---

## Legacy: P2P Stream (glass-pair/v0)

v0 pairing is legacy and kept only for backward compatibility. v1 is the current path.

### v0 Pairing handshake (legacy)

1. Phone scans QR → parses `{v,peer,addrs,proto,code,psk,exp}`.
2. Phone dials inbox multiaddrs (TCP + Noise + Mplex).
3. Opens stream `/glass/inbox/v0`.
4. First frame: `{"psk":"<64 hex from QR>"}` (unsigned-varint length-prefixed JSON).
5. Inbox verifies PSK and replies: `{status:200,body:{paired:true}}`.

### Frame format

All frames are **unsigned-varint** (multiformats) length prefix followed by UTF-8 JSON bytes.

---

## Legacy: HTTP Inbox (parked)

HTTP inbox is parked. v1 pairing uses LAN discovery instead.

Token in Settings is optional (only if bearer needed later). URL field is hidden/advanced.

Do **not** invent URLs, tokens, or relay addrs. Do not put secrets in git or the APK.

## How to run

1. Open the `android/` directory in Android Studio (not the repo root).
2. Generate the Gradle wrapper if missing:

   ```
   gradle wrapper --gradle-version 8.11.1
   ```

   or let Android Studio create `gradle/wrapper/gradle-wrapper.jar`.
3. Set `sdk.dir` in `local.properties`. URL/token are optional (HTTP inbox parked).
4. Sideload the debug APK (or the GitHub Release `apk-debug-0.1.0`).
5. Grant Glass as the default assistant.
6. Long-press home to open Ashleigh.

## Layout

```
android/          this project
  app/            applicationId com.jtwolfe.glass
```

minSdk 29, compile/target 35.
