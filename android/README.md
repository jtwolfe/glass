# Glass Android

Phone client for **Glass — Assistant Interface for GrokBot**. Scan the plugin QR, set **your** Session URL, talk. One install, one `glass-peer`.

This app is a stopgap default-assistant shell until a first-party Android personal assistant exists. It is not an official xAI / SpaceXAI product.

The join path is `wss://` to **your** hostname (reverse proxy in front of `glass-peer`). There is no mDNS browse, no public `:8711`, no HTTP inbox, and no FCM / ntfy.

## Install → Session URL → Scan → Talk

1. **Install** the debug APK (`./gradlew assembleDebug`). Sideload; allow unknown sources when prompted.

2. **Set as default assistant**: Settings → Apps → Default apps → Digital assistant app → Glass.

3. **Grant microphone** when prompted on first voice use.

4. **Set Session URL** in Settings (required unless the QR includes a `wss` hint):

   - Field: **Session URL**
   - Placeholder: `wss://chat.example.com/session` (replace with your host)
   - `wss://` to a public host (not loopback)
   - `ws://` only to a LAN/private IP (not loopback). The committed network-security config forbids cleartext; add your LAN IP locally if you use this path
   - Bare `wss://chat.example.com` canonicalizes to `wss://chat.example.com/session`
   - Settings overrides a QR `wss` hint and survives unpair / remint

   The phone never dials `:8711` on the host unless you explicitly set a LAN `ws://` URL.

5. **Scan the plugin v1 QR** in Settings → Pair Plugin. The QR is raw JSON (UTF-8, not a URL):

   ```json
   {"v":1,"peer":"<52 char lowercase base32>","pub":"<64 hex>","code":"<8 Crockford>","exp":"<ISO>","wss":"wss://chat.example.com/session"}
   ```

   - `peer`: 52-char lowercase RFC 4648 base32 (a-z2-7) of SHA-256(pub)
   - `pub`: 64 hex chars
   - `code`: 8-char Crockford — `0-9A-HJKMNP-TV-Z`, **no I L O U**. **`0` and `1` are valid.**
   - `exp`: ISO-8601 expiration (plugin default 300s)
   - `wss`: optional reachability hint. Used only when Settings is empty. Not pairing identity.
   - Phone does not mint. Paste JSON works if you cannot scan.

6. **Talk** when the header says **Connected** (hello-ok). Plugin off, proxy `502`, or a wrong URL is **Offline**. One send can produce several assistant rows. Background / doze may drop the socket; the next hello shows this-session missed replies as **text only** (silent).

### Pairing notes

- Phone scans or pastes JSON. It does not call `GET /pair`.
- URL resolve: Settings → QR `wss` hint → last successful URL for this plugin.
- One phone. Remint on the host (`curl` to `http://127.0.0.1:8080/pair`) evicts this install, wipes the local thread, and wipes the peer this-session log.
- Settings picks a **Grok Bot desktop PA**. The process is still `glass-peer`; the phone does not pair to Grok Bot or to the `grok` CLI.

## Assistant role

The app registers as a `VoiceInteractionService` and handles `ACTION_ASSIST`. On first run / Settings, it requests `RoleManager.ROLE_ASSISTANT`.

Long-press home (or any assist gesture) opens the chat and **immediately starts listening**.

## Voice

- **Mic button**: hold to talk, release to send. Assist-open starts listening automatically.
- Default STT: Android `SpeechRecognizer`. If signed into xAI, speech can go to `api.x.ai` STT.
- Live foreground replies: xAI TTS if logged in, else Android `TextToSpeech`. Clips play one after another; the next clip is prefetched.
- Catch-up and background replies are **text only**, including on resume.
- Hello sends integer `lastSeenSeq` so the peer can flush this session only.
- TTS stops if you start a new voice input or leave the foreground.

## How to run

1. Open the `android/` directory in Android Studio (not the repo root).
2. Set `sdk.dir` in `local.properties` (see `local.properties.example`).
3. Sideload the debug APK.
4. Set Session URL, grant Glass as the default assistant, scan the plugin QR.
5. Long-press home to open the chat.

minSdk 29, compile/target 35.
