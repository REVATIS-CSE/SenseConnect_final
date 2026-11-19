import SwiftUI

/// Activity history: what SenseConnect helped with, grouped by day and filterable by module.
@MainActor
struct ActivityView: View {
    @Environment(AppModel.self) private var app
    @State private var filter: ActivityType?
    @State private var confirmClear = false

    var body: some View {
        let all = app.activity.events
        let events = filter.map { f in all.filter { $0.type == f } } ?? all
        let groups = Dictionary(grouping: events) { Calendar.current.startOfDay(for: $0.date) }
            .sorted { $0.key > $1.key }

        List {
            Section {
                weeklySummary(all)
                filterChips
            }
            .listRowSeparator(.hidden)

            if events.isEmpty {
                StateMessage(symbol: "clock.arrow.circlepath",
                             title: all.isEmpty ? "No activity yet" : "Nothing in this category",
                             message: "Use Vision, Hearing, Communication or SOS and a summary will appear here.")
                    .listRowBackground(Color.clear)
            } else {
                ForEach(groups, id: \.key) { group in
                    Section(TimeText.dayLabel(group.key)) {
                        ForEach(group.value) { ActivityRow(event: $0) }
                    }
                }
                Section {
                    Label("Stored only on this iPhone and excluded from iCloud backup. Images, audio, transcripts and scanned text are never saved - only short summaries.",
                          systemImage: "lock.fill")
                        .font(.scCaption)
                        .foregroundStyle(SC.onSurfaceVariant)
                }
            }
        }
        .navigationTitle("Activity")
        .toolbar {
            Button("Clear", systemImage: "trash") { confirmClear = true }
                .disabled(all.isEmpty)
        }
        .confirmationDialog("Clear activity history?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear history", role: .destructive) {
                app.activity.clear()
                Haptics.success()
            }
        } message: {
            Text("All activity summaries on this iPhone will be permanently removed.")
        }
    }

    private func weeklySummary(_ events: [ActivityEvent]) -> some View {
        let weekAgo = Date().addingTimeInterval(-7 * 24 * 3600)
        let recent = events.filter { $0.date >= weekAgo }
        return HStack {
            ForEach([ActivityType.vision, .hearing, .communication, .sos]) { type in
                let count = recent.filter { $0.type == type }.count
                VStack(spacing: 4) {
                    IconTile(module: type.style, size: 36)
                    Text("\(count)").font(.system(.title3, weight: .semibold))
                    Text(type.label).font(.scCaption).foregroundStyle(SC.onSurfaceVariant).lineLimit(1).minimumScaleFactor(0.6)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(count) \(type.label) events this week")
            }
        }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("All", selected: filter == nil) { filter = nil }
                ForEach(ActivityType.allCases) { type in
                    chip(type.label, selected: filter == type) { filter = type }
                }
            }
        }
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            Haptics.tick()
            action()
        } label: {
            Text(title)
                .font(.system(.subheadline, weight: .semibold))
                .padding(.horizontal, 14)
                .frame(minHeight: 40)
                .foregroundStyle(selected ? SC.onSecondaryContainer : SC.onSurface)
                .background(selected ? SC.secondaryContainer : Color.clear, in: Capsule())
                .overlay(Capsule().strokeBorder(selected ? Color.clear : SC.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
