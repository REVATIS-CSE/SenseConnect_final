import Foundation
import Observation

/// Shared dependency container: every screen uses the same settings, history, speech engine,
/// location service and backend client.
@MainActor @Observable
final class AppModel {
    let settings: SettingsStore
    let activity: ActivityLog
    let phrases: PhraseStore
    let network: NetworkMonitor
    let location: LocationService
    let speech: SpeechService
    let backend: BackendService

    @ObservationIgnored private var started = false

    init() {
        let settings = SettingsStore()
        let network = NetworkMonitor()
        self.settings = settings
        self.network = network
        activity = ActivityLog()
        phrases = PhraseStore()
        location = LocationService()
        speech = SpeechService(settings: settings)
        backend = BackendService(client: APIClient(baseURL: AppConfig.apiBaseURL), network: network)
    }

    func start() async {
        guard !started else { return }
        started = true
        // Re-check the cloud automatically whenever the phone regains internet.
        network.onBecameOnline = { [weak self] in
            Task { await self?.refreshCloud() }
        }
        network.start()
        await refreshCloud()
    }

    func refreshCloud() async {
        await backend.refreshHealth()
        await backend.refreshConfig()
    }
}

/// Destinations reachable from the dashboard.
enum Route: Hashable {
    case vision
    case hearing(autoStart: Bool)
    case communication
    case sos
}
