import Foundation

/// Build-time configuration. The production backend is the same Render service the Android app uses.
enum AppConfig {
    /// Production SenseConnect backend (Render). HTTPS only.
    static let productionAPIBaseURL = URL(string: "https://senseconnect-api.onrender.com/")!

    /// Debug builds may point at another HTTPS backend by setting the scheme environment
    /// variable `SENSECONNECT_DEV_API_URL`. Release builds always use production.
    static var apiBaseURL: URL {
        #if DEBUG
        if let value = ProcessInfo.processInfo.environment["SENSECONNECT_DEV_API_URL"],
           let url = URL(string: value), url.scheme == "https" {
            return url
        }
        #endif
        return productionAPIBaseURL
    }

    static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "2.0.0"
    }

    static var build: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "2"
    }
}
