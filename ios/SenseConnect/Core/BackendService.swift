import Foundation
import Observation

enum BackendStatus: Equatable {
    case unknown
    case checking
    /// Health check is slow, typically a Render free instance waking from sleep.
    case waking
    case online(latencyMs: Int, version: String)
    case offline(String)
}

/// Configuration served by the backend, with built-in defaults for offline use.
struct RemoteConfig: Equatable {
    var emergencyNumbers: [String: String]
    var defaultEmergencyNumber: String
    var announcement: String?

    static let fallback = RemoteConfig(
        emergencyNumbers: ["IN": "112", "US": "911", "CA": "911", "GB": "999", "AU": "000"],
        defaultEmergencyNumber: "112",
        announcement: nil
    )

    func emergencyNumber(for region: String?) -> String {
        region.flatMap { emergencyNumbers[$0.uppercased()] } ?? defaultEmergencyNumber
    }
}

struct IncidentReceipt: Equatable {
    let id: String
}

/// Single entry point for the Render backend (View → Model → BackendService → APIClient).
/// Core assistive features never depend on it; it adds health monitoring, remote
/// configuration and an emergency incident log.
@MainActor @Observable
final class BackendService {
    private(set) var status: BackendStatus = .unknown
    private(set) var config: RemoteConfig

    @ObservationIgnored private let client: APIClient
    @ObservationIgnored private let network: NetworkMonitor
    @ObservationIgnored private let defaults = UserDefaults.standard

    init(client: APIClient, network: NetworkMonitor) {
        self.client = client
        self.network = network
        if let data = UserDefaults.standard.data(forKey: "remoteConfig"),
           let cached = try? JSONDecoder().decode(ConfigResponse.self, from: data) {
            config = RemoteConfig(emergencyNumbers: cached.emergencyNumbers, defaultEmergencyNumber: cached.defaultEmergencyNumber, announcement: cached.announcement)
        } else {
            config = .fallback
        }
    }

    var baseURLDescription: String { client.baseURL.absoluteString }

    /// GET {baseURL}/health
    @discardableResult
    func refreshHealth() async -> BackendStatus {
        guard network.isOnline else {
            status = .offline("No internet connection")
            return status
        }
        status = .checking
        let wakingHint = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(4))
            if let self, self.status == .checking { self.status = .waking }
        }
        let started = Date()
        do {
            let health = try await client.get("health", as: HealthResponse.self)
            if health.status == "ok" && health.service == "SenseConnect" {
                status = .online(latencyMs: Int(Date().timeIntervalSince(started) * 1000), version: health.version ?? "?")
            } else {
                status = .offline("Unexpected response from server")
            }
        } catch {
            status = .offline(Self.describe(error))
        }
        wakingHint.cancel()
        return status
    }

    func refreshConfig() async {
        guard network.isOnline,
              let response = try? await client.get("api/v1/config", as: ConfigResponse.self) else { return }
        if let data = try? JSONEncoder().encode(response) { defaults.set(data, forKey: "remoteConfig") }
        config = RemoteConfig(
            emergencyNumbers: response.emergencyNumbers,
            defaultEmergencyNumber: response.defaultEmergencyNumber,
            announcement: response.announcement?.isEmpty == false ? response.announcement : nil
        )
    }

    /// POST /api/v1/incidents. Returns a receipt only when the server confirms storage.
    func reportIncident(deviceID: String, triggeredAt: Date, location: LocationFix?, contactConfigured: Bool) async throws -> IncidentReceipt {
        let body = IncidentRequest(
            deviceId: deviceID,
            triggeredAt: Int64(triggeredAt.timeIntervalSince1970 * 1000),
            location: location.map { IncidentLocation(lat: $0.latitude, lng: $0.longitude, accuracy: $0.accuracy) },
            contactConfigured: contactConfigured,
            appVersion: AppConfig.version
        )
        do {
            let response = try await client.post("api/v1/incidents", body: body, as: IncidentResponse.self)
            return IncidentReceipt(id: response.id)
        } catch {
            throw APIError(statusCode: nil, message: Self.describe(error))
        }
    }

    /// POST /api/v1/incidents/{id}/resolve ("I'm safe").
    func resolveIncident(id: String, deviceID: String) async {
        _ = try? await client.post("api/v1/incidents/\(id)/resolve", body: ResolveRequest(deviceId: deviceID), as: IncidentResponse.self)
    }

    /// Maps network errors to messages a user can act on.
    static func describe(_ error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut: return "Server did not respond in time (it may still be waking up)"
            case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed: return "No internet connection"
            case .cannotFindHost, .dnsLookupFailed, .cannotConnectToHost: return "Cannot reach the server"
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
                 .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
                return "Secure (HTTPS) connection failed"
            default: return urlError.localizedDescription
            }
        }
        if let apiError = error as? APIError {
            if let code = apiError.statusCode, code >= 500 { return "Server error (HTTP \(code))" }
            return apiError.message
        }
        return error.localizedDescription
    }
}
