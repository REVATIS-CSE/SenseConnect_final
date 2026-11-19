import AVFoundation
import Speech
import Observation

struct TranscriptSegment: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let date: Date
}

/// Thread-safe holder so the real-time audio tap can feed the current recognition request.
private final class RequestBox: @unchecked Sendable {
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    var onLevel: ((Float) -> Void)?

    func set(_ newValue: SFSpeechAudioBufferRecognitionRequest?) {
        lock.lock(); request = newValue; lock.unlock()
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        lock.lock(); let current = request; lock.unlock()
        current?.append(buffer)
        if let data = buffer.floatChannelData?[0], buffer.frameLength > 0 {
            var sum: Float = 0
            for i in 0..<Int(buffer.frameLength) { sum += data[i] * data[i] }
            let rms = sqrt(sum / Float(buffer.frameLength))
            onLevel?(min(1, rms * 12))
        }
    }
}

/// Continuous live transcription. Each phrase is committed after a short pause and a fresh
/// recognition task is started, giving uninterrupted captions. Uses on-device recognition
/// whenever the iPhone supports it for the current language. Audio is never stored.
@MainActor @Observable
final class HearingModel {
    enum Phase: Equatable { case idle, starting, listening, hearingSpeech }

    private(set) var phase: Phase = .idle
    private(set) var partial = ""
    private(set) var segments: [TranscriptSegment] = []
    private(set) var level: Float = 0
    private(set) var errorMessage: String?
    private(set) var needsPermission = false
    private(set) var onDevice = false
    private(set) var startedAt: Date?

    @ObservationIgnored private let app: AppModel
    @ObservationIgnored private let engine = AVAudioEngine()
    @ObservationIgnored private let box = RequestBox()
    @ObservationIgnored private var recognizer: SFSpeechRecognizer?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var silenceTimer: Task<Void, Never>?
    @ObservationIgnored private var sessionCap: Task<Void, Never>?
    @ObservationIgnored private var consecutiveErrors = 0
    @ObservationIgnored private var sessionPhrases = 0
    @ObservationIgnored private var sessionWords = 0

    init(app: AppModel) {
        self.app = app
        box.onLevel = { [weak self] value in
            Task { @MainActor in
                guard let self, self.phase != .idle else { return }
                self.level = self.level * 0.6 + value * 0.4
            }
        }
    }

    var isActive: Bool { phase != .idle }
    var fullTranscript: String { segments.map(\.text).joined(separator: "\n") }

    func start() async {
        errorMessage = nil
        needsPermission = false
        guard await Permissions.requestMicrophoneAndSpeech() else {
            needsPermission = true
            errorMessage = "Microphone and speech recognition access are needed to turn speech into text."
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US")),
              recognizer.isAvailable else {
            errorMessage = "Speech recognition isn't available right now. Check your internet connection or install the offline language in iOS Settings."
            return
        }
        self.recognizer = recognizer
        onDevice = recognizer.supportsOnDeviceRecognition
        app.speech.stop()
        phase = .starting

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker, .allowBluetooth])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [box] buffer, _ in box.append(buffer) }
            engine.prepare()
            try engine.start()
        } catch {
            fail("The microphone could not be started: \(error.localizedDescription)")
            return
        }
        sessionPhrases = 0
        sessionWords = 0
        consecutiveErrors = 0
        startedAt = Date()
        startRecognitionTask()
    }

    func stop() {
        guard isActive else { return }
        commitPartial()
        teardown()
        phase = .idle
        logSession()
    }

    func clear() {
        segments.removeAll()
        partial = ""
    }

    // MARK: Recognition tasks

    private func startRecognitionTask() {
        guard let recognizer else { return }
        generation += 1
        let current = generation
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = recognizer.supportsOnDeviceRecognition
        request.addsPunctuation = true
        box.set(request)
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            let failed = error != nil
            Task { @MainActor in self?.handle(text: text, isFinal: isFinal, failed: failed, generation: current) }
        }
        phase = .listening
        // Apple limits a single recognition request to about a minute: rotate before that.
        sessionCap?.cancel()
        sessionCap = Task { [weak self] in
            try? await Task.sleep(for: .seconds(50))
            guard !Task.isCancelled, let self, self.generation == current else { return }
            self.rotate()
        }
    }

    private func handle(text: String?, isFinal: Bool, failed: Bool, generation: Int) {
        guard generation == self.generation, isActive else { return }
        if let text, !text.isEmpty {
            consecutiveErrors = 0
            partial = text
            phase = .hearingSpeech
            // Commit the phrase after ~1.5 s without new words.
            silenceTimer?.cancel()
            silenceTimer = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(1500))
                guard !Task.isCancelled, let self, self.generation == generation else { return }
                self.rotate()
            }
        }
        if isFinal {
            rotate()
        } else if failed {
            // "No speech detected" and similar errors are routine during silence: restart quietly.
            consecutiveErrors += 1
            if consecutiveErrors > 5 {
                fail("Speech recognition keeps stopping. Check your connection or try again.")
            } else {
                rotate()
            }
        }
    }

    /// Commits the current phrase and starts a fresh recognition task on the same audio stream.
    private func rotate() {
        commitPartial()
        silenceTimer?.cancel()
        box.set(nil)
        task?.cancel()
        task = nil
        if isActive { startRecognitionTask() }
    }

    private func commitPartial() {
        let text = partial.trimmingCharacters(in: .whitespacesAndNewlines)
        partial = ""
        guard !text.isEmpty else { return }
        segments.append(TranscriptSegment(text: text, date: Date()))
        sessionPhrases += 1
        sessionWords += text.split(whereSeparator: { $0.isWhitespace }).count
    }

    private func teardown() {
        silenceTimer?.cancel()
        sessionCap?.cancel()
        box.set(nil)
        task?.cancel()
        task = nil
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        // Return to normal playback so typed replies and read-aloud aren't quiet "measurement" audio.
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        level = 0
        startedAt = nil
    }

    private func fail(_ message: String) {
        commitPartial()
        teardown()
        phase = .idle
        errorMessage = message
        logSession()
    }

    /// History keeps counts only; transcripts are sensitive and never persisted.
    private func logSession() {
        guard sessionPhrases > 0 else { return }
        app.activity.log(.hearing, "Live transcription", "\(sessionPhrases) phrases · \(sessionWords) words captioned")
        sessionPhrases = 0
        sessionWords = 0
    }
}
