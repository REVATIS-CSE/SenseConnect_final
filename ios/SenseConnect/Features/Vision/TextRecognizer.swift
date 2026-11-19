import Vision
import UIKit

struct RecognizedText: Equatable {
    let text: String
    let lines: Int
    var words: Int { text.split(whereSeparator: { $0.isWhitespace }).count }
}

/// On-device OCR with Apple's Vision framework. Nothing leaves the phone and no image is stored.
enum TextRecognizer {
    /// Accurate recognition for a captured photo or imported image.
    static func recognize(_ image: CGImage, orientation: CGImagePropertyOrientation) async throws -> RecognizedText {
        try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.automaticallyDetectsLanguage = true
            let handler = VNImageRequestHandler(cgImage: image, orientation: orientation)
            try handler.perform([request])
            let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
            return RecognizedText(text: lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines), lines: lines.count)
        }.value
    }

    /// Fast, low-cost check used only for live guidance ("Text detected - hold steady").
    static func quickWordCount(_ buffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> Int {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        let handler = VNImageRequestHandler(cvPixelBuffer: buffer, orientation: orientation)
        try? handler.perform([request])
        return (request.results ?? [])
            .compactMap { $0.topCandidates(1).first?.string }
            .reduce(0) { $0 + $1.split(separator: " ").count }
    }
}

extension CGImagePropertyOrientation {
    init(_ orientation: UIImage.Orientation) {
        switch orientation {
        case .up: self = .up
        case .down: self = .down
        case .left: self = .left
        case .right: self = .right
        case .upMirrored: self = .upMirrored
        case .downMirrored: self = .downMirrored
        case .leftMirrored: self = .leftMirrored
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
