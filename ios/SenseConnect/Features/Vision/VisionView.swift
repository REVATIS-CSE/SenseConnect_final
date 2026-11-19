import SwiftUI
import PhotosUI
import AVFoundation

/// Vision pipeline state: camera / photo → on-device OCR → recognised text → read aloud.
@MainActor @Observable
final class VisionModel {
    enum CameraState: Equatable { case starting, running, denied, unavailable, failed(String) }
    enum ScanState: Equatable { case idle, processing, result(RecognizedText), noText, error(String) }

    var cameraState: CameraState = .starting
    var scanState: ScanState = .idle
    var liveWords = 0
    var torchOn = false

    @ObservationIgnored let camera = CameraController()
    @ObservationIgnored private let app: AppModel

    init(app: AppModel) {
        self.app = app
        camera.onLiveWords = { [weak self] words in self?.liveWords = words }
    }

    var isProcessing: Bool { scanState == .processing }

    func startCamera() async {
        switch Permissions.cameraStatus {
        case .notDetermined:
            guard await Permissions.requestCamera() else { cameraState = .denied; return }
        case .authorized:
            break
        default:
            cameraState = .denied
            return
        }
        do {
            try await camera.start()
            cameraState = .running
        } catch {
            cameraState = AVCaptureDevice.default(for: .video) == nil ? .unavailable : .failed(error.localizedDescription)
        }
    }

    func stopCamera() {
        camera.setTorch(false)
        torchOn = false
        camera.stop()
        app.speech.stop()
    }

    func scan() async {
        guard cameraState == .running, !isProcessing else { return }
        app.speech.stop()
        app.speech.announce("Scanning.")
        scanState = .processing
        camera.livePaused = true
        defer { camera.livePaused = false }
        do {
            let (image, orientation) = try await camera.capture()
            await recognize(image, orientation: orientation, source: "Text scanned")
        } catch {
            scanState = .error(error.localizedDescription)
        }
    }

    func recognize(photo item: PhotosPickerItem) async {
        scanState = .processing
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data), let cgImage = image.cgImage else {
            scanState = .error("That image could not be opened.")
            return
        }
        await recognize(cgImage, orientation: CGImagePropertyOrientation(image.imageOrientation), source: "Image scanned")
    }

    private func recognize(_ image: CGImage, orientation: CGImagePropertyOrientation, source: String) async {
        do {
            let result = try await TextRecognizer.recognize(image, orientation: orientation)
            guard !result.text.isEmpty else {
                scanState = .noText
                Haptics.warning()
                app.speech.announce("No text found.")
                return
            }
            scanState = .result(result)
            Haptics.success()
            // History stores counts only, never the scanned text.
            app.activity.log(.vision, source, "\(result.words) words · \(result.lines) lines recognised on-device")
            if app.settings.autoReadScans {
                app.speech.speak(result.text)
            } else {
                app.speech.announce("\(result.words) words recognised.")
            }
        } catch {
            scanState = .error("Text recognition failed: \(error.localizedDescription)")
        }
    }

    func toggleTorch() {
        torchOn.toggle()
        camera.setTorch(torchOn)
    }

    func clear() {
        app.speech.stop()
        scanState = .idle
    }
}

@MainActor
struct VisionView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var model: VisionModel?
    @State private var photoItem: PhotosPickerItem?
    @State private var copied = false

    var body: some View {
        VStack(spacing: 12) {
            if let model {
                cameraArea(model)
                resultCard(model)
                controls(model)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .background(SC.background)
        .navigationTitle("Vision Assistance")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let model, model.cameraState == .running, model.camera.hasTorch {
                Button { model.toggleTorch() } label: {
                    Image(systemName: model.torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                }
                .accessibilityLabel(model.torchOn ? "Turn off light" : "Turn on light")
            }
        }
        .task {
            if model == nil { model = VisionModel(app: app) }
            await model?.startCamera()
        }
        .onDisappear { model?.stopCamera() }
        .onChange(of: photoItem) { _, item in
            guard let item, let model else { return }
            Task { await model.recognize(photo: item) }
            photoItem = nil
        }
    }

    // MARK: Camera

    private func cameraArea(_ model: VisionModel) -> some View {
        ZStack {
            Color.black
            switch model.cameraState {
            case .running:
                CameraPreview(session: model.camera.session)
                    .accessibilityLabel("Live camera preview")
                ScanFrame(active: model.liveWords > 0 && !model.isProcessing,
                          scanning: model.isProcessing && !(systemReduceMotion || app.settings.reduceMotion))
                VStack {
                    guidePill(model)
                    Spacer()
                    if model.isProcessing {
                        HStack(spacing: 12) {
                            ProgressView().tint(SC.scanActive)
                            Text("Recognising text…").font(.system(.subheadline, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 20).padding(.vertical, 14)
                        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 16))
                        Spacer()
                    }
                }
                .padding(.top, 14)
            case .starting:
                ProgressView().tint(.white)
            case .denied:
                StateMessage(symbol: "camera.fill", title: "Camera access needed",
                             message: "Vision Assistance uses the camera to recognise and read text. You can still scan a saved photo.",
                             tone: .warning, actionTitle: "Open Settings") { Permissions.openAppSettings() }
                    .foregroundStyle(.white)
            case .unavailable:
                StateMessage(symbol: "camera.fill", title: "No camera available",
                             message: "Use “Scan a photo” instead.", tone: .neutral)
                    .foregroundStyle(.white)
            case .failed(let reason):
                StateMessage(symbol: "exclamationmark.triangle.fill", title: "Camera unavailable", message: reason,
                             tone: .danger, actionTitle: "Retry") { Task { await model.startCamera() } }
                    .foregroundStyle(.white)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .frame(maxHeight: .infinity)
    }

    private func guidePill(_ model: VisionModel) -> some View {
        let detected = model.liveWords > 0 && !model.isProcessing
        let label = model.isProcessing ? "Hold still…" : detected ? "Text detected - hold steady and tap Scan" : "Align text inside the frame"
        return HStack(spacing: 6) {
            Circle().fill(detected ? SC.scanActive : .white).frame(width: 8, height: 8)
            Text(label)
        }
        .font(.scStatus)
        .foregroundStyle(.white)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(.black.opacity(0.7), in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }

    // MARK: Result

    private func resultCard(_ model: VisionModel) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Recognised text").font(.scTitle).accessibilityAddTraits(.isHeader)
                Spacer()
                switch model.scanState {
                case .idle: StatusPill(tone: .neutral, label: "Ready")
                case .processing: StatusPill(tone: .info, label: "Processing")
                case .result(let r): StatusPill(tone: .success, label: "\(r.words) word\(r.words == 1 ? "" : "s")")
                case .noText: StatusPill(tone: .warning, label: "No text")
                case .error: StatusPill(tone: .danger, label: "Error")
                }
            }
            switch model.scanState {
            case .result(let result):
                ScrollView {
                    Text(result.text)
                        .font(.system(.body))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                }
                .frame(maxHeight: 160)
                .background(SC.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
                HStack(spacing: 8) {
                    Button {
                        Haptics.tap()
                        if app.speech.isSpeaking { app.speech.stop() } else { app.speech.speak(result.text) }
                    } label: {
                        Label(app.speech.isSpeaking ? "Stop reading" : "Read aloud",
                              systemImage: app.speech.isSpeaking ? "stop.fill" : "speaker.wave.2.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    Button {
                        UIPasteboard.general.string = result.text
                        Haptics.success()
                        copied = true
                    } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc").frame(width: 56, height: 56) }
                    .buttonStyle(.bordered)
                    .accessibilityLabel(copied ? "Copied" : "Copy")
                    ShareLink(item: result.text) { Image(systemName: "square.and.arrow.up").frame(width: 56, height: 56) }
                        .buttonStyle(.bordered)
                        .accessibilityLabel("Share")
                }
            default:
                emptyRow(model.scanState)
            }
        }
        .scCard(padding: 16)
        .onChange(of: model.scanState) { _, _ in copied = false }
    }

    private func scanButtonTitle(_ model: VisionModel) -> String {
        if model.isProcessing { return "Reading…" }
        if case .result = model.scanState { return "Scan again" }
        return "Scan text"
    }

    private func emptyContent(_ state: VisionModel.ScanState) -> (symbol: String, tone: Tone, title: String, message: String) {
        switch state {
        case .processing: return ("doc.text.viewfinder", .info, "Recognising text…", "On-device OCR is analysing the image.")
        case .noText: return ("questionmark.circle", .warning, "No text found", "Move closer, improve the lighting and keep the phone steady.")
        case .error(let message): return ("exclamationmark.triangle.fill", .danger, "Couldn't read text", message)
        default: return ("textformat", .neutral, "No text captured", "Point the camera at printed text and tap Scan.")
        }
    }

    private func emptyRow(_ state: VisionModel.ScanState) -> some View {
        let content = emptyContent(state)
        return HStack(spacing: 12) {
            IconTile(content.0, container: content.1.container, onContainer: content.1.onContainer, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(content.2).font(.system(.body, weight: .semibold))
                Text(content.3).font(.scSupporting).foregroundStyle(SC.onSurfaceVariant)
            }
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: Controls

    private func controls(_ model: VisionModel) -> some View {
        HStack(spacing: 12) {
            PhotosPicker(selection: $photoItem, matching: .images) {
                Image(systemName: "photo.on.rectangle").font(.title3).frame(width: 64, height: 64)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Scan a photo")

            Button {
                Haptics.tap()
                Task { await model.scan() }
            } label: {
                Label(scanButtonTitle(model), systemImage: "doc.text.viewfinder")
                    .frame(minHeight: 64)
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.isProcessing || model.cameraState != .running)

            Button {
                Haptics.tap()
                model.clear()
            } label: {
                Image(systemName: "xmark").font(.title3).frame(width: 64, height: 64)
            }
            .buttonStyle(.bordered)
            .disabled(model.scanState == .idle)
            .accessibilityLabel("Clear")
        }
    }
}

// MARK: - Camera preview & scan frame

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}
}

/// Dims everything outside the scan area; the frame turns green when text is detected.
struct ScanFrame: View {
    let active: Bool
    let scanning: Bool
    @State private var sweep = false

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width * 0.86
            let height = min(geo.size.height * 0.7, width * 1.15)
            let frame = CGRect(x: (geo.size.width - width) / 2, y: (geo.size.height - height) / 2, width: width, height: height)
            ZStack(alignment: .topLeading) {
                Path { path in
                    path.addRect(CGRect(origin: .zero, size: geo.size))
                    path.addRoundedRect(in: frame, cornerSize: CGSize(width: 18, height: 18))
                }
                .fill(.black.opacity(0.55), style: FillStyle(eoFill: true))
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(active ? SC.scanActive : .white, lineWidth: 3)
                    .frame(width: frame.width, height: frame.height)
                    .offset(x: frame.minX, y: frame.minY)
                if scanning {
                    LinearGradient(colors: [.clear, SC.scanActive, .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: frame.width - 16, height: 3)
                        .offset(x: frame.minX + 8, y: frame.minY + (sweep ? frame.height - 8 : 8))
                        .onAppear {
                            withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: true)) { sweep = true }
                        }
                        .onDisappear { sweep = false }
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
