import SwiftUI
import AVFoundation
import CoreLocation
import MessageUI

/// Accessibility, speech, emergency, permission and cloud preferences. Every control is live.
@MainActor
struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var editingMessage = false
    @State private var testingCloud = false
    @State private var cloudResult: String?
    @State private var replayIntro = false
    @State private var refreshToken = 0

    var body: some View {
        @Bindable var settings = app.settings
        let _ = refreshToken

        Form {
            Section("Accessibility") {
                Picker("Text size", selection: $settings.textSize) {
                    ForEach(TextSize.allCases) { Text($0.label).tag($0) }
                }
                Text("Applied on top of your iOS text size.").font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                Picker("Theme", selection: $settings.theme) {
                    ForEach(ThemeMode.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                toggle("High contrast", "Stronger text, borders and backgrounds", "circle.lefthalf.filled", $settings.highContrast)
                toggle("Reduce motion", "Turn off scanning and level animations", "figure.walk.motion", $settings.reduceMotion)
                toggle("Voice feedback", "Spoken confirmations such as “12 words recognised”", "person.wave.2", $settings.voiceFeedback)
                toggle("Haptic feedback", "Vibrate on taps, confirmations and SOS", "iphone.radiowaves.left.and.right", $settings.haptics)
            }

            Section {
                VStack(alignment: .leading) {
                    LabeledContent("Speech rate", value: String(format: "%.1f×", settings.speechRate))
                    Slider(value: $settings.speechRate, in: 0.5...2.0, step: 0.1).accessibilityLabel("Speech rate")
                }
                VStack(alignment: .leading) {
                    LabeledContent("Voice pitch", value: String(format: "%.1f×", settings.speechPitch))
                    Slider(value: $settings.speechPitch, in: 0.5...2.0, step: 0.1).accessibilityLabel("Voice pitch")
                }
                Button {
                    app.speech.speak("Hello. This is SenseConnect, your assistive companion.")
                } label: {
                    Label("Test voice", systemImage: "speaker.wave.2.fill")
                }
                toggle("Speak phrases on tap", "Communication phrases are spoken immediately", "text.bubble", $settings.speakOnTap)
                toggle("Auto-read scanned text", "Read recognised text aloud as soon as a scan completes", "doc.text.viewfinder", $settings.autoReadScans)
            } header: {
                Text("Speech")
            } footer: {
                Text("Voice: \(app.speech.voiceDescription), on-device. Download higher-quality voices in iOS Settings › Accessibility › Spoken Content › Voices.")
            }

            Section {
                NavigationLink {
                    EmergencyContactEditor()
                } label: {
                    HStack {
                        Label("Emergency contact", systemImage: "person.fill")
                        Spacer()
                        StatusPill(tone: settings.hasEmergencyContact ? .success : .warning,
                                   label: settings.hasEmergencyContact ? settings.emergencyContactDisplayName : "Not configured")
                    }
                }
                Button { editingMessage = true } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Label("Emergency message", systemImage: "message.fill").foregroundStyle(SC.onSurface)
                        Text(settings.emergencyMessage).font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                    }
                }
                toggle("Log SOS to SenseConnect Cloud", "Records time and location of an SOS on the server (deleted after 24 h)", "icloud", $settings.cloudLogging)
            } header: {
                Text("Emergency")
            } footer: {
                Text(MFMessageComposeViewController.canSendText()
                     ? "On iPhone, SOS opens Messages pre-filled with your location; you tap Send. iOS does not allow apps to send SMS automatically."
                     : "This device cannot send SMS. SOS can still call, share your location and sound an alarm.")
            }

            Section("Permissions") {
                permissionRow("Camera", "Used to recognise and read text.", "camera.fill", granted: Permissions.cameraStatus == .authorized,
                              undetermined: Permissions.cameraStatus == .notDetermined) { _ = await Permissions.requestCamera() }
                permissionRow("Microphone & speech", "Used to convert speech into readable text.", "mic.fill",
                              granted: Permissions.microphoneGranted && Permissions.speechStatus == .authorized,
                              undetermined: AVAudioApplication.shared.recordPermission == .undetermined || Permissions.speechStatus == .notDetermined) {
                    _ = await Permissions.requestMicrophoneAndSpeech()
                }
                permissionRow("Location", "Used to share your location during emergency assistance.", "location.fill",
                              granted: app.location.isAuthorized, undetermined: app.location.authorization == .notDetermined) {
                    await app.location.requestPermission()
                }
            }

            Section("Cloud & about") {
                Button {
                    testingCloud = true
                    Task {
                        await app.refreshCloud()
                        testingCloud = false
                        switch app.backend.status {
                        case .online(let ms, let version): cloudResult = "Connected to SenseConnect Cloud in \(ms) ms (API v\(version))."
                        case .offline(let reason): cloudResult = "Cloud unreachable: \(reason)."
                        default: cloudResult = nil
                        }
                    }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Label("SenseConnect Cloud", systemImage: "checkmark.icloud").foregroundStyle(SC.onSurface)
                            Text("\(app.backend.baseURLDescription) · tap to test").font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                        }
                        Spacer()
                        if testingCloud { ProgressView() } else { cloudPill }
                    }
                }
                if let cloudResult { Text(cloudResult).font(.scCaption) }

                HStack(spacing: 14) {
                    BrandBadge(size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("SenseConnect").font(.scTitle)
                        Text("Version \(AppConfig.version) (build \(AppConfig.build))").font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                        Text("One Device. Three Disabilities.").font(.scStatus).foregroundStyle(SC.primary)
                    }
                }
                Text("SenseConnect brings vision, hearing and speech assistance together with emergency support. Text recognition, speech recognition, text-to-speech and GPS run on the iPhone; the SenseConnect Cloud adds health monitoring, configuration and emergency incident logging.")
                    .font(.scCaption)
                    .foregroundStyle(SC.onSurfaceVariant)
                Button { replayIntro = true } label: { Label("Replay introduction", systemImage: "play.circle") }
            }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $editingMessage) { NavigationStack { EmergencyMessageEditor() } }
        .fullScreenCover(isPresented: $replayIntro) { OnboardingView { replayIntro = false } }
        .onChange(of: scenePhase) { _, phase in if phase == .active { refreshToken += 1 } }
    }

    private var cloudPill: some View {
        if !app.network.isOnline { return StatusPill(tone: .neutral, label: "Offline") }
        switch app.backend.status {
        case .online(let ms, _): return StatusPill(tone: .success, label: "Online · \(ms) ms")
        case .waking: return StatusPill(tone: .info, label: "Waking server…")
        case .offline: return StatusPill(tone: .warning, label: "Unreachable")
        case .unknown, .checking: return StatusPill(tone: .info, label: "Connecting…")
        }
    }

    private func toggle(_ title: String, _ subtitle: String, _ symbol: String, _ binding: Binding<Bool>) -> some View {
        Toggle(isOn: binding) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    Text(subtitle).font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                }
            } icon: {
                Image(systemName: symbol)
            }
        }
        .onChange(of: binding.wrappedValue) { _, _ in Haptics.tick() }
    }

    private func permissionRow(_ title: String, _ why: String, _ symbol: String, granted: Bool, undetermined: Bool,
                               request: @escaping () async -> Void) -> some View {
        Button {
            Task {
                if undetermined { await request() } else { Permissions.openAppSettings() }
                refreshToken += 1
            }
        } label: {
            HStack {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).foregroundStyle(SC.onSurface)
                        Text(why).font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
                    }
                } icon: { Image(systemName: symbol) }
                Spacer()
                StatusPill(tone: granted ? .success : .warning, label: granted ? "Allowed" : "Not allowed")
            }
        }
        .accessibilityHint(granted ? "Opens iOS Settings" : (undetermined ? "Requests permission" : "Opens iOS Settings to allow access"))
    }
}

/// Emergency contact: pick from Contacts (no Contacts permission needed) or type it in.
@MainActor
struct EmergencyContactEditor: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var phone = ""
    @State private var picking = false
    @State private var loaded = false

    var body: some View {
        Form {
            Section {
                Button { picking = true } label: { Label("Choose from Contacts", systemImage: "person.crop.circle.badge.plus") }
            } footer: {
                Text("Only the contact you pick is read.")
            }
            Section("Or enter manually") {
                TextField("Name", text: $name).textContentType(.name)
                TextField("Phone number", text: $phone).keyboardType(.phonePad).textContentType(.telephoneNumber)
            }
            if app.settings.hasEmergencyContact {
                Section {
                    Button("Remove emergency contact", role: .destructive) {
                        app.settings.emergencyContactName = ""
                        app.settings.emergencyContactPhone = ""
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle("Emergency contact")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    app.settings.emergencyContactName = name.trimmingCharacters(in: .whitespaces)
                    app.settings.emergencyContactPhone = phone.filter { $0.isNumber || $0 == "+" }
                    Haptics.success()
                    dismiss()
                }
                .disabled(phone.filter { $0.isNumber }.isEmpty)
            }
        }
        .sheet(isPresented: $picking) {
            ContactPicker { pickedName, pickedPhone in
                name = pickedName
                phone = pickedPhone
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            name = app.settings.emergencyContactName
            phone = app.settings.emergencyContactPhone
        }
    }
}

struct EmergencyMessageEditor: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        Form {
            Section {
                TextField("Emergency message", text: $text, axis: .vertical).lineLimit(3...6)
            } footer: {
                Text("Your location link and the time are added automatically.")
            }
            Section {
                Button("Reset to default") { text = SettingsStore.defaultEmergencyMessage }
            }
        }
        .navigationTitle("Emergency message")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    app.settings.emergencyMessage = clean.isEmpty ? SettingsStore.defaultEmergencyMessage : clean
                    dismiss()
                }
            }
        }
        .onAppear { text = app.settings.emergencyMessage }
    }
}
