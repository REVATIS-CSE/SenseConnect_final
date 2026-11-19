import SwiftUI
import UIKit

// MARK: - Semantic colour system (mirrors the Android app; every pair meets WCAG AA)

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension Color {
    /// Colour that adapts to light and dark mode (tuned, not inverted).
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

enum SC {
    static let primary = Color.dynamic(0x4338CA, 0xB4BCFF)
    static let onPrimary = Color.dynamic(0xFFFFFF, 0x1A1760)
    static let primaryContainer = Color.dynamic(0xE4E6FF, 0x312E81)
    static let onPrimaryContainer = Color.dynamic(0x1E1B4B, 0xE4E6FF)

    static let secondary = Color.dynamic(0x0F766E, 0x6EE7D6)
    static let onSecondary = Color.dynamic(0xFFFFFF, 0x022C28)
    static let secondaryContainer = Color.dynamic(0xD2F5EF, 0x134E4A)
    static let onSecondaryContainer = Color.dynamic(0x042F2E, 0xD2F5EF)

    static let tertiary = Color.dynamic(0x1D5FC9, 0xA3C6FF)
    static let onTertiary = Color.dynamic(0xFFFFFF, 0x0B2A5B)
    static let tertiaryContainer = Color.dynamic(0xDCE8FF, 0x1E3A7A)
    static let onTertiaryContainer = Color.dynamic(0x0B2A5B, 0xDCE8FF)

    static let success = Color.dynamic(0x15803D, 0x5BE08A)
    static let successContainer = Color.dynamic(0xDCF7E3, 0x14532D)
    static let onSuccessContainer = Color.dynamic(0x052E16, 0xDCF7E3)

    static let warning = Color.dynamic(0xB45309, 0xFBC34A)
    static let warningContainer = Color.dynamic(0xFEF0C7, 0x6B3410)
    static let onWarningContainer = Color.dynamic(0x451A03, 0xFEF0C7)

    static let emergency = Color.dynamic(0xC62828, 0xFF8A80)
    static let onEmergency = Color.dynamic(0xFFFFFF, 0x4A0707)
    static let emergencyContainer = Color.dynamic(0xFDE3E1, 0x7A1717)
    static let onEmergencyContainer = Color.dynamic(0x5F0B0B, 0xFFE3E0)
    static let emergencyDeep = Color.dynamic(0x9F1C1C, 0xB42323)

    static let neutral = Color.dynamic(0x6B7280, 0x9CA3AF)
    static let neutralContainer = Color.dynamic(0xECEEF2, 0x2A303B)
    static let onNeutralContainer = Color.dynamic(0x1F2937, 0xE5E7EB)

    static let background = Color.dynamic(0xF5F6FA, 0x0E1116)
    static let surface = Color.dynamic(0xFFFFFF, 0x151922)
    static let surfaceContainer = Color.dynamic(0xF0F2F7, 0x1A1F29)
    static let onSurface = Color.dynamic(0x111827, 0xE8EAF0)
    static let onSurfaceVariant = Color.dynamic(0x4B5563, 0xAEB5C2)
    static let outline = Color.dynamic(0x9CA3AF, 0x6B7385)
    static let outlineVariant = Color.dynamic(0xE2E5EC, 0x2C3340)

    static let brandStart = Color(UIColor(hex: 0x3730A3))
    static let brandEnd = Color(UIColor(hex: 0x0F766E))
    static let scanActive = Color(UIColor(hex: 0x4ADE80))

    static let brandGradient = LinearGradient(colors: [brandStart, brandEnd], startPoint: .topLeading, endPoint: .bottomTrailing)
}

// MARK: - Tones (status is always text + colour, never colour alone)

enum Tone {
    case success, warning, danger, neutral, info

    var container: Color {
        switch self {
        case .success: SC.successContainer
        case .warning: SC.warningContainer
        case .danger: SC.emergencyContainer
        case .neutral: SC.neutralContainer
        case .info: SC.primaryContainer
        }
    }

    var onContainer: Color {
        switch self {
        case .success: SC.onSuccessContainer
        case .warning: SC.onWarningContainer
        case .danger: SC.onEmergencyContainer
        case .neutral: SC.onNeutralContainer
        case .info: SC.onPrimaryContainer
        }
    }

    var accent: Color {
        switch self {
        case .success: SC.success
        case .warning: SC.warning
        case .danger: SC.emergency
        case .neutral: SC.neutral
        case .info: SC.primary
        }
    }
}

/// Visual identity of each assistive module, shared by dashboard, history and onboarding.
struct ModuleStyle {
    let symbol: String
    let container: Color
    let onContainer: Color

    static let vision = ModuleStyle(symbol: "eye.fill", container: SC.primaryContainer, onContainer: SC.onPrimaryContainer)
    static let hearing = ModuleStyle(symbol: "ear.fill", container: SC.secondaryContainer, onContainer: SC.onSecondaryContainer)
    static let communication = ModuleStyle(symbol: "person.wave.2.fill", container: SC.tertiaryContainer, onContainer: SC.onTertiaryContainer)
    static let sos = ModuleStyle(symbol: "sos", container: SC.emergencyContainer, onContainer: SC.onEmergencyContainer)
    static let location = ModuleStyle(symbol: "location.fill", container: SC.successContainer, onContainer: SC.onSuccessContainer)
}

// MARK: - Typography (Dynamic Type text styles so the app follows iOS text size)

extension Font {
    static let scDisplay = Font.system(.largeTitle, weight: .semibold)
    static let scScreenTitle = Font.system(.title2, weight: .semibold)
    static let scTitle = Font.system(.headline)
    static let scBody = Font.system(.body)
    static let scSupporting = Font.system(.subheadline)
    static let scCaption = Font.system(.caption)
    static let scStatus = Font.system(.footnote, weight: .semibold)
    static let scLiveCaption = Font.system(.title, weight: .medium)
}

// MARK: - High contrast environment value

private struct HighContrastKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// True when the in-app High Contrast setting or iOS "Increase Contrast" is on.
    var scHighContrast: Bool {
        get { self[HighContrastKey.self] }
        set { self[HighContrastKey.self] = newValue }
    }
}
