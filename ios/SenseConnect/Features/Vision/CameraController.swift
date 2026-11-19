import AVFoundation
import UIKit

/// AVFoundation camera: live preview, still capture for OCR, throttled live text detection
/// and torch control. All session work runs on a private serial queue.
final class CameraController: NSObject, @unchecked Sendable {
    enum CameraError: LocalizedError {
        case unavailable, captureFailed
        var errorDescription: String? {
            switch self {
            case .unavailable: "The camera is not available."
            case .captureFailed: "The photo could not be captured. Please try again."
            }
        }
    }

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "senseconnect.camera.session")
    private let videoQueue = DispatchQueue(label: "senseconnect.camera.video")
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private var device: AVCaptureDevice?
    private var configured = false

    private var photoContinuation: CheckedContinuation<(CGImage, CGImagePropertyOrientation), Error>?
    private var lastLiveAnalysis = Date.distantPast
    private var liveBusy = false
    /// Pauses live detection while a captured photo is being recognised.
    var livePaused = false

    /// Called on the main thread with the number of words currently visible.
    var onLiveWords: ((Int) -> Void)?

    var hasTorch: Bool { device?.hasTorch == true }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            sessionQueue.async {
                do {
                    try self.configureIfNeeded()
                    if !self.session.isRunning { self.session.startRunning() }
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func configureIfNeeded() throws {
        guard !configured else { return }
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera) else {
            throw CameraError.unavailable
        }
        session.beginConfiguration()
        session.sessionPreset = .photo
        guard session.canAddInput(input), session.canAddOutput(photoOutput) else {
            session.commitConfiguration()
            throw CameraError.unavailable
        }
        session.addInput(input)
        session.addOutput(photoOutput)
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) } // live guidance is optional
        session.commitConfiguration()
        if camera.isFocusModeSupported(.continuousAutoFocus) {
            try? camera.lockForConfiguration()
            camera.focusMode = .continuousAutoFocus
            camera.unlockForConfiguration()
        }
        device = camera
        configured = true
    }

    func capture() async throws -> (CGImage, CGImagePropertyOrientation) {
        try await withCheckedThrowingContinuation { continuation in
            sessionQueue.async {
                guard self.session.isRunning else {
                    continuation.resume(throwing: CameraError.unavailable)
                    return
                }
                self.photoContinuation = continuation
                self.photoOutput.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
            }
        }
    }

    func setTorch(_ on: Bool) {
        sessionQueue.async {
            guard let device = self.device, device.hasTorch, (try? device.lockForConfiguration()) != nil else { return }
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        }
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        defer { photoContinuation = nil }
        guard error == nil, let image = photo.cgImageRepresentation() else {
            photoContinuation?.resume(throwing: CameraError.captureFailed)
            return
        }
        let raw = photo.metadata[String(kCGImagePropertyOrientation)] as? UInt32
        let orientation = raw.flatMap(CGImagePropertyOrientation.init(rawValue:)) ?? .right
        photoContinuation?.resume(returning: (image, orientation))
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = Date()
        guard !livePaused, !liveBusy, now.timeIntervalSince(lastLiveAnalysis) > 0.9,
              let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastLiveAnalysis = now
        liveBusy = true
        // Back camera frames arrive in landscape; .right maps them to portrait.
        let words = TextRecognizer.quickWordCount(buffer, orientation: .right)
        liveBusy = false
        DispatchQueue.main.async { self.onLiveWords?(words) }
    }
}
