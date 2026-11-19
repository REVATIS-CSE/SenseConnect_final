import SwiftUI

@main
struct SenseConnectApp: App {
    @State private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .task { await app.start() }
        }
    }
}

/// Applies accessibility preferences app-wide and routes between onboarding and the main app.
struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.colorSchemeContrast) private var systemContrast

    var body: some View {
        let settings = app.settings
        Group {
            if settings.onboardingComplete {
                MainTabView()
            } else {
                OnboardingView { settings.onboardingComplete = true }
            }
        }
        .preferredColorScheme(settings.theme.colorScheme)
        .dynamicTypeSize(settings.textSize.range)
        .environment(\.scHighContrast, settings.highContrast || systemContrast == .increased)
        .tint(SC.primary)
    }
}

enum AppTab: Hashable { case home, activity, settings }

struct MainTabView: View {
    @State private var selection: AppTab = .home
    @State private var homePath: [Route] = []

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack(path: $homePath) {
                HomeView(
                    navigate: { homePath.append($0) },
                    showActivity: { selection = .activity },
                    showSettings: { selection = .settings }
                )
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .vision: VisionView()
                    case .hearing(let autoStart): HearingView(autoStart: autoStart)
                    case .communication: CommunicationView()
                    case .sos: SOSView()
                    }
                }
            }
            .tabItem { Label("Home", systemImage: "house.fill") }
            .tag(AppTab.home)

            NavigationStack { ActivityView() }
                .tabItem { Label("Activity", systemImage: "clock.arrow.circlepath") }
                .tag(AppTab.activity)

            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(AppTab.settings)
        }
    }
}
