# SenseConnect for iPhone

Native **SwiftUI** version of SenseConnect (iOS 17+). It has the same four modules, dashboard, design system and **Render backend** (`https://senseconnect-api.onrender.com/`) as the Android app.

> **Status:** the source is complete but has **not been compiled yet**. iPhone apps can only be built with Xcode on a Mac; the project was written on Windows. Expect to fix any small compiler errors on the first build in Xcode.

## How each feature is implemented on iOS

| Module | Android | iPhone (this project) |
|---|---|---|
| Vision OCR | ML Kit (bundled) | Apple **Vision** `VNRecognizeTextRequest` (on-device, offline) |
| Camera | CameraX | **AVFoundation** (`AVCaptureSession`, photo + live frames) |
| Live captions | `SpeechRecognizer` | **SFSpeechRecognizer** + `AVAudioEngine`, on-device when the iPhone supports it |
| Text-to-speech | Android TTS | **AVSpeechSynthesizer** |
| Location | Fused Location | **CoreLocation** |
| SOS SMS | Direct send *or* composer | **Messages composer**, pre-filled; the user taps **Send** (iOS never lets apps send SMS silently) |
| Calls | Dialer | `tel://` (iOS asks the user to confirm) |
| Backend | `ApiClient` + `BackendRepository` | `APIClient` + `BackendService` (URLSession, HTTPS only) |
| History | SharedPreferences | JSON in Application Support, **excluded from iCloud backup** |

## Project layout

```
ios/
  project.yml                 XcodeGen project definition
  SenseConnect/
    App/                      entry point, AppModel (shared services), AppConfig (backend URL)
    Core/                     theme, settings, history, speech, location, network, backend, alarm
    Components/               cards, status pills, pipelines, brand mark, Messages / Contacts pickers
    Features/                 Home, Activity, Settings, Onboarding, Vision, Hearing, Communication, SOS
    Resources/Assets.xcassets app icon, accent and launch colours
    Info.plist                permission texts (camera, microphone, speech, location)
  SenseConnectTests/          unit tests (emergency message, config, time text)
```

## Build and install on your iPhone

**You need:**
- A Mac with **Xcode 15 or later**.
- An Apple ID. A free one works for installing on your own iPhone.
- A USB cable for the first install.

1. **Generate the Xcode project:**
   ```bash
   brew install xcodegen
   cd ios
   xcodegen generate
   open SenseConnect.xcodeproj
   ```
   *(No Homebrew? Create a new **iOS App** project in Xcode named `SenseConnect` (SwiftUI, Swift), delete its generated `ContentView.swift`/`…App.swift`, drag in the `SenseConnect/` folders, and set **Info.plist File** to `SenseConnect/Info.plist`.)*
2. **Set up signing.** Select the **SenseConnect** target → **Signing & Capabilities**:
   - **Team:** choose your Apple ID (Xcode → Settings → Accounts → add it if missing).
   - **Bundle Identifier:** change `com.example.senseconnect` to something unique, e.g. `com.yourname.senseconnect`. Free accounts can't use `com.example`.
3. **Connect the iPhone** with a cable, unlock it, and tap **Trust This Computer**.
   - On iOS 16 or later, turn on **Settings → Privacy & Security → Developer Mode**. The iPhone restarts.
4. In Xcode, choose your iPhone as the run destination and press **Run (⌘R)**.
5. **The first time only**, on the iPhone go to **Settings → General → VPN & Device Management**, tap your Apple ID, then **Trust**.
6. Open **SenseConnect**, go through onboarding, and allow the permissions.

With a **free** Apple ID the app stops opening after **7 days**; press **Run** again to reinstall. A paid Apple Developer account ($99/year) gives 1-year installs and **TestFlight**, which lets you install the app on any iPhone from a link with no cable.

**Run the tests:** in Xcode press **⌘U**, or:
```bash
xcodebuild test -scheme SenseConnect -destination 'platform=iOS Simulator,name=iPhone 15'
```
The camera, SMS and real GPS don't work in the Simulator, so test those on a real iPhone.

## Backend configuration

- Production: `AppConfig.productionAPIBaseURL = https://senseconnect-api.onrender.com/`. Release builds always use it.
- Debug builds can point at a different **HTTPS** backend with the scheme environment variable `SENSECONNECT_DEV_API_URL`.
- `Info.plist` declares **no App Transport Security exceptions**, so the app can only use HTTPS.
- Cloud status works the same as on Android: *Connecting… → Waking server… → Cloud Online / Cloud Offline (with reason)*. The app retries automatically when the iPhone reconnects to the internet.

## iOS-specific limitations

- **SMS cannot be sent automatically.** SOS opens Messages pre-filled with the location and the user taps Send. The result reported to the app ("sent") means iOS queued or sent the message.
- **Live captions** use Apple's speech recognition. On-device recognition depends on the language and iPhone model; otherwise it needs internet. Apple limits each recognition session to about a minute, so the app rotates sessions automatically.
- **Emergency calls** always show the iOS call confirmation.
- The app needs **iOS 17 or later**.
