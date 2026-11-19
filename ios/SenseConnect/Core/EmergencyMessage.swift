import Foundation

/// Builds the SOS text used by SMS, the share sheet and the on-screen preview.
/// Identical format to the Android app.
enum EmergencyMessage {
    static func build(template: String, location: LocationFix?, date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateFormat = "d MMM yyyy, HH:mm"
        let time = formatter.string(from: date)

        let trimmed = template.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = trimmed.isEmpty ? "EMERGENCY: I need help." : trimmed

        let locationLine: String
        if let location {
            let accuracy = location.accuracy.map { " (accuracy ±\(Int($0.rounded())) m)" } ?? ""
            let stale = location.isFresh ? "" : " [last known location]"
            locationLine = "My location: \(location.mapsURL)\(accuracy)\(stale)"
        } else {
            locationLine = "My location could not be determined."
        }
        return "\(base)\n\(locationLine)\nTime: \(time)"
    }
}
