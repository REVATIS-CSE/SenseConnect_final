import AVFoundation
import UIKit
import Observation

/// App-wide on-device text-to-speech shared by Vision (read aloud), Communication, Hearing
/// replies and voice feedback. Rate and pitch are read from Settings on every utterance.
@MainActor @Observable
final class SpeechService {
    private(set) var isSpeaking = false

    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private let delegate = SynthDelegate()
    @ObservationIgnored private let settings: SettingsStore

    init(settings: SettingsStore) {
        self.settings = settings
        synthesizer.delegate = delegate
        delegate.onChange = { [weak self] speaking in
            Task { @MainActor in self?.isSpeaking = speaking }
        }
    }

    var isAvailable: Bool { !AVSpeechSynthesisVoice.speechVoices().isEmpty }

    var voiceDescription: String {
        let code = AVSpeechSynthesisVoice.currentLanguageCode()
        return Locale.current.localizedString(forIdentifier: code) ?? code
    }

    /// Speaks text. Returns false when there is nothing to speak.
    @discardableResult
    func speak(_ text: String) -> Bool {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return false }
        let session = AVAudioSession.sharedInstance()
        if session.category != .playAndRecord {
            // Play even when the ring/silent switch is on: speech is the app's core output.
            try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        }
        try? session.setActive(true)
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }

        let utterance = AVSpeechUtterance(string: clean)
        utterance.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
        let rate = AVSpeechUtteranceDefaultSpeechRate * Float(settings.speechRate)
        utterance.rate = min(max(rate, AVSpeechUtteranceMinimumSpeechRate), AVSpeechUtteranceMaximumSpeechRate)
        utterance.pitchMultiplier = Float(min(max(settings.speechPitch, 0.5), 2.0))
        synthesizer.speak(utterance)
        return true
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
    }

    /// Short confirmation. Uses VoiceOver's announcement when VoiceOver is running so the two
    /// voices never talk over each other; otherwise speaks if Voice Feedback is on.
    func announce(_ text: String) {
        if UIAccessibility.isVoiceOverRunning {
            UIAccessibility.post(notification: .announcement, argument: text)
        } else if settings.voiceFeedback, !isSpeaking {
            speak(text)
        }
    }
}

private final class SynthDelegate: NSObject, AVSpeechSynthesizerDelegate {
    var onChange: ((Bool) -> Void)?

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) { onChange?(true) }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { onChange?(false) }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { onChange?(false) }
}
