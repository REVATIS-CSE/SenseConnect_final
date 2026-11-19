import SwiftUI

/// Hearing Assistance: live captions of nearby speech plus a typed reply spoken aloud.
@MainActor
struct HearingView: View {
    let autoStart: Bool

    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var model: HearingModel?
    @State private var reply = ""
    @State private var replyError = false
    @FocusState private var replyFocused: Bool

    var body: some View {
        Group {
            if let model { content(model) }
        }
        .background(SC.background)
        .navigationTitle("Hearing Assistance")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if model == nil {
                let created = HearingModel(app: app)
                model = created
                if autoStart { await created.start() }
            }
        }
        .onDisappear { model?.stop() } // never keep the microphone open in the background
    }

    private func content(_ model: HearingModel) -> some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        statusRow(model)
                        liveCaption(model)
                        if let error = model.errorMessage { errorCard(model, error) }
                        transcriptHeader(model)
                        if model.segments.isEmpty {
                            Label("This session's transcript will build up here. It is not saved when you leave.", systemImage: "waveform")
                                .font(.scSupporting)
                                .foregroundStyle(SC.onSurfaceVariant)
                                .scCard(padding: 14, background: SC.surfaceContainer, border: Color.clear)
                        } else {
                            ForEach(model.segments) { segment in
                                HStack(alignment: .firstTextBaseline, spacing: 12) {
                                    Text(segment.date.formatted(date: .omitted, time: .shortened))
                                        .font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                                        .frame(width: 64, alignment: .leading)
                                    Text(segment.text).font(.system(.title3)).textSelection(.enabled)
                                }
                                .id(segment.id)
                                .accessibilityElement(children: .combine)
                            }
                        }
                    }
                    .padding(16)
                }
                .onChange(of: model.segments.count) { _, _ in
                    if let last = model.segments.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            bottomPanel(model)
        }
    }

    private func statusRow(_ model: HearingModel) -> some View {
        let state = listenState(model)
        return HStack(spacing: 12) {
            StatusPill(tone: state.tone, label: state.label)
            LevelMeter(level: model.level, active: model.isActive, animate: !(systemReduceMotion || app.settings.reduceMotion))
            Spacer()
            if let startedAt = model.startedAt {
                Text(startedAt, style: .timer).font(.scStatus).foregroundStyle(SC.onSurfaceVariant).monospacedDigit()
            }
        }
    }

    private func listenState(_ model: HearingModel) -> (tone: Tone, label: String) {
        switch model.phase {
        case .idle: return model.errorMessage == nil ? (.neutral, "Ready") : (.warning, "Stopped")
        case .starting: return (.info, "Starting microphone…")
        case .listening: return (.success, model.onDevice ? "Listening · on-device" : "Listening")
        case .hearingSpeech: return (.success, "Hearing speech")
        }
    }

    private func liveCaption(_ model: HearingModel) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("LIVE CAPTION").font(.scStatus).tracking(1.2).foregroundStyle(SC.secondary)
            Text(model.partial.isEmpty
                 ? (model.isActive ? "Listening for speech…" : "Tap Start listening. What people say will appear here in large text.")
                 : model.partial)
                .font(.scLiveCaption)
                .foregroundStyle(model.partial.isEmpty ? SC.onSurfaceVariant : SC.onSurface)
                .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
                .accessibilityAddTraits(.updatesFrequently)
        }
        .scCard(border: model.isActive ? SC.secondary : nil)
    }

    private func errorCard(_ model: HearingModel, _ message: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
            Text(message).font(.scSupporting)
            Spacer(minLength: 0)
            Button(model.needsPermission ? "Settings" : "Retry") {
                if model.needsPermission { Permissions.openAppSettings() } else { Task { await model.start() } }
            }
            .font(.system(.subheadline, weight: .semibold))
        }
        .foregroundStyle(SC.onWarningContainer)
        .scCard(padding: 14, background: SC.warningContainer, border: SC.warning)
    }

    private func transcriptHeader(_ model: HearingModel) -> some View {
        HStack {
            Text("TRANSCRIPT").font(.scStatus).tracking(1.2).foregroundStyle(SC.onSurfaceVariant).accessibilityAddTraits(.isHeader)
            Spacer()
            Button { UIPasteboard.general.string = model.fullTranscript; Haptics.success() } label: { Image(systemName: "doc.on.doc").frame(width: 44, height: 44) }
                .accessibilityLabel("Copy transcript")
            ShareLink(item: model.fullTranscript) { Image(systemName: "square.and.arrow.up").frame(width: 44, height: 44) }
                .accessibilityLabel("Share transcript")
            Button { Haptics.tap(); model.clear() } label: { Image(systemName: "trash").frame(width: 44, height: 44) }
                .accessibilityLabel("Clear transcript")
        }
        .disabled(model.segments.isEmpty)
        .padding(.top, 8)
    }

    private func bottomPanel(_ model: HearingModel) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                TextField("Type a reply to speak aloud", text: $reply, axis: .vertical)
                    .lineLimit(1...3)
                    .focused($replyFocused)
                    .submitLabel(.send)
                    .onSubmit { speakReply(model) }
                    .padding(14)
                    .background(SC.surface, in: RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(replyError ? SC.emergency : SC.outline, lineWidth: 1))
                Button { speakReply(model) } label: {
                    Image(systemName: "speaker.wave.2.fill").frame(width: 56, height: 56)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityLabel("Speak reply")
            }
            Button {
                Haptics.tap()
                if model.isActive {
                    model.stop()
                    app.speech.announce("Stopped listening.")
                } else {
                    Task { await model.start() }
                }
            } label: {
                Label(model.isActive ? "Stop listening" : "Start listening", systemImage: model.isActive ? "stop.fill" : "mic.fill")
                    .frame(minHeight: 60)
            }
            .buttonStyle(PrimaryButtonStyle(background: SC.secondary, foreground: SC.onSecondary))
        }
        .padding(16)
        .background(SC.surface)
    }

    /// Deaf or hard-of-hearing users can answer by typing; SenseConnect speaks the reply.
    private func speakReply(_ model: HearingModel) {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { replyError = true; return }
        replyError = false
        if model.isActive { model.stop() } // don't transcribe our own voice
        app.speech.speak(text)
        Haptics.success()
        app.activity.log(.communication, "Typed reply spoken", "From Hearing Assistance")
        reply = ""
        replyFocused = false
    }
}

/// Five-bar microphone level meter: visual confirmation that sound is being picked up.
struct LevelMeter: View {
    let level: Float
    let active: Bool
    let animate: Bool
    private let weights: [CGFloat] = [0.55, 0.8, 1, 0.8, 0.55]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(weights.indices, id: \.self) { i in
                Capsule()
                    .fill(active ? SC.secondary : SC.outline)
                    .frame(width: 4, height: max(4, 24 * (active ? max(0.15, CGFloat(level) * weights[i]) : 0.15)))
            }
        }
        .frame(height: 24)
        .animation(animate ? .easeOut(duration: 0.12) : nil, value: level)
        .accessibilityHidden(true)
    }
}
