import SwiftUI
import AVFoundation

/// First-launch introduction: what SenseConnect is, its four modules, and why each permission
/// is needed, requested in context rather than all at once.
@MainActor
struct OnboardingView: View {
    let onFinish: () -> Void

    @Environment(AppModel.self) private var app
    @State private var page = 0
    @State private var refreshToken = 0

    private struct Page {
        let symbol: String?
        let style: ModuleStyle?
        let overline: String
        let title: String
        let body: String
        let pipeline: [String]
    }

    private let pages: [Page] = [
        Page(symbol: nil, style: nil, overline: "Welcome to", title: "SenseConnect",
             body: "One accessible companion for people with visual, hearing and speech needs - with emergency help built in.",
             pipeline: ["One Device. Three Disabilities."]),
        Page(symbol: "eye.fill", style: .vision, overline: "See with sound", title: "Vision Assistance",
             body: "Point the camera at printed text. SenseConnect recognises it on the iPhone and reads it aloud.",
             pipeline: ["Camera", "On-device OCR", "Read aloud"]),
        Page(symbol: "ear.fill", style: .hearing, overline: "Read what is said", title: "Hearing Assistance",
             body: "Nearby speech becomes large live captions you can read, copy or share. Type a reply and it is spoken for you.",
             pipeline: ["Microphone", "Speech recognition", "Live captions"]),
        Page(symbol: "person.wave.2.fill", style: .communication, overline: "Be heard", title: "Communication",
             body: "Tap a phrase or type a message and SenseConnect speaks it clearly. Save favourites and your own phrases.",
             pipeline: ["Phrase board", "Text-to-speech", "Spoken aloud"]),
        Page(symbol: "sos", style: .sos, overline: "Help when it matters", title: "Emergency Assistance",
             body: "Press and hold SOS to alert your emergency contact with your live GPS location, call for help or sound an alarm.",
             pipeline: ["GPS location", "Emergency message", "SMS · Call · Share"]),
    ]

    private var pageCount: Int { pages.count + 1 }
    private var isLast: Bool { page == pageCount - 1 }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("STEP \(page + 1) OF \(pageCount)").font(.scStatus).tracking(1.2).foregroundStyle(SC.onSurfaceVariant)
                Spacer()
                if !isLast { Button("Skip") { withAnimation { page = pageCount - 1 } } }
            }
            .padding(.horizontal, 20)
            .frame(minHeight: 52)

            TabView(selection: $page) {
                ForEach(pages.indices, id: \.self) { index in
                    pageView(pages[index]).tag(index)
                }
                permissionsPage.tag(pages.count)
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            HStack(spacing: 12) {
                if page > 0 {
                    Button("Back") { withAnimation { page -= 1 } }
                        .buttonStyle(TonalButtonStyle(background: SC.surfaceContainer, foreground: SC.primary))
                }
                Button(isLast ? "Get started" : "Next") {
                    Haptics.tap()
                    if isLast { onFinish() } else { withAnimation { page += 1 } }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(20)
        }
        .background(SC.background)
    }

    private func pageView(_ page: Page) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer(minLength: 24)
                Group {
                    if let symbol = page.symbol, let style = page.style {
                        Image(systemName: symbol)
                            .font(.system(size: 56, weight: .semibold))
                            .foregroundStyle(style.onContainer)
                            .frame(width: 132, height: 132)
                            .background(style.container, in: Circle())
                    } else {
                        BrandBadge(size: 132)
                    }
                }
                .accessibilityHidden(true)
                Text(page.overline.uppercased()).font(.scStatus).tracking(1.4).foregroundStyle(page.style?.onContainer ?? SC.primary)
                    .padding(.top, 16)
                Text(page.title).font(.scDisplay).multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                Text(page.body).font(.scBody).foregroundStyle(SC.onSurfaceVariant).multilineTextAlignment(.center)
                PipelineView(steps: page.pipeline).padding(.top, 8)
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 48)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: Permissions

    private var permissionsPage: some View {
        let _ = refreshToken
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("PERMISSIONS").font(.scStatus).tracking(1.2).foregroundStyle(SC.primary)
                Text("Allow what you need").font(.scScreenTitle).accessibilityAddTraits(.isHeader)
                Text("Each permission powers one assistive service. You can skip any of them and allow it later from Settings.")
                    .font(.scSupporting).foregroundStyle(SC.onSurfaceVariant)
                permissionCard(.vision, "camera.fill", "Camera", "Used to recognise and read text.",
                               granted: Permissions.cameraStatus == .authorized) { _ = await Permissions.requestCamera() }
                permissionCard(.hearing, "mic.fill", "Microphone & speech", "Used to convert speech into readable text.",
                               granted: Permissions.microphoneGranted && Permissions.speechStatus == .authorized) {
                    _ = await Permissions.requestMicrophoneAndSpeech()
                }
                permissionCard(.sos, "location.fill", "Location", "Used to share your location during emergency assistance.",
                               granted: app.location.isAuthorized) { await app.location.requestPermission() }
                Label("Camera images and microphone audio are processed on your iPhone and never stored.", systemImage: "lock.fill")
                    .font(.scCaption).foregroundStyle(SC.onSurfaceVariant)
            }
            .padding(20)
            .padding(.bottom, 32)
        }
    }

    private func permissionCard(_ style: ModuleStyle, _ symbol: String, _ title: String, _ why: String,
                                granted: Bool, request: @escaping () async -> Void) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                IconTile(symbol, container: style.container, onContainer: style.onContainer)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.scTitle)
                    Text(why).font(.scSupporting).foregroundStyle(SC.onSurfaceVariant)
                }
            }
            HStack {
                Spacer()
                StatusPill(tone: granted ? .success : .neutral, label: granted ? "Allowed" : "Not yet allowed")
                if !granted {
                    Button("Allow") {
                        Task {
                            await request()
                            refreshToken += 1
                            Haptics.tap()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityLabel("Allow \(title) access")
                }
            }
        }
        .scCard()
    }
}
