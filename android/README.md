# Glass Android Client

Android assistant app that replaces the default long-press assistant (Gemini). Chat is Jamie ↔ Ashleigh only.

## Prerequisites

- Android Studio (Ladybug or later recommended)
- Android device or emulator running Android 10+ (API 29+)

## Setup

### 1. Clone and open

```bash
git clone https://github.com/jtwolfe/glass.git
cd glass/android
```

Open the `android/` folder in Android Studio.

### 2. Generate Gradle wrapper

If building from command line without the wrapper, Android Studio generates it automatically when you sync the project. Alternatively:

```bash
gradle wrapper --gradle-version 8.9
```

### 3. Configure inbox (optional)

Copy the example config:

```bash
cp local.properties.example local.properties
```

Edit `local.properties` and set:

```properties
glass.inbox.url=https://your-inbox-host.example.com
glass.inbox.token=your-glass-phone-token
```

- **URL**: Public HTTPS endpoint for the inbox (never localhost)
- **Token**: `GLASS_PHONE_TOKEN` issued by your inbox administrator

If left blank, the app runs in local-only mode (messages stay on device).

**Do not commit `local.properties` to git.**

## Build and Install

### From Android Studio

1. Connect your device via USB (enable USB debugging)
2. Click **Run** or press `Shift+F10`

### From command line

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Set as Default Assistant

After installing, you must set Glass as the default assistant to enable long-press activation:

### Method 1: In-app prompt

Launch the app. It will prompt you to set Glass as the default assistant.

### Method 2: System settings

1. Go to **Settings** → **Apps** → **Default apps** → **Digital assistant app**
2. Select **Glass**

### Method 3: ADB

```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.jtwolfe.glass
```

## Usage

Long-press the home button (or power button on some devices) to open Glass instead of Google Assistant / Gemini.

- Type messages to Ashleigh
- Messages from Jamie are sent to the inbox (if configured)
- Replies from Ashleigh appear automatically via polling

## Inbox Contract (v0)

The app speaks to a public HTTPS inbox:

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/v0/messages` | POST | Bearer token | Send message `{"from":"jamie","text":"...","at":"<ISO-8601>"}` |
| `/v0/replies` | GET | Bearer token | Poll replies `?after=<ISO-8601>&limit=50` |
| `/v0/health` | GET | None | Health check `{"ok":true}` |

The phone only POSTs `from=jamie`. Replies are always `from=ashleigh`.

## Project Structure

```
android/
├── app/
│   └── src/main/
│       ├── java/com/jtwolfe/glass/
│       │   ├── data/          # Message models, repository
│       │   ├── network/       # Retrofit API client
│       │   ├── service/       # VoiceInteractionService
│       │   └── ui/            # Compose UI, ViewModel
│       └── res/
│           ├── xml/           # interaction-service metadata
│           └── values/        # strings, themes
├── build.gradle.kts
├── local.properties.example
└── README.md
```
