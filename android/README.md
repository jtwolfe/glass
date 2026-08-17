# Glass Android

Phone client for the operator WSS session. Scan the plugin QR, dial **your** Session URL, talk. One install, one plugin.

The join path is `wss://` to the operator hostname (nginx on the same host/netns as `glass-peer`). There is no mDNS browse, no LAN TCP `:8711`, no HTTP inbox, and no FCM / ntfy.

---

## Quick Start: Install → Session URL → Scan → Talk

1. **Install** the debug APK (`./gradlew assembleDebug`, or the GitHub Release `apk-debug-0.1.0` when you have one). Sideload; allow unknown sources when prompted.

2. **Set as default assistant**: Settings → Apps → Default apps → Digital assistant app → Glass.

3. **Grant microphone** when prompted on first voice use.

4. **Set Session URL** in Settings (required unless the QR includes a `wss` hint):

   - Field: **Session URL**
   - Placeholder: `wss://chat.example.com/session`
   - Scheme `wss` only (`ws` / `http` / `https` are rejected)
   - Bare `wss://chat.example.com` (or `wss://chat.example.com/`) canonicalizes to `wss://chat.example.com/session`
   - Settings overrides a QR `wss` hint and survives unpair / remint

   Same-host nginx (`location = /session` → `http://127.0.0.1:8711`) is documented in the [root README](../README.md). The phone never dials `:8711` itself.

5. **Scan the plugin v1 QR** in Settings → Pair Plugin. The QR is raw JSON (UTF-8, not a URL):

   ```json
   {"v":1,"peer":"<52 char lowercase base32>","pub":"<64 hex>","code":"<8 Crockford>","exp":"<ISO>","wss":"wss://chat.example.com/session"}
   ```

   - `peer`: 52-char lowercase RFC 4648 base32 (a-z2-7) of SHA-256(pub)
     Example shape: `5coyrsvqsuzekhvfx3vlp7g4gr3aqphxrhqp6dllcwbi7xlfok4q`
   - `pub`: 64 hex chars (identity material)
   - `code`: 8-char Crockford — `0-9A-HJKMNP-TV-Z`, **no I L O U**. **`0` and `1` are valid.**
   - `exp`: ISO-8601 expiration (plugin default 300s)
   - `wss`: optional reachability hint. Used only when Settings is empty (then pre-filled). Not pairing identity.
   - Phone does not mint. Paste JSON works if you cannot scan.

6. **Talk** when the header says **Connected** (hello-ok). Plugin off, nginx `502`, or a wrong URL is **Offline**. One send can produce several assistant rows. Background / doze may drop the socket; the next hello shows this-session missed replies as **text only** (silent). There is no FCM / ntfy.

### Pairing notes (v1)

- Phone scans or pastes JSON. It does not call `GET /pair`.
- URL resolve: Settings → QR `wss` hint → last successful URL for this plugin. Missing all three: “Set session URL in Settings.”
- One phone. Remint on the host (`curl` to `http://127.0.0.1:8080/pair`) evicts this install, wipes the local thread, and wipes the peer this-session log.
- Phone Settings picks a **Grok Bot desktop PA** (named roster). The process is still `glass-peer`; the phone does not pair to Grok Bot or to the `grok` CLI.

---

## Assistant role

The app registers as a `VoiceInteractionService` and handles `ACTION_ASSIST`. On first run / Settings, it requests `RoleManager.ROLE_ASSISTANT` and falls back to system voice-input / default-assistant settings.

Long-press home (or any assist gesture) opens the chat and **immediately starts listening** for voice input.

## Voice input & output

### Microphone permission

The app requests `RECORD_AUDIO` permission when you first try to use voice.

### Hold-to-talk

- **Mic button** in the composer: hold to talk, release to send
- **On assist open** (long-press home): automatically starts listening
- Default: Android `SpeechRecognizer` (on-device STT)
- Final transcript posts just like typed messages

### Spoken replies

When the assistant replies on the live pipe **in the foreground**:

1. Try xAI TTS if logged in (bearer on device only)
2. Fall back to Android `TextToSpeech`

Clips play **one after another**. The next clip is prefetched while the current one plays. A new live reply does not cut the previous clip.

Catch-up (hello flush after the process was dead) and replies that arrive in the background are **text only** — they never speak, including on resume. Hello sends integer `lastSeenSeq` so the peer can flush this session only; older APKs that omit the field get no flush.

TTS is watermarked (`sessionId`, `seq`). Remint / unpair / reinstall → empty thread, no TTS of previous messages.

TTS stops if you start a new voice input or leave the foreground.

### Keyboard fallback

The typed composer remains available.

---

## How to run

1. Open the `android/` directory in Android Studio (not the repo root).
2. Generate the Gradle wrapper if missing:

   ```
   gradle wrapper --gradle-version 8.11.1
   ```

   or let Android Studio create `gradle/wrapper/gradle-wrapper.jar`.
3. Set `sdk.dir` in `local.properties` (see `local.properties.example`).
4. Sideload the debug APK.
5. Set Session URL, grant Glass as the default assistant, scan the plugin QR.
6. Long-press home to open the chat.

## Layout

```
android/          this project
  app/            applicationId com.jtwolfe.glass
```

minSdk 29, compile/target 35.
