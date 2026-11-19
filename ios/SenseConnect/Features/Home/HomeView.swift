import SwiftUI

/// SenseConnect dashboard: System status → Assistive services → Quick actions →
/// Recent activity → Service availability → Emergency access.
@MainActor
struct HomeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase

    let navigate: (Route) -> Void
    let showActivity: () -> Void
    let showSettings: () -> Void

    @State private var refreshToken = 0
    @State private var locating = false
    @State private var shareItem: ShareItem?
    @State private var alertMessage: String?

    var body: some View {
        let _ = refreshToken // re-evaluate permission-based statuses when this changes
        let statuses = ServiceStatusBuilder.snapshot(app: app)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                greeting
                hero(statuses)
                if let announcement = app.backend.config.announcement {
                    Label(announcement, systemImage: "info.circle.fill")
                        .font(.scSupporting)
                        .foregroundStyle(SC.onTertiaryContainer)
                        .scCard(padding: 14, background: SC.tertiaryContainer, border: Color.clear)
                        .padding(.top, 12)
                }
                services(statuses)
                quickActions(statuses)
                recentActivity
                serviceStatus(statuses)
                emergencyAccess
                Text("SenseConnect \(AppConfig.version) · Core features run on-device")
                    .font(.scCaption)
                    .foregroundStyle(SC.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 28)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .background(SC.background)
        .toolbar(.hidden, for: .navigationBar)
        .refreshable {
            await app.refreshCloud()
            refreshToken += 1
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { refreshToken += 1 }
        }
        .sheet(item: $shareItem) { item in ShareSheet(items: [item.text]) }
        .alert("Location", isPresented: Binding(get: { alertMessage != nil }, set: { if !$0 { alertMessage = nil } })) {
            Button("OK", role: .cancel) {}
            Button("Open Settings") { Permissions.openAppSettings() }
        } message: {
            Text(alertMessage ?? "")
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            BrandBadge(size: 40)
            VStack(alignment: .leading, spacing: 0) {
                Text("SenseConnect").font(.system(.title3, weight: .semibold)).accessibilityAddTraits(.isHeader)
                Text("Assistive technology companion").font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
            }
            Spacer()
            Button {
                Haptics.tap()
                navigate(.sos)
            } label: {
                Label("SOS", systemImage: "staroflife.fill")
                    .font(.system(.headline, weight: .bold))
                    .padding(.horizontal, 16)
                    .frame(minHeight: 48)
                    .foregroundStyle(SC.onEmergency)
                    .background(SC.emergency, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .accessibilityLabel("Open emergency SOS")
        }
        .padding(.top, 8)
    }

    private var greeting: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(TimeText.greeting()).font(.scDisplay)
            Text(Date().formatted(.dateTime.weekday(.wide).day().month(.wide)))
                .font(.scSupporting)
                .foregroundStyle(SC.onSurfaceVariant)
        }
        .padding(.top, 20)
    }

    // MARK: 1. System status

    private func hero(_ statuses: [ServiceStatus]) -> some View {
        let local = statuses.filter { $0.id != .cloud }
        let ready = local.filter { $0.tone == .success }.count
        let allReady = ready == local.count
        func isReady(_ id: ServiceID) -> Bool { statuses.first { $0.id == id }?.tone == .success }

        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("SYSTEM STATUS").font(.scStatus).tracking(1.2).foregroundStyle(.white.opacity(0.85))
                Spacer()
                HStack(spacing: 6) {
                    Circle().fill(.white).frame(width: 8, height: 8)
                    Text(cloudLabel)
                }
                .font(.scStatus)
                .foregroundStyle(.white)
                .padding(.horizontal, 11).padding(.vertical, 5)
                .background(.white.opacity(0.2), in: Capsule())
                .accessibilityElement(children: .combine)
                .accessibilityLabel("SenseConnect Cloud status: \(cloudLabel)")
            }
            Text(allReady ? "Ready to assist" : "Attention needed")
                .font(.scScreenTitle)
                .foregroundStyle(.white)
                .padding(.top, 14)
            Text(allReady
                 ? "All \(local.count) assistive services are available. Core features work offline."
                 : "\(local.count - ready) service(s) need attention. See Service status below.")
                .font(.scSupporting)
                .foregroundStyle(.white.opacity(0.9))
                .padding(.top, 4)
            FlowLayout(spacing: 8) {
                heroPill("Vision", ok: isReady(.camera))
                heroPill("Hearing", ok: isReady(.microphone))
                heroPill("Communication", ok: isReady(.speech))
                heroPill("SOS", ok: isReady(.location) && app.settings.hasEmergencyContact)
            }
            .padding(.top, 16)
            Divider().overlay(.white.opacity(0.3)).padding(.top, 18)
            HStack(spacing: 8) {
                BrandMark().frame(width: 20, height: 20)
                Text("One Device. Three Disabilities.").font(.scStatus).foregroundStyle(.white)
            }
            .padding(.top, 14)
        }
        .padding(20)
        .background(SC.brandGradient, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.top, 20)
    }

    private var cloudLabel: String {
        if !app.network.isOnline { return "Cloud Offline" }
        switch app.backend.status {
        case .online: return "Cloud Online"
        case .unknown, .checking: return "Connecting…"
        case .waking: return "Waking server…"
        case .offline: return "Cloud Offline"
        }
    }

    private func heroPill(_ label: String, ok: Bool) -> some View {
        Label(label, systemImage: ok ? "checkmark.circle.fill" : "exclamationmark.circle")
            .font(.scStatus)
            .foregroundStyle(.white)
            .padding(.horizontal, 11).padding(.vertical, 6)
            .background(.white.opacity(0.2), in: Capsule())
            .accessibilityLabel("\(label): \(ok ? "ready" : "needs attention")")
    }

    // MARK: 2. Assistive services

    private func services(_ statuses: [ServiceStatus]) -> some View {
        VStack(spacing: 12) {
            SectionHeader(title: "Assistive services")
            serviceCard(.vision, title: "Vision Assistance", summary: "Read printed text aloud with the camera",
                        pipeline: ["Camera", "OCR", "Speech"], status: statuses.first { $0.id == .camera }, route: .vision)
            serviceCard(.hearing, title: "Hearing Assistance", summary: "Turn nearby speech into live captions",
                        pipeline: ["Mic", "Recognition", "Captions"], status: statuses.first { $0.id == .microphone }, route: .hearing(autoStart: false))
            serviceCard(.communication, title: "Communication", summary: "Speak phrases or typed messages aloud",
                        pipeline: ["Phrase", "TTS", "Voice"], status: statuses.first { $0.id == .speech }, route: .communication)
        }
    }

    private func serviceCard(_ style: ModuleStyle, title: String, summary: String, pipeline: [String], status: ServiceStatus?, route: Route) -> some View {
        Button {
            Haptics.tap()
            navigate(route)
        } label: {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 16) {
                    IconTile(module: style, size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).font(.scTitle).foregroundStyle(SC.onSurface)
                        Text(summary).font(.scSupporting).foregroundStyle(SC.onSurfaceVariant)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").foregroundStyle(SC.onSurfaceVariant)
                }
                PipelineView(steps: pipeline)
                if let status { StatusPill(tone: status.tone, label: status.stateLabel) }
            }
            .multilineTextAlignment(.leading)
            .scCard()
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title). \(summary). Status: \(status?.stateLabel ?? "unknown"). Workflow: \(pipeline.joined(separator: ", then "))")
        .accessibilityAddTraits(.isButton)
    }

    // MARK: 3. Quick actions

    private func quickActions(_ statuses: [ServiceStatus]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Quick actions")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                quickAction("Scan text", "Camera OCR", "doc.text.viewfinder", .vision) { navigate(.vision) }
                quickAction("Start listening", "Live captions", "mic.fill", .hearing) { navigate(.hearing(autoStart: true)) }
                quickAction("Speak a phrase", "Phrase board", "person.wave.2.fill", .communication) { navigate(.communication) }
                quickAction("Share location", locating ? "Finding location…" : "Send GPS link", "location.fill", .location) { shareLocation() }
                    .disabled(locating)
            }
        }
    }

    private func quickAction(_ title: String, _ subtitle: String, _ symbol: String, _ style: ModuleStyle, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tap()
            action()
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                IconTile(symbol, container: style.container, onContainer: style.onContainer, size: 40)
                Text(title).font(.system(.subheadline, weight: .semibold)).foregroundStyle(SC.onSurface)
                Text(subtitle).font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, minHeight: 112, alignment: .topLeading)
            .scCard(padding: 16)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    private func shareLocation() {
        locating = true
        Task {
            let result = await app.location.currentLocation()
            locating = false
            refreshToken += 1
            switch result {
            case .success(let fix):
                app.activity.log(.location, "Location shared", "Accuracy ±\(Int(fix.accuracy ?? 0)) m")
                Haptics.success()
                shareItem = ShareItem(text: "My current location: \(fix.mapsURL)")
            case .permissionDenied:
                alertMessage = "Allow location so SenseConnect can share where you are."
            case .servicesDisabled:
                alertMessage = "Turn on Location Services in iOS Settings to share where you are."
            case .unavailable(let reason):
                alertMessage = reason
            }
        }
    }

    // MARK: 4. Recent activity

    private var recentActivity: some View {
        VStack(spacing: 0) {
            SectionHeader(title: "Recent activity", trailing: AnyView(Button("View all", action: showActivity).font(.system(.subheadline, weight: .semibold))))
            VStack(spacing: 0) {
                let recent = app.activity.latest(3)
                if recent.isEmpty {
                    ActivityRow(symbol: "clock", style: ModuleStyle(symbol: "clock", container: SC.neutralContainer, onContainer: SC.onNeutralContainer),
                                title: "No activity yet", detail: "Scans, transcriptions and spoken phrases will appear here.", time: nil)
                } else {
                    ForEach(recent) { event in
                        ActivityRow(event: event)
                        if event.id != recent.last?.id { Divider() }
                    }
                }
            }
            .scCard(padding: 14)
        }
    }

    // MARK: 5. Service availability

    private func serviceStatus(_ statuses: [ServiceStatus]) -> some View {
        VStack(spacing: 0) {
            SectionHeader(title: "Service status", trailing: AnyView(
                Button {
                    Haptics.tap()
                    refreshToken += 1
                    Task { await app.refreshCloud() }
                } label: {
                    Image(systemName: "arrow.clockwise").frame(width: 44, height: 44)
                }
                .accessibilityLabel("Refresh service status")
            ))
            VStack(spacing: 0) {
                ForEach(statuses) { status in
                    Button { handleFix(status.fix) } label: {
                        HStack(spacing: 14) {
                            Image(systemName: status.symbol).frame(width: 26).foregroundStyle(SC.onSurfaceVariant)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(status.label).font(.system(.subheadline, weight: .semibold)).foregroundStyle(SC.onSurface)
                                Text(status.detail).font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                            }
                            Spacer(minLength: 8)
                            StatusPill(tone: status.tone, label: status.stateLabel)
                        }
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(status.fix == nil)
                    .accessibilityElement(children: .combine)
                    .accessibilityHint(status.fix == nil ? "" : "Double tap to fix")
                    if status.id != statuses.last?.id { Divider().padding(.leading, 40) }
                }
            }
            .scCard(padding: 14)
        }
    }

    private func handleFix(_ fix: FixAction?) {
        guard let fix else { return }
        Haptics.tap()
        Task {
            switch fix {
            case .requestCamera: _ = await Permissions.requestCamera()
            case .requestMicrophone: _ = await Permissions.requestMicrophoneAndSpeech()
            case .requestLocation: await app.location.requestPermission()
            case .openSettings: Permissions.openAppSettings()
            case .retryCloud: await app.refreshCloud()
            }
            refreshToken += 1
        }
    }

    // MARK: 6. Emergency access

    private var emergencyAccess: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "Emergency access")
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 16) {
                    IconTile("staroflife.fill", container: SC.emergency, onContainer: SC.onEmergency, size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Emergency Assistance").font(.scTitle)
                        Text("Press and hold SOS to alert your emergency contact with your live location.").font(.scSupporting)
                    }
                }
                Button(action: showSettings) {
                    Label(app.settings.hasEmergencyContact
                          ? "Emergency contact: \(app.settings.emergencyContactDisplayName)"
                          : "No emergency contact set - add one in Settings",
                          systemImage: "person.fill")
                        .font(.scStatus)
                }
                .buttonStyle(.plain)
                Button {
                    Haptics.tap()
                    navigate(.sos)
                } label: {
                    Label("Open SOS", systemImage: "staroflife.fill")
                }
                .buttonStyle(PrimaryButtonStyle(background: SC.emergency, foreground: SC.onEmergency))
            }
            .foregroundStyle(SC.onEmergencyContainer)
            .scCard(padding: 20, background: SC.emergencyContainer, border: SC.emergency)
        }
    }
}

/// Shared row used by the dashboard and the Activity screen.
struct ActivityRow: View {
    let symbol: String
    let style: ModuleStyle
    let title: String
    let detail: String
    let time: String?

    init(symbol: String, style: ModuleStyle, title: String, detail: String, time: String?) {
        self.symbol = symbol
        self.style = style
        self.title = title
        self.detail = detail
        self.time = time
    }

    init(event: ActivityEvent) {
        self.init(symbol: event.type.style.symbol, style: event.type.style, title: event.title, detail: event.detail, time: TimeText.relative(event.date))
    }

    var body: some View {
        HStack(spacing: 14) {
            IconTile(symbol, container: style.container, onContainer: style.onContainer, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(.body, weight: .semibold))
                if !detail.isEmpty {
                    Text(detail).font(.scSupporting).foregroundStyle(SC.onSurfaceVariant).lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            if let time { Text(time).font(.scCaption).foregroundStyle(SC.onSurfaceVariant) }
        }
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }
}
