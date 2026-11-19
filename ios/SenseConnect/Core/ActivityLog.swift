import Foundation
import Observation

enum ActivityType: String, Codable, CaseIterable, Identifiable {
    case vision, hearing, communication, sos, location
    var id: Self { self }

    var label: String {
        switch self {
        case .vision: "Vision"
        case .hearing: "Hearing"
        case .communication: "Communication"
        case .sos: "Emergency"
        case .location: "Location"
        }
    }

    var style: ModuleStyle {
        switch self {
        case .vision: .vision
        case .hearing: .hearing
        case .communication: .communication
        case .sos: .sos
        case .location: .location
        }
    }
}

/// One history entry. Privacy: summaries only, never scanned text, transcripts, images or audio.
struct ActivityEvent: Codable, Identifiable, Equatable {
    let id: UUID
    let type: ActivityType
    let title: String
    let detail: String
    let date: Date
}

/// On-device activity history, stored in Application Support and excluded from iCloud backup.
@MainActor @Observable
final class ActivityLog {
    private(set) var events: [ActivityEvent] = []
    @ObservationIgnored private let fileURL: URL
    private static let maxEvents = 200

    init() {
        let folder = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        fileURL = folder.appendingPathComponent("activity.json")
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode([ActivityEvent].self, from: data) {
            events = decoded
        }
    }

    func log(_ type: ActivityType, _ title: String, _ detail: String) {
        events.insert(ActivityEvent(id: UUID(), type: type, title: title, detail: detail, date: Date()), at: 0)
        if events.count > Self.maxEvents { events.removeLast(events.count - Self.maxEvents) }
        persist()
    }

    func latest(_ count: Int) -> [ActivityEvent] { Array(events.prefix(count)) }

    func clear() {
        events.removeAll()
        try? FileManager.default.removeItem(at: fileURL)
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(events) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        var url = fileURL
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }
}

enum TimeText {
    static func relative(_ date: Date, now: Date = Date()) -> String {
        let seconds = max(0, now.timeIntervalSince(date))
        let calendar = Calendar.current
        if seconds < 60 { return "Just now" }
        if seconds < 3600 { return "\(Int(seconds / 60)) min ago" }
        if calendar.isDate(date, inSameDayAs: now) { return "\(Int(seconds / 3600)) h ago" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }

    static func dayLabel(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
        return date.formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    static func greeting(_ date: Date = Date()) -> String {
        switch Calendar.current.component(.hour, from: date) {
        case 5..<12: "Good morning"
        case 12..<17: "Good afternoon"
        default: "Good evening"
        }
    }
}
