import CoreLocation
import Observation

/// A real GPS fix. Coordinates are never hard-coded or simulated.
struct LocationFix: Equatable {
    let latitude: Double
    let longitude: Double
    let accuracy: Double?
    let timestamp: Date
    /// False when no fresh fix arrived in time and the last known location was used.
    let isFresh: Bool

    var mapsURL: String {
        String(format: "https://maps.google.com/?q=%.6f,%.6f", locale: Locale(identifier: "en_US_POSIX"), latitude, longitude)
    }
}

enum LocationResult: Equatable {
    case success(LocationFix)
    case permissionDenied
    case servicesDisabled
    case unavailable(String)
}

/// CoreLocation wrapper: permission, one-shot fix with timeout and last-known fallback.
@MainActor @Observable
final class LocationService {
    private(set) var authorization: CLAuthorizationStatus = .notDetermined

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private let delegate = LocationDelegate()
    @ObservationIgnored private var fixContinuation: CheckedContinuation<CLLocation?, Never>?
    @ObservationIgnored private var authContinuation: CheckedContinuation<Void, Never>?
    @ObservationIgnored private var fixToken = 0

    init() {
        authorization = manager.authorizationStatus
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.delegate = delegate
        delegate.onAuthorization = { [weak self] status in
            Task { @MainActor in
                guard let self else { return }
                self.authorization = status
                if status != .notDetermined {
                    self.authContinuation?.resume()
                    self.authContinuation = nil
                }
            }
        }
        delegate.onLocation = { [weak self] location in
            Task { @MainActor in self?.finishFix(location) }
        }
    }

    var isAuthorized: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }

    /// Shows the iOS permission prompt if the user has not decided yet.
    func requestPermission() async {
        guard authorization == .notDetermined else { return }
        await withCheckedContinuation { continuation in
            authContinuation = continuation
            manager.requestWhenInUseAuthorization()
        }
    }

    func currentLocation(timeout: TimeInterval = 15) async -> LocationResult {
        if authorization == .notDetermined { await requestPermission() }
        guard isAuthorized else { return .permissionDenied }
        // locationServicesEnabled() can block, so it is queried off the main thread.
        let enabled = await Task.detached { CLLocationManager.locationServicesEnabled() }.value
        guard enabled else { return .servicesDisabled }

        finishFix(nil) // never leave an earlier request's continuation hanging
        fixToken += 1
        let token = fixToken
        let fresh: CLLocation? = await withCheckedContinuation { continuation in
            fixContinuation = continuation
            manager.requestLocation()
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                if let self, self.fixToken == token { self.finishFix(nil) }
            }
        }
        if let fresh { return .success(Self.fix(from: fresh, isFresh: true)) }
        if let last = manager.location { return .success(Self.fix(from: last, isFresh: false)) }
        return .unavailable("No GPS signal yet. Move near a window or outdoors and try again.")
    }

    private func finishFix(_ location: CLLocation?) {
        fixContinuation?.resume(returning: location)
        fixContinuation = nil
    }

    private static func fix(from location: CLLocation, isFresh: Bool) -> LocationFix {
        LocationFix(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            accuracy: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
            timestamp: location.timestamp,
            isFresh: isFresh
        )
    }
}

private final class LocationDelegate: NSObject, CLLocationManagerDelegate {
    var onAuthorization: ((CLAuthorizationStatus) -> Void)?
    var onLocation: ((CLLocation?) -> Void)?

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        onAuthorization?(manager.authorizationStatus)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        onLocation?(locations.last)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // kCLErrorLocationUnknown is transient; the timeout + last-known fallback handles it.
        if (error as? CLError)?.code != .locationUnknown { onLocation?(nil) }
    }
}
