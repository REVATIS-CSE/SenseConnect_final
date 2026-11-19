import Foundation
import Network
import Observation

/// Tracks internet connectivity with NWPathMonitor.
@MainActor @Observable
final class NetworkMonitor {
    private(set) var isOnline = true
    @ObservationIgnored var onBecameOnline: (() -> Void)?
    @ObservationIgnored private let monitor = NWPathMonitor()

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let wasOnline = self.isOnline
                self.isOnline = online
                if online && !wasOnline { self.onBecameOnline?() }
            }
        }
        monitor.start(queue: DispatchQueue(label: "senseconnect.network"))
    }
}

struct APIError: LocalizedError {
    let statusCode: Int?
    let message: String
    var errorDescription: String? { message }
}

// MARK: - Backend DTOs

struct HealthResponse: Decodable {
    let status: String
    let service: String
    let version: String?
}

struct ConfigResponse: Codable {
    let emergencyNumbers: [String: String]
    let defaultEmergencyNumber: String
    let announcement: String?
}

struct IncidentLocation: Encodable {
    let lat: Double
    let lng: Double
    let accuracy: Double?
}

struct IncidentRequest: Encodable {
    let deviceId: String
    let triggeredAt: Int64
    let location: IncidentLocation?
    let contactConfigured: Bool
    let appVersion: String
}

struct IncidentResponse: Decodable {
    let id: String
    let status: String
    let receivedAt: String?
}

struct ResolveRequest: Encodable {
    let deviceId: String
}

/// Minimal JSON-over-HTTPS client for the SenseConnect backend (URLSession, async/await).
final class APIClient: Sendable {
    let baseURL: URL
    private let session: URLSession

    init(baseURL: URL) {
        self.baseURL = baseURL
        let config = URLSessionConfiguration.default
        // A sleeping Render free instance can take 30-60 s to answer its first request.
        config.timeoutIntervalForRequest = 60
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: config)
    }

    func get<T: Decodable>(_ path: String, as type: T.Type) async throws -> T {
        try await send(URLRequest(url: baseURL.appendingPathComponent(path)), as: type)
    }

    func post<Body: Encodable, T: Decodable>(_ path: String, body: Body, as type: T.Type) async throws -> T {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        return try await send(request, as: type)
    }

    private func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
        var request = request
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("SenseConnect-iOS", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError(statusCode: nil, message: "No HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let serverMessage = (try? JSONDecoder().decode([String: String].self, from: data))?["error"]
            throw APIError(statusCode: http.statusCode, message: serverMessage ?? "Server returned HTTP \(http.statusCode)")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
