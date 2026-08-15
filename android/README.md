# Glass Android

Jamie ↔ Ashleigh chat that can replace the default assistant on long-press home.

Nash owns this client. Quay owns the public HTTPS inbox. No routing, no other teams, no secrets in git.

## Assistant role

The app registers as a `VoiceInteractionService` and handles `ACTION_ASSIST`. On first run / Settings, it requests `RoleManager.ROLE_ASSISTANT` and falls back to system voice-input / default-assistant settings.

Long-press home (or any assist gesture) opens the Ashleigh chat.

## Inbox v0

Contract message:

```json
{"from":"jamie"|"ashleigh","text":"...","at":"<ISO-8601>"}
```

Assumed paths (Quay's inbox):

- `POST {base}/v0/messages` — send. Outgoing body always `"from":"jamie"`.
- `GET {base}/v0/messages` — JSON array of messages.
- `Authorization: Bearer <token>`

Configure **outside git**:

1. Copy `local.properties.example` → `local.properties` and set `glass.inbox.url` / `glass.inbox.token` (baked into `BuildConfig` at compile time), **and/or**
2. Enter URL + token on the in-app Settings screen (DataStore, device-only).

Runtime settings override BuildConfig when non-empty. If both are unset, chat stays **local-only** on device. Network errors never crash the app.

## How to run

1. Open the `android/` directory in Android Studio (not the repo root).
2. Generate the Gradle wrapper if missing:

   ```
   gradle wrapper --gradle-version 8.11.1
   ```

   or let Android Studio create `gradle/wrapper/gradle-wrapper.jar`. That jar is binary and is not in git.
3. Set `sdk.dir`, `glass.inbox.url`, and `glass.inbox.token` in `local.properties`.
4. Sideload the debug APK.
5. Grant Glass as the default assistant (Settings in-app, or system assistant settings).
6. Long-press home to open Ashleigh.

## Layout

```
android/          this project (Quay can add inbox/ later at repo root)
  app/            applicationId com.jtwolfe.glass
```

minSdk 29, compile/target 35.
