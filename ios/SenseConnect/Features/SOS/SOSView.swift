import SwiftUI

/// SOS Emergency screen. Visually distinct from everyday features, activated by press-and-hold
/// plus a cancellable countdown, and honest about what was actually delivered.
@MainActor
struct SOSView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.openURL) private var openURL
    @State private var model: SOSModel?
    @State private var confirmForVoiceOver = false
    @State private var editingContact = false
    @State private var holdHint = "Press and hold to send SOS"

    var body: some View {
        Group {
            if let model { content(model) }
        }
        .background(SC.background)
        .navigationTitle("Emergency SOS")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if model == nil {
                let created = SOSModel(app: app)
                model = created
                await created.refreshLocation()
            }
        }
        .onDisappear {
            if model?.phase != .active { model?.shutdown() }
        }
    }

    private func content(_ model: SOSModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                banner(model)
                switch model.phase {
                case .ready, .countdown:
                    holdSection(model)
                case .active:
                    deliverySection(model)
                }
                Button {
                    dial(model.emergencyNumber)
                    model.recordManualAction("Emergency services call opened")
                } label: {
                    Label("Call emergency services (\(model.emergencyNumber))", systemImage: "phone.arrow.up.right.fill")
                        .font(.system(.body, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .foregroundStyle(SC.emergency)
                        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(SC.emergency, lineWidth: 1.5))
                }
                .padding(.top, 16)
                if model.phase != .active { readiness(model) }
                locationCard(model)
                SectionHeader(title: "Emergency message")
                Text(model.message)
                    .font(.scBody)
                    .textSelection(.enabled)
                    .scCard(padding: 16)
            }
            .padding(16)
        }
        .sheet(isPresented: Binding(get: { model.showComposer }, set: { if !$0 { model.showComposer = false } })) {
            MessageComposer(recipients: [app.settings.emergencyContactPhone], body: model.message) { result in
                model.composerFinished(result)
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $editingContact) {
            NavigationStack { EmergencyContactEditor() }
        }
        .confirmationDialog("Send emergency alert?", isPresented: $confirmForVoiceOver, titleVisibility: .visible) {
            Button("Send SOS", role: .destructive) { model.activateNow() }
        } message: {
            Text("SenseConnect will get your location and alert your emergency contact.")
        }
        .onChange(of: model.phase) { _, phase in
            if phase == .ready { holdHint = "Press and hold to send SOS" }
            UIApplication.shared.isIdleTimerDisabled = phase != .ready // keep the screen on during an emergency
        }
    }

    // MARK: Banner

    private func banner(_ model: SOSModel) -> some View {
        let s = app.settings
        let content: (bg: Color, fg: Color, symbol: String, title: String, body: String) = {
            switch model.phase {
            case .ready:
                let ready = s.hasEmergencyContact && model.location.fix != nil
                return ready
                    ? (SC.successContainer, SC.onSuccessContainer, "checkmark.shield.fill", "READY", "Your contact and location are ready to be shared.")
                    : (SC.warningContainer, SC.onWarningContainer, "exclamationmark.triangle.fill", "READY", "SOS works, but check the readiness items below.")
            case .countdown(let seconds):
                return (SC.warningContainer, SC.onWarningContainer, "timer", "SENDING IN \(seconds)", "Tap Cancel if this was a mistake.")
            case .active:
                let started = (model.activatedAt ?? Date()).formatted(date: .abbreviated, time: .standard)
                return (SC.emergencyDeep, .white, "sos", "SOS ACTIVATED", "Started \(started). Use the actions below to get help.")
            }
        }()
        return HStack(spacing: 14) {
            Image(systemName: content.symbol).font(.system(size: 30, weight: .semibold))
            VStack(alignment: .leading, spacing: 2) {
                Text("CURRENT STATUS").font(.scStatus).tracking(1.2)
                Text(content.title).font(.scScreenTitle)
                Text(content.body).font(.scSupporting)
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(content.fg)
        .padding(18)
        .background(content.bg, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    // MARK: Hold to send

    private func holdSection(_ model: SOSModel) -> some View {
        VStack(spacing: 12) {
            if case .countdown(let seconds) = model.phase {
                Text("Sending SOS in \(seconds)…").font(.scTitle).padding(.top, 24)
                Button {
                    Haptics.tap()
                    model.cancelCountdown()
                } label: {
                    Label("Cancel SOS", systemImage: "xmark").frame(minHeight: 64)
                }
                .buttonStyle(TonalButtonStyle(background: SC.surface, foreground: SC.emergency))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(SC.outline, lineWidth: 1))
            } else {
                HoldToActivateButton(
                    onStarted: { holdHint = "Keep holding…" },
                    onCancelled: { holdHint = "Released too early - hold for 3 seconds" },
                    onCompleted: { model.startCountdown() },
                    onAccessibleActivate: { confirmForVoiceOver = true }
                )
                .padding(.top, 24)
                Text(holdHint).font(.scTitle).multilineTextAlignment(.center)
                Text("Holding prevents accidental alerts. You get \(SOSModel.countdownSeconds) seconds to cancel.")
                    .font(.scSupporting).foregroundStyle(SC.onSurfaceVariant).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: Active

    private func deliverySection(_ model: SOSModel) -> some View {
        let s = app.settings
        return VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Alert delivery")
            VStack(spacing: 0) {
                deliveryRow("message.fill", "SMS to \(s.hasEmergencyContact ? s.emergencyContactDisplayName : "contact")", model.sms)
                Divider()
                deliveryRow("icloud.fill", "SenseConnect Cloud incident log", model.cloud)
            }
            .scCard(padding: 14)
            SectionHeader(title: "Get help")
            HStack(spacing: 10) {
                Button {
                    guard s.hasEmergencyContact else { editingContact = true; return }
                    dial(s.emergencyContactPhone)
                    model.recordManualAction("Call to emergency contact opened")
                } label: {
                    Label(s.hasEmergencyContact ? "Call \(s.emergencyContactName.isEmpty ? "contact" : s.emergencyContactName)" : "Call contact", systemImage: "phone.fill")
                }
                .buttonStyle(PrimaryButtonStyle(background: SC.emergency, foreground: SC.onEmergency))
                Button {
                    guard s.hasEmergencyContact else { editingContact = true; return }
                    model.openComposer()
                } label: {
                    Label("Send SMS", systemImage: "message.fill")
                }
                .buttonStyle(TonalButtonStyle())
            }
            HStack(spacing: 10) {
                ShareLink(item: model.message) {
                    Label("Share", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.bordered)
                .simultaneousGesture(TapGesture().onEnded { model.recordManualAction("Location shared") })
                Button {
                    Haptics.tap()
                    model.toggleAlarm()
                } label: {
                    Label(model.alarmOn ? "Stop alarm" : "Sound alarm", systemImage: model.alarmOn ? "stop.fill" : "bell.and.waves.left.and.right.fill")
                }
                .buttonStyle(TonalButtonStyle())
            }
            Button {
                model.endSOS()
            } label: {
                Label("I'm safe - end SOS", systemImage: "checkmark.shield.fill")
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(.bordered)
        }
    }

    private func deliveryRow(_ symbol: String, _ label: String, _ delivery: Delivery) -> some View {
        let (tone, state, detail): (Tone, String, String) = {
            switch delivery {
            case .idle: return (.neutral, "Waiting", "")
            case .inProgress: return (.info, "Sending", "Waiting for confirmation…")
            case .done(let text): return (.success, "Confirmed", text)
            case .failed(let text): return (.danger, "Failed", text)
            case .actionNeeded(let text): return (.warning, "Action needed", text)
            }
        }()
        return HStack(spacing: 12) {
            Image(systemName: symbol).frame(width: 24).foregroundStyle(SC.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.system(.subheadline, weight: .semibold))
                if !detail.isEmpty { Text(detail).font(.scCaption).foregroundStyle(SC.onSurfaceVariant) }
            }
            Spacer(minLength: 8)
            if delivery == .inProgress { ProgressView() }
            StatusPill(tone: tone, label: state)
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }

    // MARK: Readiness

    private func readiness(_ model: SOSModel) -> some View {
        let s = app.settings
        return VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "Readiness")
            VStack(spacing: 0) {
                Button { editingContact = true } label: {
                    readinessRow("person.fill", "Emergency contact",
                                 s.hasEmergencyContact ? s.emergencyContactDisplayName : "Needed to call or text someone you trust",
                                 s.hasEmergencyContact ? .success : .warning, s.hasEmergencyContact ? "Configured" : "Set up")
                }
                .buttonStyle(.plain)
                Divider()
                readinessRow("message.fill", "Message delivery",
                             model.canSendText ? "Messages opens pre-filled; you tap Send" : "This device cannot send SMS - use Call or Share",
                             model.canSendText ? .success : .neutral, model.canSendText ? "Messages" : "Unavailable")
                Divider()
                readinessRow("icloud.fill", "Cloud incident log",
                             !s.cloudLogging ? "Turned off in Settings" : app.network.isOnline ? "Incident time and location are logged" : "Offline - SOS still works without the cloud",
                             s.cloudLogging && app.network.isOnline ? .success : .neutral,
                             !s.cloudLogging ? "Off" : app.network.isOnline ? "Online" : "Offline")
            }
            .scCard(padding: 14)
        }
    }

    private func readinessRow(_ symbol: String, _ label: String, _ detail: String, _ tone: Tone, _ state: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).frame(width: 24).foregroundStyle(SC.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.system(.subheadline, weight: .semibold)).foregroundStyle(SC.onSurface)
                Text(detail).font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
            }
            Spacer(minLength: 8)
            StatusPill(tone: tone, label: state)
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    // MARK: Location

    private func locationCard(_ model: SOSModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "Location")
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    IconTile(module: .location, size: 40)
                    Text(locationTitle(model.location)).font(.scTitle)
                    Spacer()
                    if model.location == .loading { ProgressView() }
                }
                if let fix = model.location.fix {
                    VStack(alignment: .leading, spacing: 4) {
                        coordinateRow("Latitude", String(format: "%.5f", fix.latitude))
                        coordinateRow("Longitude", String(format: "%.5f", fix.longitude))
                        coordinateRow("Accuracy", fix.accuracy.map { "± \(Int($0.rounded())) m" } ?? "Unknown")
                        coordinateRow("Last updated", fix.timestamp.formatted(date: .abbreviated, time: .standard))
                    }
                } else if let message = locationMessage(model.location) {
                    Text(message).font(.scSupporting).foregroundStyle(SC.onSurfaceVariant)
                }
                HStack(spacing: 10) {
                    Button {
                        Haptics.tap()
                        switch model.location {
                        case .permissionDenied, .disabled: Permissions.openAppSettings()
                        default: Task { await model.refreshLocation() }
                        }
                    } label: {
                        Label(locationActionTitle(model.location), systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(TonalButtonStyle())
                    .disabled(model.location == .loading)
                    Button {
                        if let fix = model.location.fix, let url = URL(string: fix.mapsURL) { openURL(url) }
                    } label: {
                        Label("Open in Maps", systemImage: "map.fill").frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.location.fix == nil)
                }
            }
            .scCard(padding: 16)
        }
    }

    private func coordinateRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            Text(label).font(.scCaption).foregroundStyle(SC.onSurfaceVariant).frame(width: 96, alignment: .leading)
            Text(value).font(.system(.body, design: .monospaced))
        }
        .accessibilityElement(children: .combine)
    }

    private func locationTitle(_ state: LocationState) -> String {
        switch state {
        case .loading: return "Finding your location…"
        case .available(let fix): return fix.isFresh ? "Location available" : "Last known location"
        case .permissionDenied: return "Location permission needed"
        case .disabled: return "Location Services are off"
        case .unavailable: return "Location unavailable"
        }
    }

    private func locationMessage(_ state: LocationState) -> String? {
        switch state {
        case .loading: return "Getting a GPS fix. This can take a few seconds indoors."
        case .permissionDenied: return "Allow location so SenseConnect can share where you are in an emergency."
        case .disabled: return "Turn on Location Services in iOS Settings to share where you are."
        case .unavailable(let reason): return reason
        case .available: return nil
        }
    }

    private func locationActionTitle(_ state: LocationState) -> String {
        switch state {
        case .permissionDenied, .disabled: return "Settings"
        case .unavailable: return "Retry"
        default: return "Refresh"
        }
    }

    private func dial(_ number: String) {
        let digits = number.filter { $0.isNumber || $0 == "+" }
        if let url = URL(string: "tel://\(digits)") { openURL(url) } // iOS asks the user to confirm the call
    }
}

/// Large circular SOS control that must be held for 3 seconds. VoiceOver users activate it
/// with a double-tap, which opens an explicit confirmation instead of a timed hold.
struct HoldToActivateButton: View {
    let onStarted: () -> Void
    let onCancelled: () -> Void
    let onCompleted: () -> Void
    let onAccessibleActivate: () -> Void

    @State private var progress: CGFloat = 0
    @State private var pressing = false
    @State private var pressStart: Date?
    @State private var completed = false
    @State private var tickTask: Task<Void, Never>?
    private let holdSeconds = 3.0

    var body: some View {
        ZStack {
            Circle().fill(SC.emergencyContainer)
            Circle().fill(SC.emergencyDeep).padding(22)
            Circle().stroke(.white.opacity(0.25), lineWidth: 8).padding(36)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(.white, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(36)
            VStack(spacing: 4) {
                Text("SOS").font(.system(size: 46, weight: .black))
                Text("Hold 3 seconds").font(.system(.subheadline, weight: .semibold))
            }
            .foregroundStyle(.white)
        }
        .frame(width: 220, height: 220)
        .scaleEffect(pressing ? 0.97 : 1)
        .onLongPressGesture(minimumDuration: holdSeconds, maximumDistance: 60) {
            completed = true
            tickTask?.cancel()
            Haptics.success()
            progress = 0
            pressing = false
            onCompleted()
        } onPressingChanged: { isPressing in
            pressing = isPressing
            if isPressing {
                completed = false
                pressStart = Date()
                Haptics.heavy()
                onStarted()
                withAnimation(.linear(duration: holdSeconds)) { progress = 1 }
                tickTask = Task { @MainActor in
                    for _ in 0..<Int(holdSeconds) {
                        try? await Task.sleep(for: .seconds(1))
                        if Task.isCancelled { return }
                        Haptics.tick()
                    }
                }
            } else {
                tickTask?.cancel()
                // `progress` is already at its animation target, so judge by elapsed time.
                let heldLongEnough = pressStart.map { Date().timeIntervalSince($0) >= holdSeconds } ?? false
                pressStart = nil
                withAnimation(.easeOut(duration: 0.2)) { progress = 0 }
                if !completed && !heldLongEnough { onCancelled() }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("SOS")
        .accessibilityHint("Press and hold for 3 seconds to send an emergency alert. Double-tap to confirm with a dialog.")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { onAccessibleActivate() }
    }
}
