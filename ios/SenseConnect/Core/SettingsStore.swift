import SwiftUI
import Observation

enum ThemeMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: Self { self }
    var label: String { rawValue.capitalized }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// In-app text size. Acts as a minimum on top of the iOS Dynamic Type setting.
enum TextSize: String, CaseIterable, Identifiable {
    case standard, large, extraLarge
    var id: Self { self }
    var label: String {
        switch self {
        case .standard: "Default"
        case .large: "Large"
        case .extraLarge: "Extra large"
        }
    }
    var range: ClosedRange<DynamicTypeSize> {
        switch self {
        case .standard: .xSmall ... .accessibility5
        case .large: .xLarge ... .accessibility5
        case .extraLarge: .xxxLarge ... .accessibility5
        }
    }
}

/// User preferences persisted in UserDefaults and observed by every screen.
@MainActor @Observable
final class SettingsStore {
    static let defaultEmergencyMessage = "EMERGENCY: I need help. This alert was sent from SenseConnect."

    @ObservationIgnored private let defaults = UserDefaults.standard

    var textSize: TextSize { didSet { defaults.set(textSize.rawValue, forKey: "textSize") } }
    var theme: ThemeMode { didSet { defaults.set(theme.rawValue, forKey: "theme") } }
    var highContrast: Bool { didSet { defaults.set(highContrast, forKey: "highContrast") } }
    var reduceMotion: Bool { didSet { defaults.set(reduceMotion, forKey: "reduceMotion") } }
    var voiceFeedback: Bool { didSet { defaults.set(voiceFeedback, forKey: "voiceFeedback") } }
    var haptics: Bool { didSet { defaults.set(haptics, forKey: "haptics"); Haptics.enabled = haptics } }
    var speechRate: Double { didSet { defaults.set(speechRate, forKey: "speechRate") } }
    var speechPitch: Double { didSet { defaults.set(speechPitch, forKey: "speechPitch") } }
    var speakOnTap: Bool { didSet { defaults.set(speakOnTap, forKey: "speakOnTap") } }
    var autoReadScans: Bool { didSet { defaults.set(autoReadScans, forKey: "autoReadScans") } }
    var emergencyContactName: String { didSet { defaults.set(emergencyContactName, forKey: "contactName") } }
    var emergencyContactPhone: String { didSet { defaults.set(emergencyContactPhone, forKey: "contactPhone") } }
    var emergencyMessage: String { didSet { defaults.set(emergencyMessage, forKey: "emergencyMessage") } }
    var cloudLogging: Bool { didSet { defaults.set(cloudLogging, forKey: "cloudLogging") } }
    var onboardingComplete: Bool { didSet { defaults.set(onboardingComplete, forKey: "onboardingComplete") } }

    /// Random installation ID used to correlate backend incidents. Not a hardware identifier.
    let installationID: String

    init() {
        let d = UserDefaults.standard
        func bool(_ key: String, _ fallback: Bool) -> Bool { d.object(forKey: key) == nil ? fallback : d.bool(forKey: key) }
        func double(_ key: String, _ fallback: Double) -> Double { d.object(forKey: key) == nil ? fallback : d.double(forKey: key) }

        textSize = TextSize(rawValue: d.string(forKey: "textSize") ?? "") ?? .standard
        theme = ThemeMode(rawValue: d.string(forKey: "theme") ?? "") ?? .system
        highContrast = bool("highContrast", false)
        reduceMotion = bool("reduceMotion", false)
        voiceFeedback = bool("voiceFeedback", true)
        haptics = bool("haptics", true)
        speechRate = double("speechRate", 1.0)
        speechPitch = double("speechPitch", 1.0)
        speakOnTap = bool("speakOnTap", true)
        autoReadScans = bool("autoReadScans", false)
        emergencyContactName = d.string(forKey: "contactName") ?? ""
        emergencyContactPhone = d.string(forKey: "contactPhone") ?? ""
        emergencyMessage = d.string(forKey: "emergencyMessage") ?? Self.defaultEmergencyMessage
        cloudLogging = bool("cloudLogging", true)
        onboardingComplete = bool("onboardingComplete", false)

        if let id = d.string(forKey: "installationID") {
            installationID = id
        } else {
            let id = UUID().uuidString.lowercased()
            d.set(id, forKey: "installationID")
            installationID = id
        }
        Haptics.enabled = haptics
    }

    var hasEmergencyContact: Bool {
        !emergencyContactPhone.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var emergencyContactDisplayName: String {
        emergencyContactName.isEmpty ? emergencyContactPhone : emergencyContactName
    }
}
