import AVFoundation

/// Loud looping two-tone alarm to attract nearby attention, for users who cannot shout for
/// help. The tone is synthesised in memory, so no audio file is bundled.
@MainActor
final class AlarmPlayer {
    private var player: AVAudioPlayer?

    var isPlaying: Bool { player?.isPlaying == true }

    @discardableResult
    func start() -> Bool {
        if isPlaying { return true }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
            let player = try AVAudioPlayer(data: Self.makeSiren())
            player.numberOfLoops = -1
            player.volume = 1
            player.play()
            self.player = player
        } catch {
            player = nil
        }
        return isPlaying
    }

    func stop() {
        player?.stop()
        player = nil
    }

    /// 1 second of alternating 960 Hz / 720 Hz tones as 16-bit mono PCM WAV data.
    private static func makeSiren(sampleRate: Int = 22_050) -> Data {
        var samples = [Int16]()
        samples.reserveCapacity(sampleRate)
        for i in 0..<sampleRate {
            let t = Double(i) / Double(sampleRate)
            let frequency = (i / (sampleRate / 4)) % 2 == 0 ? 960.0 : 720.0
            samples.append(Int16(sin(2 * .pi * frequency * t) * 30_000))
        }
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let byteCount = samples.count * 2
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + byteCount))
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(sampleRate)); append(UInt32(sampleRate * 2)); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(UInt32(byteCount))
        samples.forEach { append($0) }
        return data
    }
}
