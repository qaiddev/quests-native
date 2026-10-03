#if os(iOS)
import SwiftUI
import UIKit

/// The resolved theme for one appearance, in SwiftUI terms. Text sizes follow Dynamic
/// Type through `scale`; paddings and radii stay in points, as the theme gives them.
struct QuestLook {
    let palette: QuestPalette
    let metrics: QuestMetrics
    let font: QuestFontChoice
    let scale: CGFloat

    init(style: QuestStyle, systemDark: Bool, scale: CGFloat) {
        palette = style.palette(systemDark: systemDark)
        metrics = style.metrics
        font = style.font { !UIFont.fontNames(forFamilyName: $0).isEmpty }
        self.scale = scale
    }

    func color(_ color: QuestColor) -> Color {
        Color(.sRGB, red: color.red, green: color.green, blue: color.blue, opacity: color.alpha)
    }

    /// `em` of the theme's base size (16 by default), scaled for Dynamic Type.
    func font(_ em: Double, weight: Font.Weight = .regular) -> Font {
        let size = CGFloat(metrics.fontSize * em) * scale
        switch font {
        case .family(let name): return Font.custom(name, fixedSize: size).weight(weight)
        case .system(let design): return Font.system(size: size, weight: weight, design: Self.design(design))
        }
    }

    var buttonFont: Font {
        font(metrics.buttonFontSize / max(metrics.fontSize, 1), weight: Self.weight(metrics.buttonFontWeight))
    }

    static func design(_ design: QuestFontDesign) -> Font.Design {
        switch design {
        case .standard: return .default
        case .serif: return .serif
        case .monospaced: return .monospaced
        case .rounded: return .rounded
        }
    }

    static func weight(_ value: Int) -> Font.Weight {
        switch value {
        case ..<150: return .ultraLight
        case ..<250: return .thin
        case ..<350: return .light
        case ..<450: return .regular
        case ..<550: return .medium
        case ..<650: return .semibold
        case ..<750: return .bold
        case ..<850: return .heavy
        default: return .black
        }
    }

    func insets(_ insets: QuestInsets) -> EdgeInsets {
        EdgeInsets(top: insets.vertical, leading: insets.horizontal, bottom: insets.vertical, trailing: insets.horizontal)
    }
}

/// Primary and secondary buttons: the theme's colours, radius and padding, at least
/// 44 points tall.
struct QuestButtonStyle: ButtonStyle {
    let look: QuestLook
    let primary: Bool

    func makeBody(configuration: Configuration) -> some View {
        let palette = look.palette
        let shape = RoundedRectangle(cornerRadius: look.metrics.buttonRadius, style: .continuous)
        return configuration.label
            .font(look.buttonFont)
            .foregroundColor(look.color(primary ? palette.primaryForeground : palette.secondaryForeground))
            .padding(look.insets(look.metrics.buttonPadding))
            .frame(minHeight: 44)
            .background(shape.fill(look.color(primary ? palette.primaryBackground : palette.secondaryBackground)))
            .overlay(shape.strokeBorder(look.color(primary ? palette.primaryBorder : palette.secondaryBorder), lineWidth: 1))
            .contentShape(shape)
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Option images, loaded once per URL and kept in memory for the session.
@MainActor
final class QuestImageCache {
    static let shared = QuestImageCache()
    private let cache = NSCache<NSURL, UIImage>()

    func image(for url: URL) async -> UIImage? {
        if let cached = cache.object(forKey: url as NSURL) { return cached }
        guard let (data, _) = try? await URLSession.shared.data(from: url), let image = UIImage(data: data) else {
            return nil
        }
        cache.setObject(image, forKey: url as NSURL)
        return image
    }
}

struct QuestRemoteImage: View {
    let url: URL
    let placeholder: Color
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            placeholder
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            }
        }
        .clipped()
        .accessibilityHidden(true)
        .task(id: url) { image = await QuestImageCache.shared.image(for: url) }
    }
}
#endif
