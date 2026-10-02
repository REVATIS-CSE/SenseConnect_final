# SenseConnect

**One Device. Three Disabilities.**

SenseConnect is an integrated assistive-technology platform for Android that brings **vision**, **hearing** and **speech/communication** assistance together with **emergency SOS** in a single accessible mobile app, backed by a small cloud API hosted on Render.

---

## 1. Problem statement

People with visual, hearing or speech impairments typically juggle several single-purpose apps (a text reader, a captioning app, an AAC board, an emergency app), each with its own interface and permissions. They rarely share settings, history or accessibility preferences, and few work fully offline. SenseConnect's objective is **one coherent assistive companion** that:

- reads printed text aloud for blind and low-vision users,
- converts nearby speech into large live captions for deaf and hard-of-hearing users,
- speaks phrases or typed messages for users who cannot speak,
- sends a location-aware emergency alert that never fakes delivery,
- runs its core features **on-device and offline**, with the cloud as an optional extra.

## 2. Features

| Module | Pipeline | What the user gets |
|---|---|---|
| **Vision Assistance** | Camera → on-device OCR (ML Kit) → recognised text → text-to-speech | Live camera preview with a scan frame, live "text detected – hold steady" guidance, capture, a processing indicator, read aloud / stop, copy, share, scan a saved photo, torch toggle, auto-read option |
| **Hearing Assistance** | Microphone → `SpeechRecognizer` (continuous, partial results) → live captions | Large live caption, listening state with mic level meter and timer, session transcript, copy / share / clear, offline-first recognition, a typed reply spoken through TTS |
| **Communication** | Phrase / typed message → text-to-speech → spoken | Categorised board (Emergency, Medical, Basic needs, Food & water, Yes/No, General), always-visible Yes / No / Wait, favourites (long-press), custom phrases, full-screen "show" mode, copy |
| **SOS Emergency** | GPS location → emergency message → SMS · Call · Share (+ cloud incident log) | Press-and-hold (3 s) plus a 5 s cancellable countdown, readiness checklist, real GPS (lat, lng, accuracy, timestamp, Maps link), automatic SMS **confirmed by Android**, call contact, call local emergency number, share location, loud alarm, "I'm safe" resolve |
| **Dashboard** | System status → services → quick actions → recent activity → service availability → emergency access | One hub showing what is ready, what needs attention (tap to fix), and recent usage |
| **Activity** | Local history | Grouped by day, filter by module, weekly summary, clear history. Stores summaries only |
| **Settings** | — | Text size, theme (System/Light/Dark), high contrast, reduce motion, voice feedback, haptics, speech rate and pitch, voice test, speak-on-tap, auto-read, emergency contact (manual or contact picker), emergency message, automatic SMS, cloud logging, permission status, cloud connection test, replay onboarding |
| **Onboarding** | 6 steps | Explains each module and requests camera, microphone and location in context, with a reason for each |

## 3. Architecture

```
┌──────────────────────────── Android phone ─────────────────────────────┐
│  UI (Activities / Fragments, ViewBinding, Material 3)                   │
│    MainActivity (splash + bottom nav) ─ Home │ Activity │ Settings      │
│    VisionActivity · HearingActivity · CommunicationActivity · SOS       │
│                 │                                                       │
│  ViewModels (StateFlow)  HomeVM · VisionVM · HearingVM · EmergencyVM    │
│                 │                                                       │
│  Repositories (AppContainer – manual DI)                                │
│    SettingsRepository  ActivityRepository  PhraseRepository            │
│    SpeechOutput (TTS)  LocationRepository  ServiceStatusRepository      │
│    NetworkMonitor      BackendRepository ── ApiClient (HTTPS/JSON)      │
│                 │                       on-device engines:               │
│                 │                       ML Kit OCR · SpeechRecognizer    │
│                 │                       TextToSpeech · Fused Location    │
└─────────────────┼───────────────────────────────────────────────────────┘
                  │ HTTPS (optional – app works offline)
┌─────────────────▼──────────────── Render ──────────────────────────────┐
│  SenseConnect backend (Node.js + Express)                               │
│  /health · /api/v1/config · /api/v1/incidents · /api/v1/stats           │
│  In-memory incident store, 24 h retention, rate limiting                │
└────────────────────────────────────────────────────────────────────────┘
```

Render hosts **only the backend API**. The Android app runs on the phone and is installed as an APK.

### Code layout

```
app/src/main/java/com/example/senseconnect/
  SenseConnectApp.kt            Application, owns AppContainer
  core/
    AppContainer.kt             manual dependency container
    settings/                   AppSettings + SharedPreferences repository (StateFlow)
    activitylog/                privacy-preserving local history
    speech/SpeechOutput.kt      shared TTS engine (rate/pitch from settings)
    location/                   fused location, permission/GPS/timeout handling
    network/                    ApiClient, BackendRepository, NetworkMonitor
    status/                     real-time availability of every subsystem
    emergency/                  message builder, SMS sender (with confirmation), alarm
    ui/                         BaseActivity, status pills, insets, haptics, permissions
  ui/
    main/ home/ history/ settings/ onboarding/
    vision/ hearing/ communication/ emergency/
backend/                        Node.js + Express API (deployed to Render)
render.yaml                     Render Blueprint
```

### Design system

- **Colour roles** (`res/values/colors.xml` and `values-night/colors.xml`): indigo primary, teal secondary, blue tertiary, and semantic success / warning / emergency / neutral colours. Every text/background pair meets WCAG AA. Dark mode uses tuned colours rather than inverted ones, and a separate **high-contrast overlay** is applied at runtime.
- **Module identity**: Vision = indigo, Hearing = teal, Communication = blue, Emergency = red, used consistently across the dashboard, history and onboarding.
- **Typography** (`res/values/styles.xml`): display, screen title, section header, title, body, supporting, caption and live-caption styles, all in `sp`.
- **Icons**: Material Symbols (Rounded) vector drawables. No emoji.
- **Branding**: a custom mark (one central device connected to three senses), adaptive launcher icon with a monochrome layer, and an Android 12+ splash screen.

## 4. Accessibility

- TalkBack: content descriptions, headings, grouped cards announced as one element, live regions for changing status, switch semantics on settings rows, custom accessibility actions (favourite / delete phrase).
- The SOS hold gesture has an accessible alternative: a TalkBack double-tap opens an explicit confirmation dialog.
- Touch targets are at least 48 dp (primary actions 56–64 dp).
- In-app text size (Default / Large / Extra large) is applied **on top of** the Android font scale.
- High contrast, dark mode, reduce motion (disables scan-line and level animations; system animator scale is honoured too), and optional haptics and voice feedback.
- State is never conveyed by colour alone: every status pill shows text and an indicator.

## 5. Privacy and offline-first

| Capability | Where it runs | Needs internet? |
|---|---|---|
| OCR | ML Kit **bundled** model on the phone | No |
| Text-to-speech | Android TTS (on-device voices) | No |
| Speech recognition | Android `SpeechRecognizer`; requests the on-device engine when offline | Offline if the language pack is installed |
| Phrases, favourites, history, settings | SharedPreferences on the phone | No |
| GPS, SMS, dialer | Phone hardware | No (SMS needs mobile signal) |
| Health / config / incident log | Render backend | Yes (optional) |

- Images and audio are processed in memory and **never stored**.
- History stores summaries only (e.g. "26 words · 5 lines recognised"), never scanned text or transcripts. Typed free-text messages are logged as a word count only.
- History is excluded from Android cloud backup.
- Server-side incidents expire after 24 hours, and the device ID is a random installation UUID, not a hardware identifier.

## 6. Backend API (Render)

Base URL: configured at build time (see §8). All responses are JSON.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | `{"status":"ok","service":"SenseConnect","version","uptimeSeconds","timestamp"}`, used by the app and by Render's health check |
| GET | `/api/v1/config` | Emergency numbers by country, default number (112), optional announcement shown on the dashboard |
| POST | `/api/v1/incidents` | Log an SOS: `{deviceId (UUID), triggeredAt (ms), location?{lat,lng,accuracy}, contactConfigured, appVersion}` → `201 {id, status:"active", receivedAt, …}` |
| GET | `/api/v1/incidents/:id?deviceId=…` | Read your own incident |
| POST | `/api/v1/incidents/:id/resolve` | Body `{deviceId}`: mark the incident resolved ("I'm safe") |
| GET | `/api/v1/stats` | Aggregate, non-identifying counts |

Hardening: input validation, 16 KB body limit, per-IP rate limit (10 incident calls per minute), `no-store` caching, no `x-powered-by` header, and owner-only incident access.

Run locally:

```bash
cd backend
npm install
npm test        # 7 tests (node:test)
npm start       # http://localhost:3000/health
```

## 7. Build the app

Requirements: Android Studio (bundled JBR 21), Android SDK 36. The build configuration is pinned and must not be changed: AGP 9.1.0, Gradle 9.4.1, Java 21, configuration cache off.

```bash
./gradlew clean assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest            # unit tests
```

## 8. Backend URL configuration

`app/build.gradle.kts` reads the Gradle property `senseconnect.apiBaseUrl` into `BuildConfig.API_BASE_URL`. The default is the Render URL. To override it:

```bash
# Production (Render):
./gradlew assembleDebug -Psenseconnect.apiBaseUrl=https://<your-service>.onrender.com/
# Local backend from the emulator:
./gradlew assembleDebug -Psenseconnect.apiBaseUrl=http://10.0.2.2:3000/
```

Cleartext HTTP is blocked everywhere except `10.0.2.2` and `localhost` (`res/xml/network_security_config.xml`).

## 9. Deploy the backend to Render

1. Push this repository to GitHub.
2. In Render, choose **New → Blueprint** and select the repository. `render.yaml` creates the `senseconnect-api` web service (root directory `backend`, `npm ci`, `npm start`, health check `/health`).
3. Wait for the deploy, then verify:
   `curl https://<service>.onrender.com/health` → `{"status":"ok","service":"SenseConnect",…}`
4. Rebuild the APK with that URL (§8).

The free plan sleeps after inactivity. The first request can take 30–60 s, so the app uses long timeouts and shows "Checking" until the server responds.

## 10. Install on an Android phone

1. On the phone, open **Settings → About phone** and tap **Build number** seven times to enable Developer options.
2. Open **Settings → System → Developer options** and enable **USB debugging**.
3. Connect the phone by USB and accept the "Allow USB debugging?" prompt.
4. From the project folder, run:
   ```bash
   adb devices
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Alternatively, press **Run** in Android Studio with the phone selected, or copy the APK to the phone and open it (allow "Install unknown apps").
5. Launch **SenseConnect**, complete onboarding and allow the permissions.

## 11. Recommended demonstration flow

1. **Splash → onboarding** (the 5 modules and contextual permissions).
2. **Dashboard**: system status, "Cloud online", the three service cards with their pipelines, quick actions, and service availability.
3. **Vision**: point the camera at printed text (green frame = text detected), tap **Scan text**, the text is read aloud. Show **Copy**.
4. **Hearing**: **Start listening**, speak, watch the live caption and transcript build. Type a reply and press speak.
5. **Communication**: tap **I need a doctor.**, it is spoken. Show the full-screen display and Yes / No.
6. **SOS**: show the readiness checklist and live GPS, press and hold, the countdown, **SOS ACTIVATED** with SMS "confirmed by Android" and the cloud incident ID. Show Share and Sound alarm, then **I'm safe**.
7. **Activity**: the history of everything just demonstrated, with no sensitive content stored.
8. **Settings**: switch Dark / Extra-large text / High contrast live, and run **Test voice** and the **cloud connection test**.

## 12. Testing summary

- **Android unit tests**: emergency message building, locale-independent Maps links, emergency-number resolution, and time formatting (11 tests).
- **Backend tests**: health, config, incident lifecycle and ownership, validation, 404s, and expiry (7 tests).
- **Emulator end-to-end tests**: onboarding permissions; dashboard live status; OCR on a real printed-text image (26/26 words); camera capture with no text giving the "No text found" state; Communication TTS through Google's on-device voice; SOS hold → countdown → activation with real location, an SMS present in Android's sent store, and the incident created and resolved on the backend; dark, extra-large and high-contrast modes.

## 13. Known limitations

- **Speech recognition** depends on the phone's recognition service (normally Google). Offline use needs the language's offline pack. The Android emulator's virtual microphone does not produce usable speech, so test captioning on a real phone.
- **Direct SMS** needs a SIM and the optional SMS permission. Otherwise SOS opens the messaging app pre-filled and the user presses Send. Android confirms that the message was *sent*, not that it was *delivered* to the recipient's phone.
- **Calls** open the dialer pre-filled; Android requires the user to press Call.
- **Backend storage is in-memory**: incidents are lost if the Render instance restarts (and expire after 24 h by design). Add Render Postgres for durable storage.
- Render's free plan sleeps when idle, so the first request is slow.
- The debug APK is about 60 MB because it includes the bundled OCR model for every CPU architecture. A release App Bundle would be much smaller per device.
- English UI only.

## 14. Future enhancements

- Wearable / Bluetooth companion device (haptic alerts for deaf users).
- Object and currency recognition in Vision; document layout reading.
- Sound-event alerts (doorbell, alarm, baby crying) for deaf users.
- Multi-language UI, OCR scripts and phrase boards.
- Durable incident storage with an authenticated caregiver dashboard.
- Background SOS trigger (power-button / shake) via a foreground service.
