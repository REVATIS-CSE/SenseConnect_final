import SwiftUI
import UIKit

// MARK: - Card

struct CardStyle: ViewModifier {
    @Environment(\.scHighContrast) private var highContrast
    var padding: CGFloat = 18
    var background: Color = SC.surface
    var border: Color? = nil

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(background, in: shape)
            .overlay(
                shape.strokeBorder(border ?? (highContrast ? SC.onSurface : SC.outlineVariant), lineWidth: highContrast ? 2 : 1)
            )
    }
}

extension View {
    func scCard(padding: CGFloat = 18, background: Color = SC.surface, border: Color? = nil) -> some View {
        modifier(CardStyle(padding: padding, background: background, border: border))
    }
}

// MARK: - Section header

struct SectionHeader: View {
    let title: String
    var trailing: AnyView? = nil

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(.scStatus)
                .tracking(1.2)
                .foregroundStyle(SC.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if let trailing { trailing }
        }
        .padding(.top, 24)
        .padding(.bottom, 4)
    }
}

// MARK: - Status pill

struct StatusPill: View {
    let tone: Tone
    let label: String

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(tone.accent).frame(width: 8, height: 8)
            Text(label).lineLimit(1)
        }
        .font(.scStatus)
        .foregroundStyle(tone.onContainer)
        .padding(.horizontal, 11)
        .padding(.vertical, 5)
        .background(tone.container, in: Capsule())
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Icon tile

struct IconTile: View {
    let symbol: String
    let container: Color
    let onContainer: Color
    var size: CGFloat = 48

    init(_ symbol: String, container: Color, onContainer: Color, size: CGFloat = 48) {
        self.symbol = symbol
        self.container = container
        self.onContainer = onContainer
        self.size = size
    }

    init(module: ModuleStyle, size: CGFloat = 48) {
        self.init(module.symbol, container: module.container, onContainer: module.onContainer, size: size)
    }

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(onContainer)
            .frame(width: size, height: size)
            .background(container, in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
            .accessibilityHidden(true)
    }
}

// MARK: - Workflow pipeline (Camera → OCR → Speech)

struct PipelineView: View {
    let steps: [String]

    var body: some View {
        FlowLayout(spacing: 6) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(spacing: 6) {
                    if index > 0 {
                        Image(systemName: "arrow.right").font(.caption2.weight(.bold))
                    }
                    Text(step)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(SC.surfaceContainer, in: Capsule())
                }
                .font(.scCaption)
                .foregroundStyle(SC.onSurfaceVariant)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Workflow: " + steps.joined(separator: ", then "))
    }
}

/// Wrapping horizontal layout that keeps chips readable at large text sizes.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > maxWidth {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return CGSize(width: min(widest, maxWidth), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: - Brand mark: one central device connected to three senses

struct BrandMark: View {
    var color: Color = .white

    var body: some View {
        Canvas { context, size in
            // Drawn in a 60×60 design space (same geometry as the Android vector).
            let scale = min(size.width, size.height) / 60
            func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: (x - 24) * scale, y: (y - 24) * scale) }
            func circle(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat) -> Path {
                Path(ellipseIn: CGRect(x: (x - 24 - r) * scale, y: (y - 24 - r) * scale, width: 2 * r * scale, height: 2 * r * scale))
            }
            let shading = GraphicsContext.Shading.color(color)
            context.stroke(circle(54, 54, 22), with: .color(color.opacity(0.45)), lineWidth: 2.5 * scale)
            var spokes = Path()
            for end in [point(54, 32), point(73.05, 65), point(34.95, 65)] {
                spokes.move(to: point(54, 54))
                spokes.addLine(to: end)
            }
            context.stroke(spokes, with: shading, style: StrokeStyle(lineWidth: 3 * scale, lineCap: .round))
            for (x, y) in [(54.0, 32.0), (73.05, 65.0), (34.95, 65.0)] {
                context.fill(circle(x, y, 6.5), with: shading)
            }
            context.fill(circle(54, 54, 8.5), with: shading)
            context.blendMode = .clear
            context.fill(circle(54, 54, 3.5), with: shading)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

struct BrandBadge: View {
    var size: CGFloat = 40
    var body: some View {
        BrandMark()
            .padding(size * 0.16)
            .frame(width: size, height: size)
            .background(SC.brandGradient, in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
    }
}

// MARK: - Empty / error state

struct StateMessage: View {
    let symbol: String
    let title: String
    let message: String
    var tone: Tone = .neutral
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 10) {
            IconTile(symbol, container: tone.container, onContainer: tone.onContainer, size: 56)
            Text(title).font(.scTitle).multilineTextAlignment(.center)
            Text(message).font(.scSupporting).foregroundStyle(SC.onSurfaceVariant).multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(actionTitle, action: action).buttonStyle(.bordered).padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .accessibilityElement(children: .contain)
    }
}

// MARK: - Buttons

struct PrimaryButtonStyle: ButtonStyle {
    var background: Color = SC.primary
    var foreground: Color = SC.onPrimary
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.body, weight: .semibold))
            .frame(maxWidth: .infinity, minHeight: 56)
            .padding(.horizontal, 16)
            .foregroundStyle(foreground)
            .background(background.opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct TonalButtonStyle: ButtonStyle {
    var background: Color = SC.secondaryContainer
    var foreground: Color = SC.onSecondaryContainer
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.body, weight: .semibold))
            .frame(maxWidth: .infinity, minHeight: 52)
            .padding(.horizontal, 12)
            .foregroundStyle(foreground)
            .background(background.opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.4),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

// MARK: - UIKit share sheet (for content produced asynchronously)

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Identifiable wrapper so a String can drive `.sheet(item:)`.
struct ShareItem: Identifiable {
    let id = UUID()
    let text: String
}
