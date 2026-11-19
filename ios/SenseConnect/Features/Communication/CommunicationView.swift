import SwiftUI

/// Communication board: choose or type a message and SenseConnect speaks it aloud.
@MainActor
struct CommunicationView: View {
    @Environment(AppModel.self) private var app
    @State private var category: PhraseCategory = .favorites
    @State private var message = ""
    @State private var selected: Phrase?
    @State private var typed = ""
    @State private var showFullScreen = false
    @State private var addingPhrase = false
    @State private var newPhrase = ""
    @State private var pendingDelete: Phrase?
    @State private var toast: String?

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    messageCard
                    quickResponses
                    categoryChips
                    phraseGrid
                }
                .padding(16)
            }
            typeBar
        }
        .background(SC.background)
        .navigationTitle("Communication")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button { addingPhrase = true } label: { Image(systemName: "plus") }
                .accessibilityLabel("Add phrase")
        }
        .onAppear {
            if app.phrases.phrases(in: .favorites).isEmpty { category = .emergency }
        }
        .onDisappear { app.speech.stop() }
        .fullScreenCover(isPresented: $showFullScreen) { FullScreenMessage(text: message) }
        .alert("Add phrase", isPresented: $addingPhrase) {
            TextField("Phrase", text: $newPhrase)
            Button("Cancel", role: .cancel) { newPhrase = "" }
            Button("Save") {
                app.phrases.addCustom(newPhrase)
                newPhrase = ""
                category = .custom
            }
        } message: {
            Text("Saved phrases stay on this iPhone.")
        }
        .confirmationDialog("Delete this phrase?", isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }), titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let phrase = pendingDelete {
                    app.phrases.removeCustom(phrase)
                    if selected?.id == phrase.id { selected = nil }
                }
            }
        } message: {
            Text(pendingDelete?.text ?? "")
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.scStatus)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 110)
                    .transition(.opacity)
            }
        }
    }

    // MARK: Message display

    private var messageCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MESSAGE").font(.scStatus).tracking(1.2)
            Text(message.isEmpty ? "Tap a phrase or type a message" : message)
                .font(.scLiveCaption)
                .opacity(message.isEmpty ? 0.65 : 1)
                .frame(maxWidth: .infinity, minHeight: 60, alignment: .topLeading)
                .accessibilityAddTraits(.updatesFrequently)
            HStack(spacing: 8) {
                Button {
                    if app.speech.isSpeaking { app.speech.stop() } else { speak() }
                } label: {
                    Label(app.speech.isSpeaking ? "Stop" : "Speak", systemImage: app.speech.isSpeaking ? "stop.fill" : "speaker.wave.2.fill")
                }
                .buttonStyle(PrimaryButtonStyle(background: SC.tertiary, foreground: SC.onTertiary))

                iconButton("textformat.size", label: "Show full screen") { showFullScreen = true }
                    .disabled(message.isEmpty)
                iconButton("doc.on.doc", label: "Copy") {
                    UIPasteboard.general.string = message
                    Haptics.success()
                    flash("Copied to clipboard")
                }
                .disabled(message.isEmpty)
                if let selected {
                    let favourite = app.phrases.isFavorite(selected)
                    iconButton(favourite ? "heart.fill" : "heart", label: favourite ? "Remove from favourites" : "Add to favourites") {
                        toggleFavorite(selected)
                    }
                }
            }
        }
        .foregroundStyle(SC.onTertiaryContainer)
        .scCard(background: SC.tertiaryContainer, border: Color.clear)
    }

    private func iconButton(_ symbol: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.title3).frame(width: 52, height: 52)
        }
        .buttonStyle(.bordered)
        .accessibilityLabel(label)
    }

    // MARK: Quick responses

    private var quickResponses: some View {
        HStack(spacing: 8) {
            quickResponse("rs_yes", "Yes", "hand.thumbsup.fill", SC.successContainer, SC.onSuccessContainer)
            quickResponse("rs_no", "No", "hand.thumbsdown.fill", SC.emergencyContainer, SC.onEmergencyContainer)
            quickResponse("rs_wait", "Wait", "hourglass", SC.secondaryContainer, SC.onSecondaryContainer)
        }
    }

    private func quickResponse(_ id: String, _ title: String, _ symbol: String, _ background: Color, _ foreground: Color) -> some View {
        Button {
            guard let phrase = app.phrases.phrase(id: id) else { return }
            select(phrase)
            speak() // quick responses always speak immediately
        } label: {
            Label(title, systemImage: symbol)
        }
        .buttonStyle(TonalButtonStyle(background: background, foreground: foreground))
    }

    // MARK: Categories & grid

    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(PhraseCategory.allCases) { item in
                    let isSelected = item == category
                    Button {
                        Haptics.tick()
                        category = item
                    } label: {
                        Label(item.label, systemImage: item.symbol)
                            .font(.system(.subheadline, weight: .semibold))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .foregroundStyle(isSelected ? SC.onSecondaryContainer : SC.onSurface)
                            .background(isSelected ? SC.secondaryContainer : SC.surface, in: Capsule())
                            .overlay(Capsule().strokeBorder(isSelected ? Color.clear : SC.outline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }
        }
    }

    @ViewBuilder
    private var phraseGrid: some View {
        let phrases = app.phrases.phrases(in: category)
        if phrases.isEmpty {
            StateMessage(
                symbol: category == .custom ? "pencil" : "heart",
                title: category == .custom ? "No saved phrases" : "No favourites yet",
                message: category == .custom ? "Add phrases you use often, like your name or medical needs." : "Long-press any phrase to add it to favourites.",
                tone: .info,
                actionTitle: category == .custom ? "Add phrase" : nil
            ) { addingPhrase = true }
        } else {
            LazyVGrid(columns: columns, spacing: 10) {
                ForEach(phrases) { phrase in phraseCard(phrase) }
            }
        }
    }

    private func phraseCard(_ phrase: Phrase) -> some View {
        let isSelected = selected?.id == phrase.id
        let favourite = app.phrases.isFavorite(phrase)
        let tone = phrase.category.tone
        return Button {
            Haptics.tap()
            select(phrase)
            if app.settings.speakOnTap { speak() }
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    IconTile(phrase.symbol, container: tone.container, onContainer: tone.onContainer, size: 36)
                    Spacer()
                    if favourite { Image(systemName: "heart.fill").foregroundStyle(SC.emergency).font(.footnote) }
                }
                Text(phrase.text).font(.system(.body, weight: .semibold)).foregroundStyle(SC.onSurface)
                    .multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, minHeight: 96, alignment: .topLeading)
            .scCard(padding: 14, border: isSelected ? SC.tertiary : nil)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(favourite ? "Remove from favourites" : "Add to favourites", systemImage: favourite ? "heart.slash" : "heart") {
                toggleFavorite(phrase)
            }
            if phrase.isCustom {
                Button("Delete", systemImage: "trash", role: .destructive) { pendingDelete = phrase }
            }
        }
        .accessibilityLabel(phrase.text + (favourite ? ", favourite" : "") + (isSelected ? ", selected" : ""))
        .accessibilityAction(named: favourite ? "Remove from favourites" : "Add to favourites") { toggleFavorite(phrase) }
    }

    // MARK: Typed message

    private var typeBar: some View {
        HStack(spacing: 8) {
            TextField("Type your own message", text: $typed, axis: .vertical)
                .lineLimit(1...3)
                .submitLabel(.done)
                .onSubmit(useTyped)
                .padding(14)
                .background(SC.surface, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(SC.outline, lineWidth: 1))
            Button(action: useTyped) {
                Image(systemName: "speaker.wave.2.fill").frame(width: 56, height: 56)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityLabel("Speak typed message")
        }
        .padding(16)
        .background(SC.surface)
    }

    // MARK: Actions

    private func select(_ phrase: Phrase) {
        selected = phrase
        message = phrase.text
    }

    private func useTyped() {
        let text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        selected = nil
        message = text
        typed = ""
        speak()
    }

    private func speak() {
        guard !message.isEmpty, app.speech.speak(message) else { return }
        Haptics.success()
        // Typed free text is private: only phrases from the board are written to history.
        if let selected {
            app.activity.log(.communication, "Phrase spoken", selected.text)
        } else {
            app.activity.log(.communication, "Typed message spoken", "\(message.split(separator: " ").count) words")
        }
    }

    private func toggleFavorite(_ phrase: Phrase) {
        let nowFavourite = app.phrases.toggleFavorite(phrase)
        Haptics.tap()
        flash(nowFavourite ? "Added to favourites" : "Removed from favourites")
    }

    private func flash(_ text: String) {
        withAnimation { toast = text }
        Task {
            try? await Task.sleep(for: .seconds(1.6))
            withAnimation { toast = nil }
        }
    }
}

/// Large high-contrast display so the message can be shown to someone in a noisy place.
struct FullScreenMessage: View {
    let text: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Text(text)
                .font(.system(size: 48, weight: .semibold))
                .minimumScaleFactor(0.3)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white)
                .padding(32)
        }
        .onTapGesture { dismiss() }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .accessibilityElement(children: .combine)
        .accessibilityHint("Double tap to close")
        .accessibilityAction { dismiss() }
    }
}
