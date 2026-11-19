import Foundation
import Observation
import MessageUI

/// Delivery state of each SOS channel. Only marked successful after real confirmation.
enum Delivery: Equatable {
    case idle
    case inProgress
    case done(String)
    case failed(String)
    case actionNeeded(String)
}

enum LocationState: Equatable {
    case loading
    case available(LocationFix)
    case permissionDenied
    case disabled
    case unavailable(String)

    var fix: LocationFix? {
        if case .available(let fix) = self { return fix }
        return nil
    }
}

/// SOS workflow: Location → Emergency message → SMS / Call / Share, plus the cloud incident log.
/// Activation is two-step (press-and-hold, then a cancellable countdown).
@MainActor @Observable
final class SOSModel {
    enum Phase: Equatable { case ready, countdown(Int), active }

    private(set) var phase: Phase = .ready
    private(set) var location: LocationState = .loading
    private(set) var activatedAt: Date?
    var sms: Delivery = .idle
    private(set) var cloud: Delivery = .idle
    private(set) var alarmOn = false
    /// Set when the Messages composer should be presented.
    var showComposer = false

    @ObservationIgnored private let app: AppModel
    @ObservationIgnored private let alarm = AlarmPlayer()
    @ObservationIgnored private var countdownTask: Task<Void, Never>?
    @ObservationIgnored private var incidentID: String?

    static let countdownSeconds = 5

    init(app: AppModel) {
        self.app = app
    }

    var settings: SettingsStore { app.settings }
    var canSendText: Bool { MFMessageComposeViewController.canSendText() }

    var message: String {
        EmergencyMessage.build(template: settings.emergencyMessage, location: location.fix, date: activatedAt ?? Date())
    }

    var emergencyNumber: String {
        app.backend.config.emergencyNumber(for: Locale.current.region?.identifier)
    }

    func refreshLocation(timeout: TimeInterval = 15) async {
        location = .loading
        location = Self.state(from: await app.location.currentLocation(timeout: timeout))
    }

    private static func state(from result: LocationResult) -> LocationState {
        switch result {
        case .success(let fix): .available(fix)
        case .permissionDenied: .permissionDenied
        case .servicesDisabled: .disabled
        case .unavailable(let reason): .unavailable(reason)
        }
    }

    func startCountdown() {
        guard phase == .ready else { return }
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            for remaining in stride(from: Self.countdownSeconds, through: 1, by: -1) {
                guard let self, !Task.isCancelled else { return }
                self.phase = .countdown(remaining)
                Haptics.heavy()
                try? await Task.sleep(for: .seconds(1))
            }
            guard let self, !Task.isCancelled else { return }
            await self.activate()
        }
    }

    func cancelCountdown() {
        countdownTask?.cancel()
        phase = .ready
        app.speech.announce("Emergency alert cancelled.")
    }

    /// Activates immediately (after an explicit VoiceOver confirmation).
    func activateNow() {
        countdownTask?.cancel()
        Task { await activate() }
    }

    private func activate() async {
        let now = Date()
        phase = .active
        activatedAt = now
        sms = .inProgress
        cloud = .inProgress
        Haptics.warning()
        app.speech.announce("Emergency alert activated.")

        // 1. Always try for a fresh fix at activation time, keeping any earlier fix as fallback.
        let previous = location
        let fresh = Self.state(from: await app.location.currentLocation(timeout: 10))
        location = (fresh.fix == nil && previous.fix != nil) ? previous : fresh

        // 2. SMS: iOS never lets apps send SMS silently, so the Messages composer opens
        //    pre-filled and the user taps Send. Delivery is reported from the composer result.
        if !settings.hasEmergencyContact {
            sms = .actionNeeded("No emergency contact configured")
        } else if canSendText {
            showComposer = true
        } else {
            sms = .failed("This device cannot send text messages")
        }

        // 3. Cloud incident log (optional; never blocks the local workflow).
        if !settings.cloudLogging {
            cloud = .actionNeeded("Cloud logging is turned off in Settings")
        } else if !app.network.isOnline {
            cloud = .failed("Offline - incident not logged")
        } else {
            do {
                let receipt = try await app.backend.reportIncident(
                    deviceID: settings.installationID,
                    triggeredAt: now,
                    location: location.fix,
                    contactConfigured: settings.hasEmergencyContact
                )
                incidentID = receipt.id
                cloud = .done("Incident #\(receipt.id.prefix(8)) logged")
            } catch {
                cloud = .failed(error.localizedDescription)
            }
        }

        let parts = [location.fix != nil ? "Location attached" : "No location"]
        app.activity.log(.sos, "SOS activated", parts.joined(separator: " · "))
    }

    /// Result from MFMessageComposeViewController.
    func composerFinished(_ result: MessageComposeResult) {
        showComposer = false
        switch result {
        case .sent:
            // Apple: "the user successfully queued or sent the message".
            sms = .done("Sent via Messages to \(settings.emergencyContactDisplayName)")
            app.activity.log(.sos, "SOS message sent", "Via Messages")
        case .cancelled:
            sms = .actionNeeded("Message not sent - tap Send SMS to try again")
        case .failed:
            sms = .failed("Messages could not send the SMS")
        @unknown default:
            sms = .actionNeeded("Message status unknown")
        }
    }

    func openComposer() {
        guard settings.hasEmergencyContact else { return }
        if canSendText { showComposer = true } else { sms = .failed("This device cannot send text messages") }
    }

    func recordManualAction(_ action: String) {
        app.activity.log(.sos, action, "Opened by user during SOS")
    }

    func toggleAlarm() {
        if alarm.isPlaying { alarm.stop() } else { alarm.start() }
        alarmOn = alarm.isPlaying
    }

    func endSOS() {
        alarm.stop()
        alarmOn = false
        if let id = incidentID {
            let deviceID = settings.installationID
            Task { await app.backend.resolveIncident(id: id, deviceID: deviceID) }
        }
        incidentID = nil
        app.activity.log(.sos, "SOS ended", "Marked safe by user")
        phase = .ready
        activatedAt = nil
        sms = .idle
        cloud = .idle
    }

    func shutdown() {
        countdownTask?.cancel()
        alarm.stop()
        alarmOn = false
    }
}
