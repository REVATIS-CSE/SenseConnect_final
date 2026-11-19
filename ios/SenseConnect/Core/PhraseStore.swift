import Foundation
import Observation

enum PhraseCategory: String, CaseIterable, Identifiable {
    case favorites, emergency, medical, basicNeeds, foodWater, responses, general, custom
    var id: Self { self }

    var label: String {
        switch self {
        case .favorites: "Favourites"
        case .emergency: "Emergency"
        case .medical: "Medical"
        case .basicNeeds: "Basic needs"
        case .foodWater: "Food & water"
        case .responses: "Yes / No"
        case .general: "General"
        case .custom: "My phrases"
        }
    }

    var symbol: String {
        switch self {
        case .favorites: "heart.fill"
        case .emergency: "staroflife.fill"
        case .medical: "cross.case.fill"
        case .basicNeeds: "figure.stand"
        case .foodWater: "fork.knife"
        case .responses: "hand.thumbsup.fill"
        case .general: "bubble.left.fill"
        case .custom: "pencil"
        }
    }

    var tone: Tone {
        switch self {
        case .emergency: .danger
        case .medical: .success
        case .responses: .success
        case .basicNeeds, .foodWater: .info
        default: .neutral
        }
    }
}

struct Phrase: Identifiable, Hashable {
    let id: String
    let text: String
    let category: PhraseCategory
    let symbol: String
    var isCustom = false
}

/// Communication board content. Built-in phrases ship with the app (offline); favourites and
/// custom phrases are stored on-device.
@MainActor @Observable
final class PhraseStore {
    let builtIn: [Phrase] = [
        Phrase(id: "em_help", text: "I need help.", category: .emergency, symbol: "hand.raised.fill"),
        Phrase(id: "em_ambulance", text: "Please call an ambulance.", category: .emergency, symbol: "cross.fill"),
        Phrase(id: "em_danger", text: "I am in danger.", category: .emergency, symbol: "exclamationmark.triangle.fill"),
        Phrase(id: "em_contact", text: "Please call my emergency contact.", category: .emergency, symbol: "phone.fill"),
        Phrase(id: "md_doctor", text: "I need a doctor.", category: .medical, symbol: "cross.case.fill"),
        Phrase(id: "md_pain", text: "I am in pain.", category: .medical, symbol: "bandage.fill"),
        Phrase(id: "md_medication", text: "I need my medication.", category: .medical, symbol: "pills.fill"),
        Phrase(id: "md_dizzy", text: "I feel dizzy.", category: .medical, symbol: "exclamationmark.circle"),
        Phrase(id: "bn_assist", text: "I need assistance.", category: .basicNeeds, symbol: "figure.roll"),
        Phrase(id: "bn_restroom", text: "I need to use the restroom.", category: .basicNeeds, symbol: "toilet.fill"),
        Phrase(id: "bn_rest", text: "I need to rest.", category: .basicNeeds, symbol: "bed.double.fill"),
        Phrase(id: "bn_cold", text: "I am feeling cold.", category: .basicNeeds, symbol: "thermometer.snowflake"),
        Phrase(id: "fw_water", text: "I need water.", category: .foodWater, symbol: "drop.fill"),
        Phrase(id: "fw_food", text: "I need food.", category: .foodWater, symbol: "fork.knife"),
        Phrase(id: "fw_hungry", text: "I am hungry.", category: .foodWater, symbol: "takeoutbag.and.cup.and.straw.fill"),
        Phrase(id: "fw_enough", text: "No more, thank you.", category: .foodWater, symbol: "checkmark"),
        Phrase(id: "rs_yes", text: "Yes.", category: .responses, symbol: "hand.thumbsup.fill"),
        Phrase(id: "rs_no", text: "No.", category: .responses, symbol: "hand.thumbsdown.fill"),
        Phrase(id: "rs_wait", text: "Please wait.", category: .responses, symbol: "hourglass"),
        Phrase(id: "rs_unsure", text: "I don't know.", category: .responses, symbol: "questionmark.circle"),
        Phrase(id: "gn_hello", text: "Hello, nice to meet you.", category: .general, symbol: "hand.wave.fill"),
        Phrase(id: "gn_thanks", text: "Thank you.", category: .general, symbol: "face.smiling"),
        Phrase(id: "gn_slow", text: "Please speak slowly.", category: .general, symbol: "tortoise.fill"),
        Phrase(id: "gn_write", text: "Please write it down for me.", category: .general, symbol: "pencil"),
        Phrase(id: "gn_aac", text: "I cannot speak. I use this app to communicate.", category: .general, symbol: "person.wave.2.fill"),
    ]

    private(set) var favorites: Set<String>
    private(set) var custom: [Phrase]
    @ObservationIgnored private let defaults = UserDefaults.standard

    init() {
        let d = UserDefaults.standard
        favorites = Set(d.stringArray(forKey: "favoritePhrases") ?? ["em_help", "md_doctor", "fw_water", "rs_yes", "rs_no", "gn_thanks"])
        let stored = d.array(forKey: "customPhrases") as? [[String: String]] ?? []
        custom = stored.compactMap { item in
            guard let id = item["id"], let text = item["text"] else { return nil }
            return Phrase(id: id, text: text, category: .custom, symbol: "bubble.left.fill", isCustom: true)
        }
    }

    func phrases(in category: PhraseCategory) -> [Phrase] {
        switch category {
        case .favorites: (builtIn + custom).filter { favorites.contains($0.id) }
        case .custom: custom
        default: builtIn.filter { $0.category == category }
        }
    }

    func phrase(id: String) -> Phrase? { (builtIn + custom).first { $0.id == id } }

    func isFavorite(_ phrase: Phrase) -> Bool { favorites.contains(phrase.id) }

    @discardableResult
    func toggleFavorite(_ phrase: Phrase) -> Bool {
        if favorites.contains(phrase.id) { favorites.remove(phrase.id) } else { favorites.insert(phrase.id) }
        defaults.set(Array(favorites), forKey: "favoritePhrases")
        return favorites.contains(phrase.id)
    }

    func addCustom(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        custom.append(Phrase(id: "custom_\(UUID().uuidString)", text: clean, category: .custom, symbol: "bubble.left.fill", isCustom: true))
        saveCustom()
    }

    func removeCustom(_ phrase: Phrase) {
        custom.removeAll { $0.id == phrase.id }
        favorites.remove(phrase.id)
        defaults.set(Array(favorites), forKey: "favoritePhrases")
        saveCustom()
    }

    private func saveCustom() {
        defaults.set(custom.map { ["id": $0.id, "text": $0.text] }, forKey: "customPhrases")
    }
}
