import AVFoundation
import CoreLocation
import Speech
import UIKit

enum ServiceID: Hashable { case camera, microphone, speech, location, cloud }

enum FixAction { case requestCamera, requestMicrophone, requestLocation, openSettings, retryCloud }

struct ServiceStatus: Identifiable {
    let id: ServiceID
    let label: String
    let detail: String
    let stateLabel: String
    let tone: Tone
    let symbol: String
    var fix: FixAction? = nil
}

/// Runtime permissions, requested in context.
@MainActor
enum Permissions {
    static var cameraStatus: AVAuthorizationStatus { AVCaptureDevice.authorizationStatus(for: .video) }
    static var microphoneGranted: Bool { AVAudioApplication.shared.recordPermission == .granted }
    static var speechStatus: SFSpeechRecognizerAuthorizationStatus { SFSpeechRecognizer.authorizationStatus() }

    static func requestCamera() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .video)
    }

    /// Microphone + speech recognition (both are needed for live captions).
    static func requestMicrophoneAndSpeech() async -> Bool {
        let mic = await AVAudioApplication.requestRecordPermission()
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
        }
        return mic && speech
    }

    static func openAppSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// Real availability of every subsystem, shown on the dashboard. Nothing here is hard-coded.
@MainActor
enum ServiceStatusBuilder {
    static func snapshot(app: AppModel) -> [ServiceStatus] {
        [camera(), microphone(), speech(app), location(app), cloud(app)]
    }

    static func camera() -> ServiceStatus {
        let base = (label: "Camera & OCR", symbol: "camera.fill")
        guard AVCaptureDevice.default(for: .video) != nil else {
            return ServiceStatus(id: .camera, label: base.label, detail: "No camera available", stateLabel: "Unavailable", tone: .neutral, symbol: base.symbol)
        }
        switch Permissions.cameraStatus {
        case .authorized:
            return ServiceStatus(id: .camera, label: base.label, detail: "On-device text recognition", stateLabel: "Ready", tone: .success, symbol: base.symbol)
        case .notDetermined:
            return ServiceStatus(id: .camera, label: base.label, detail: "Tap to allow camera access", stateLabel: "Permission needed", tone: .warning, symbol: base.symbol, fix: .requestCamera)
        default:
            return ServiceStatus(id: .camera, label: base.label, detail: "Allow camera in iOS Settings", stateLabel: "Blocked", tone: .warning, symbol: base.symbol, fix: .openSettings)
        }
    }

    static func microphone() -> ServiceStatus {
        let label = "Microphone & transcription", symbol = "mic.fill"
        guard SFSpeechRecognizer() != nil else {
            return ServiceStatus(id: .microphone, label: label, detail: "Speech recognition isn't available for this language", stateLabel: "Unavailable", tone: .neutral, symbol: symbol)
        }
        let micUndecided = AVAudioApplication.shared.recordPermission == .undetermined
        let speechUndecided = Permissions.speechStatus == .notDetermined
        if Permissions.microphoneGranted && Permissions.speechStatus == .authorized {
            return ServiceStatus(id: .microphone, label: label, detail: "Live speech-to-text", stateLabel: "Ready", tone: .success, symbol: symbol)
        }
        if micUndecided || speechUndecided {
            return ServiceStatus(id: .microphone, label: label, detail: "Tap to allow microphone & speech", stateLabel: "Permission needed", tone: .warning, symbol: symbol, fix: .requestMicrophone)
        }
        return ServiceStatus(id: .microphone, label: label, detail: "Allow microphone & speech in iOS Settings", stateLabel: "Blocked", tone: .warning, symbol: symbol, fix: .openSettings)
    }

    static func speech(_ app: AppModel) -> ServiceStatus {
        app.speech.isAvailable
            ? ServiceStatus(id: .speech, label: "Text-to-speech", detail: "Voice: \(app.speech.voiceDescription)", stateLabel: "Ready", tone: .success, symbol: "speaker.wave.2.fill")
            : ServiceStatus(id: .speech, label: "Text-to-speech", detail: "No voices installed", stateLabel: "Unavailable", tone: .danger, symbol: "speaker.wave.2.fill")
    }

    static func location(_ app: AppModel) -> ServiceStatus {
        let label = "Location (GPS)", symbol = "location.fill"
        switch app.location.authorization {
        case .authorizedWhenInUse, .authorizedAlways:
            return ServiceStatus(id: .location, label: label, detail: "Used to share position in emergencies", stateLabel: "Ready", tone: .success, symbol: symbol)
        case .notDetermined:
            return ServiceStatus(id: .location, label: label, detail: "Tap to allow location for SOS", stateLabel: "Permission needed", tone: .warning, symbol: symbol, fix: .requestLocation)
        default:
            return ServiceStatus(id: .location, label: label, detail: "Allow location in iOS Settings", stateLabel: "Blocked", tone: .warning, symbol: symbol, fix: .openSettings)
        }
    }

    static func cloud(_ app: AppModel) -> ServiceStatus {
        let label = "SenseConnect Cloud"
        if !app.network.isOnline {
            return ServiceStatus(id: .cloud, label: label, detail: "Offline - assistive features still work", stateLabel: "Offline", tone: .neutral, symbol: "icloud.slash", fix: .retryCloud)
        }
        switch app.backend.status {
        case .online(let ms, _):
            return ServiceStatus(id: .cloud, label: label, detail: "Connected over HTTPS · \(ms) ms", stateLabel: "Online", tone: .success, symbol: "checkmark.icloud.fill")
        case .unknown, .checking:
            return ServiceStatus(id: .cloud, label: label, detail: "Contacting server", stateLabel: "Connecting", tone: .info, symbol: "icloud")
        case .waking:
            return ServiceStatus(id: .cloud, label: label, detail: "Server is waking up - this can take up to a minute", stateLabel: "Waking", tone: .info, symbol: "icloud")
        case .offline(let reason):
            return ServiceStatus(id: .cloud, label: label, detail: "\(reason). Tap to retry", stateLabel: "Offline", tone: .warning, symbol: "icloud.slash", fix: .retryCloud)
        }
    }
}
