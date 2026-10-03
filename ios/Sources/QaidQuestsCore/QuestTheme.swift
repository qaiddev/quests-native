import Foundation

// A saved quest theme is a set of CSS custom properties. This file reads the small
// part of CSS those values use and turns a theme into the native style the views
// draw with. The theme's free `css` and its `unstyled` flag have no native analogue
// and are ignored: native controls are always drawn by the SDK.

package struct QuestColor: Equatable, Sendable {
    /// sRGB components and alpha, 0...1.
    package var red: Double
    package var green: Double
    package var blue: Double
    package var alpha: Double

    package init(red: Double, green: Double, blue: Double, alpha: Double = 1) {
        self.red = red
        self.green = green
        self.blue = blue
        self.alpha = alpha
    }

    package static let black = QuestColor(red: 0, green: 0, blue: 0)
    package static let white = QuestColor(red: 1, green: 1, blue: 1)
    package static let clear = QuestColor(red: 0, green: 0, blue: 0, alpha: 0)

    package func opacity(_ factor: Double) -> QuestColor {
        QuestColor(red: red, green: green, blue: blue, alpha: alpha * factor)
    }

    /// WCAG relative luminance, the web embed's `getLuminance`.
    package var luminance: Double {
        func linear(_ c: Double) -> Double { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }
}

package struct QuestShadow: Equatable, Sendable {
    package var x: Double
    package var y: Double
    package var blur: Double
    package var color: QuestColor
}

package struct QuestInsets: Equatable, Sendable {
    package var vertical: Double
    package var horizontal: Double
}

/// Every colour the views use, for one appearance.
package struct QuestPalette: Equatable, Sendable {
    package var accent: QuestColor
    package var error: QuestColor
    package var focus: QuestColor
    package var cardBackground: QuestColor
    package var cardForeground: QuestColor
    package var cardBorder: QuestColor
    package var cardShadow: QuestShadow?
    package var muted: QuestColor
    package var progressTrack: QuestColor
    package var primaryBackground: QuestColor
    package var primaryForeground: QuestColor
    package var primaryBorder: QuestColor
    package var secondaryBackground: QuestColor
    package var secondaryForeground: QuestColor
    package var secondaryBorder: QuestColor
    package var optionBackground: QuestColor
    package var optionSelectedBackground: QuestColor
    package var optionForeground: QuestColor
    package var optionBorder: QuestColor
    package var optionSelectedBorder: QuestColor
    package var markerBackground: QuestColor
    package var markerSelectedBackground: QuestColor
    package var markerBorder: QuestColor
    package var markerIcon: QuestColor
    package var inputBackground: QuestColor
    package var inputForeground: QuestColor
    package var inputBorder: QuestColor
    package var inputPlaceholder: QuestColor
}

/// Sizes in points, before Dynamic Type scales the text.
package struct QuestMetrics: Equatable, Sendable {
    package var fontSize: Double
    package var cardRadius: Double
    package var cardPadding: Double
    package var buttonRadius: Double
    package var buttonPadding: QuestInsets
    package var buttonFontSize: Double
    package var buttonFontWeight: Int
    package var optionRadius: Double
    package var optionPadding: QuestInsets
    package var inputRadius: Double
    package var inputPadding: QuestInsets
    package var markerRadius: Double
    package var gap: Double
    package var sectionGap: Double
}

package enum QuestFontDesign: Equatable, Sendable {
    case standard, serif, monospaced, rounded
}

package enum QuestFontChoice: Equatable, Sendable {
    case system(QuestFontDesign)
    case family(String)
}

package struct QuestStyle: Equatable, Sendable {
    package var mode: QaidQuestTheme.Mode
    package var light: QuestPalette
    package var dark: QuestPalette
    package var metrics: QuestMetrics
    /// The CSS font-family list, in order.
    package var fontFamilies: [String]

    /// `auto` follows the system; `light`/`dark` force one palette.
    package func palette(systemDark: Bool) -> QuestPalette {
        switch mode {
        case .auto: return systemDark ? dark : light
        case .light: return light
        case .dark: return dark
        }
    }

    /// The first family in the list that is installed, or the system font in the
    /// design a generic family asks for.
    package func font(installed: (String) -> Bool) -> QuestFontChoice {
        for family in fontFamilies {
            switch family.lowercased() {
            case "system-ui", "-apple-system", "blinkmacsystemfont", "sans-serif", "ui-sans-serif":
                return .system(.standard)
            case "serif", "ui-serif": return .system(.serif)
            case "monospace", "ui-monospace": return .system(.monospaced)
            case "ui-rounded": return .system(.rounded)
            default: if installed(family) { return .family(family) }
            }
        }
        return .system(.standard)
    }
}

/// A theme as `GET /api/quest-themes/<id>/public` returns it.
package struct QuestThemeDocument: Equatable, Sendable {
    package var preset: QaidQuestTheme.Preset?
    package var mode: QaidQuestTheme.Mode?
    package var unstyled: Bool?
    package var tokens: [String: String]
    package var css: String?

    package init(preset: QaidQuestTheme.Preset? = nil, mode: QaidQuestTheme.Mode? = nil, unstyled: Bool? = nil,
                 tokens: [String: String] = [:], css: String? = nil) {
        self.preset = preset
        self.mode = mode
        self.unstyled = unstyled
        self.tokens = tokens
        self.css = css
    }

    /// Free CSS or `unstyled`: neither has a native meaning, so both are ignored (and logged).
    package var ignoresCSS: Bool {
        unstyled == true || !(css ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// nil for a body that isn't a JSON object. Tokens that aren't `--qaid-` strings are dropped.
    package static func decode(_ data: Data) -> QuestThemeDocument? {
        guard let json = try? QuestJSON.parse(data), let dict = json.objectValue else { return nil }
        var tokens: [String: String] = [:]
        for (key, value) in dict["tokens"]?.objectValue ?? [:] where key.hasPrefix("--qaid-") {
            if let text = value.stringValue { tokens[key] = text }
        }
        return QuestThemeDocument(
            preset: (dict["preset"]?.stringValue).flatMap(QaidQuestTheme.Preset.init),
            mode: (dict["mode"]?.stringValue).flatMap(QaidQuestTheme.Mode.init),
            unstyled: dict["unstyled"]?.boolValue,
            tokens: tokens,
            css: dict["css"]?.stringValue
        )
    }
}

// MARK: Resolution

package enum QuestStyleResolver {
    /// The web embed's stylesheet defaults (`styles-shadow.css`), with `light-dark()`
    /// where the two appearances differ.
    package static let base: [String: String] = [
        "--qaid-q-accent": "#10b981",
        "--qaid-q-error": "#ef4444",
        "--qaid-q-focus": "#6366f1",
        "--qaid-q-card-bg": "light-dark(#ffffff, #1f2937)",
        "--qaid-q-card-fg": "light-dark(#1f2937, #f9fafb)",
        "--qaid-q-card-border": "light-dark(#e5e7eb, #374151)",
        "--qaid-q-card-radius": "1rem",
        "--qaid-q-card-padding": "1.5rem",
        "--qaid-q-card-shadow": "0 25px 50px -12px light-dark(rgba(0, 0, 0, 0.25), rgba(0, 0, 0, 0.6))",
        "--qaid-q-muted-fg": "light-dark(#6b7280, #9ca3af)",
        "--qaid-q-progress-track": "light-dark(#e5e7eb, #374151)",
        "--qaid-q-btn-radius": "0.5rem",
        "--qaid-q-btn-padding-y": "0.625rem",
        "--qaid-q-btn-padding-x": "1.125rem",
        "--qaid-q-btn-font-size": "0.9375em",
        "--qaid-q-btn-font-weight": "500",
        "--qaid-q-btn-primary-bg": "var(--qaid-q-accent, #10b981)",
        "--qaid-q-btn-primary-fg": "var(--qaid-q-accent-text, #ffffff)",
        "--qaid-q-btn-primary-border": "transparent",
        "--qaid-q-btn-secondary-bg": "transparent",
        "--qaid-q-btn-secondary-fg": "light-dark(#374151, #d1d5db)",
        "--qaid-q-btn-secondary-border": "light-dark(#d1d5db, #4b5563)",
        "--qaid-q-option-bg": "light-dark(#ffffff, #111827)",
        "--qaid-q-option-bg-selected": "color-mix(in srgb, var(--qaid-q-accent, #10b981) 10%, transparent)",
        "--qaid-q-option-fg": "light-dark(#1f2937, #f9fafb)",
        "--qaid-q-option-border": "light-dark(#e5e7eb, #374151)",
        "--qaid-q-option-border-selected": "var(--qaid-q-accent, #10b981)",
        "--qaid-q-option-radius": "0.625rem",
        "--qaid-q-option-padding": "0.75rem 0.875rem",
        "--qaid-q-marker-bg": "light-dark(#ffffff, #111827)",
        "--qaid-q-marker-bg-selected": "var(--qaid-q-accent, #10b981)",
        "--qaid-q-marker-border": "light-dark(#d1d5db, #4b5563)",
        "--qaid-q-marker-icon": "light-dark(#ffffff, #1f2937)",
        "--qaid-q-marker-radius": "50%",
        "--qaid-q-input-bg": "light-dark(#ffffff, #111827)",
        "--qaid-q-input-fg": "light-dark(#1f2937, #f9fafb)",
        "--qaid-q-input-border": "light-dark(#d1d5db, #374151)",
        "--qaid-q-input-radius": "0.625rem",
        "--qaid-q-input-padding": "0.75rem 0.875rem",
        "--qaid-q-input-placeholder": "light-dark(#9ca3af, #6b7280)",
        "--qaid-q-gap": "0.75rem",
        "--qaid-q-section-gap": "1.25rem",
        "--qaid-font-family": "system-ui, -apple-system, sans-serif",
        "--qaid-font-size": "16px",
    ]

    /// The tokens each preset overrides (`themes/*.css`).
    package static func presetTokens(_ preset: QaidQuestTheme.Preset) -> [String: String] {
        switch preset {
        case .default:
            return [:]
        case .minimal:
            return [
                "--qaid-q-card-radius": "0", "--qaid-q-card-shadow": "none", "--qaid-q-btn-radius": "0",
                "--qaid-q-option-radius": "0", "--qaid-q-option-bg-selected": "transparent",
                "--qaid-q-input-radius": "0",
            ]
        case .pill:
            return [
                "--qaid-q-card-radius": "1.5rem", "--qaid-q-btn-radius": "9999px", "--qaid-q-btn-padding-x": "1.5rem",
                "--qaid-q-option-radius": "9999px", "--qaid-q-option-padding": "0.75rem 1.125rem",
                "--qaid-q-input-radius": "9999px", "--qaid-q-input-padding": "0.75rem 1.125rem",
            ]
        case .dense:
            return [
                "--qaid-q-card-padding": "1rem", "--qaid-q-btn-padding-y": "0.5rem",
                "--qaid-q-btn-padding-x": "0.875rem", "--qaid-q-option-padding": "0.5rem 0.625rem",
                "--qaid-q-input-padding": "0.5rem 0.625rem", "--qaid-q-gap": "0.5rem",
                "--qaid-q-section-gap": "0.875rem",
            ]
        }
    }

    /// The web embed's layering, lowest first: stylesheet defaults, the theme's tokens,
    /// the preset (which beats the theme's tokens, as on the web), then the app's own
    /// values. `preset` and `mode` come from the app if it set them, else the theme.
    package static func resolve(document: QuestThemeDocument?, host: QaidQuestTheme?) -> QuestStyle {
        let mode = host?.mode ?? document?.mode ?? .auto
        var tokens = base
        for (key, value) in document?.tokens ?? [:]
        where !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            tokens[key] = value
        }
        for (key, value) in presetTokens(host?.preset ?? document?.preset ?? .default) { tokens[key] = value }
        if let host { apply(host, to: &tokens) }

        // A forced mode draws its one appearance for both system settings.
        let light = Layer(tokens: tokens, dark: mode == .dark)
        let dark = Layer(tokens: tokens, dark: mode != .light)
        return QuestStyle(mode: mode, light: light.palette(), dark: dark.palette(), metrics: light.metrics(),
                          fontFamilies: CSS.fontFamilies(light.resolved("--qaid-font-family") ?? ""))
    }

    static func apply(_ host: QaidQuestTheme, to tokens: inout [String: String]) {
        if let accent = host.accent {
            tokens["--qaid-q-accent"] = accent
            // The web embed's contrast rule; an unreadable colour gets white, as there.
            let light = CSS.color(accent, dark: false).map { $0.luminance > 0.4 } ?? false
            tokens["--qaid-q-accent-text"] = light ? "black" : "white"
        }
        if let error = host.error { tokens["--qaid-q-error"] = error }
        if let focus = host.focus { tokens["--qaid-q-focus"] = focus }
        if let family = host.fontFamily { tokens["--qaid-font-family"] = family }
        if let size = host.fontSize { tokens["--qaid-font-size"] = "\(size)px" }
        if let radius = host.cornerRadius {
            for key in ["--qaid-q-card-radius", "--qaid-q-btn-radius", "--qaid-q-option-radius", "--qaid-q-input-radius"] {
                tokens[key] = "\(QuestJSON.number(max(radius, 0)))px"
            }
        }
    }

    /// The tokens seen from one appearance.
    struct Layer {
        var tokens: [String: String]
        var dark: Bool

        /// The token's value with every `var()` replaced; nil when a reference can't be met.
        func resolved(_ name: String) -> String? {
            tokens[name].flatMap { CSS.substitute($0, depth: 0, lookup: lookup) }
        }

        private func lookup(_ name: String, depth: Int) -> String? {
            guard depth < 16, let raw = tokens[name] else { return nil }
            return CSS.substitute(raw, depth: depth + 1, lookup: lookup)
        }

        /// The theme's value, else the stylesheet default (a theme value the SDK can't
        /// read must not leave a control unpainted), else black.
        func color(_ name: String) -> QuestColor {
            if let value = resolved(name), let color = CSS.color(value, dark: dark) { return color }
            if let raw = QuestStyleResolver.base[name], let value = CSS.substitute(raw, depth: 0, lookup: lookup),
               let color = CSS.color(value, dark: dark) {
                return color
            }
            return .black
        }

        func length(_ name: String, fontSize: Double, percentOf: Double = 0) -> Double {
            if let value = resolved(name), let length = CSS.length(value, fontSize: fontSize, percentOf: percentOf) {
                return length
            }
            return CSS.length(QuestStyleResolver.base[name] ?? "0", fontSize: fontSize, percentOf: percentOf) ?? 0
        }

        func insets(_ name: String, fontSize: Double) -> QuestInsets {
            if let value = resolved(name), let insets = CSS.insets(value, fontSize: fontSize) { return insets }
            return CSS.insets(QuestStyleResolver.base[name] ?? "0", fontSize: fontSize) ?? QuestInsets(vertical: 0, horizontal: 0)
        }

        func shadow(_ name: String) -> QuestShadow? {
            if let value = resolved(name), let parsed = CSS.shadow(value, dark: dark) { return parsed.shadow }
            return CSS.shadow(QuestStyleResolver.base[name] ?? "none", dark: dark)?.shadow
        }

        func palette() -> QuestPalette {
            QuestPalette(
                accent: color("--qaid-q-accent"),
                error: color("--qaid-q-error"),
                focus: color("--qaid-q-focus"),
                cardBackground: color("--qaid-q-card-bg"),
                cardForeground: color("--qaid-q-card-fg"),
                cardBorder: color("--qaid-q-card-border"),
                cardShadow: shadow("--qaid-q-card-shadow"),
                muted: color("--qaid-q-muted-fg"),
                progressTrack: color("--qaid-q-progress-track"),
                primaryBackground: color("--qaid-q-btn-primary-bg"),
                primaryForeground: color("--qaid-q-btn-primary-fg"),
                primaryBorder: color("--qaid-q-btn-primary-border"),
                secondaryBackground: color("--qaid-q-btn-secondary-bg"),
                secondaryForeground: color("--qaid-q-btn-secondary-fg"),
                secondaryBorder: color("--qaid-q-btn-secondary-border"),
                optionBackground: color("--qaid-q-option-bg"),
                optionSelectedBackground: color("--qaid-q-option-bg-selected"),
                optionForeground: color("--qaid-q-option-fg"),
                optionBorder: color("--qaid-q-option-border"),
                optionSelectedBorder: color("--qaid-q-option-border-selected"),
                markerBackground: color("--qaid-q-marker-bg"),
                markerSelectedBackground: color("--qaid-q-marker-bg-selected"),
                markerBorder: color("--qaid-q-marker-border"),
                markerIcon: color("--qaid-q-marker-icon"),
                inputBackground: color("--qaid-q-input-bg"),
                inputForeground: color("--qaid-q-input-fg"),
                inputBorder: color("--qaid-q-input-border"),
                inputPlaceholder: color("--qaid-q-input-placeholder")
            )
        }

        func metrics() -> QuestMetrics {
            let fontSize = length("--qaid-font-size", fontSize: 16)
            return QuestMetrics(
                fontSize: fontSize,
                cardRadius: length("--qaid-q-card-radius", fontSize: fontSize),
                cardPadding: length("--qaid-q-card-padding", fontSize: fontSize),
                buttonRadius: length("--qaid-q-btn-radius", fontSize: fontSize),
                buttonPadding: QuestInsets(vertical: length("--qaid-q-btn-padding-y", fontSize: fontSize),
                                           horizontal: length("--qaid-q-btn-padding-x", fontSize: fontSize)),
                buttonFontSize: length("--qaid-q-btn-font-size", fontSize: fontSize),
                buttonFontWeight: resolved("--qaid-q-btn-font-weight").flatMap(CSS.fontWeight) ?? 500,
                optionRadius: length("--qaid-q-option-radius", fontSize: fontSize),
                optionPadding: insets("--qaid-q-option-padding", fontSize: fontSize),
                inputRadius: length("--qaid-q-input-radius", fontSize: fontSize),
                inputPadding: insets("--qaid-q-input-padding", fontSize: fontSize),
                // The marker is 1.25rem; a percentage is of that.
                markerRadius: length("--qaid-q-marker-radius", fontSize: fontSize, percentOf: 20),
                gap: length("--qaid-q-gap", fontSize: fontSize),
                sectionGap: length("--qaid-q-section-gap", fontSize: fontSize)
            )
        }
    }
}

// MARK: CSS values

/// The CSS value grammar quest themes use: colours (hex, `rgb()`, `hsl()`, a few
/// names, `light-dark()`, `color-mix(in srgb, …)`), `var()` with fallbacks, lengths
/// in `px`/`rem`/`em`/`%`, padding shorthands, box shadows and font-family lists.
/// `rem` is 16 points, as on a page with the default root size.
package enum CSS {
    /// `value` with every `var(--name[, fallback])` replaced. nil when a name has no
    /// value and no fallback, which in CSS makes the whole declaration invalid.
    static func substitute(_ value: String, depth: Int,
                           lookup: (_ name: String, _ depth: Int) -> String?) -> String? {
        var out = ""
        var rest = Substring(value)
        while let start = rest.range(of: "var(") {
            out += rest[..<start.lowerBound]
            guard let close = closingParen(in: rest, from: start.upperBound) else { return nil }
            let inner = rest[start.upperBound..<close]
            let (name, fallback) = splitFirstComma(inner)
            if let found = lookup(name.trimmingCharacters(in: .whitespaces), depth) {
                out += found
            } else if let fallback, let resolved = substitute(fallback, depth: depth, lookup: lookup) {
                out += resolved
            } else {
                return nil
            }
            rest = rest[rest.index(after: close)...]
        }
        return out + rest
    }

    /// The index of the `)` that closes a paren opened just before `start`.
    static func closingParen(in text: Substring, from start: Substring.Index) -> Substring.Index? {
        var depth = 1
        var index = start
        while index < text.endIndex {
            switch text[index] {
            case "(": depth += 1
            case ")":
                depth -= 1
                if depth == 0 { return index }
            default: break
            }
            index = text.index(after: index)
        }
        return nil
    }

    static func splitFirstComma(_ text: Substring) -> (String, String?) {
        let parts = split(String(text), on: { $0 == "," }, keepEmpty: true)
        guard parts.count > 1 else { return (String(text), nil) }
        return (parts[0], parts.dropFirst().joined(separator: ",").trimmingCharacters(in: .whitespaces))
    }

    /// Splits on separators outside parentheses. Whitespace splitting drops empties.
    static func split(_ text: String, on isSeparator: (Character) -> Bool, keepEmpty: Bool = false) -> [String] {
        var parts: [String] = []
        var current = ""
        var depth = 0
        for char in text {
            if char == "(" { depth += 1 } else if char == ")" { depth -= 1 }
            if depth == 0 && isSeparator(char) {
                parts.append(current)
                current = ""
            } else {
                current.append(char)
            }
        }
        parts.append(current)
        return keepEmpty ? parts : parts.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    static func words(_ text: String) -> [String] {
        split(text, on: { $0 == " " || $0 == "\t" || $0 == "\n" })
    }

    /// `name(args)` when the whole value is one call.
    static func call(_ text: String) -> (name: String, args: String)? {
        guard let open = text.firstIndex(of: "("), text.hasSuffix(")"),
              closingParen(in: Substring(text), from: text.index(after: open)) == text.index(before: text.endIndex)
        else { return nil }
        return (String(text[..<open]), String(text[text.index(after: open)..<text.index(before: text.endIndex)]))
    }

    // MARK: Colours

    package static func color(_ value: String, dark: Bool) -> QuestColor? {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let (name, args) = call(text) {
            switch name {
            case "light-dark":
                let parts = split(args, on: { $0 == "," })
                guard parts.count == 2 else { return nil }
                return color(dark ? parts[1] : parts[0], dark: dark)
            case "rgb", "rgba": return rgb(args)
            case "hsl", "hsla": return hsl(args)
            case "color-mix": return mix(args, dark: dark)
            default: return nil
            }
        }
        if text.hasPrefix("#") { return hex(String(text.dropFirst())) }
        return named[text]
    }

    static let named: [String: QuestColor] = [
        "transparent": .clear,
        "black": .black,
        "white": .white,
        "red": QuestColor(red: 1, green: 0, blue: 0),
        "green": QuestColor(red: 0, green: 128 / 255, blue: 0),
        "blue": QuestColor(red: 0, green: 0, blue: 1),
        "gray": QuestColor(red: 128 / 255, green: 128 / 255, blue: 128 / 255),
        "grey": QuestColor(red: 128 / 255, green: 128 / 255, blue: 128 / 255),
    ]

    static func hex(_ digits: String) -> QuestColor? {
        guard [3, 4, 6, 8].contains(digits.count), let number = UInt64(digits, radix: 16) else { return nil }
        let short = digits.count <= 4
        let count = short ? digits.count : digits.count / 2
        var channels: [Double] = []
        for index in (0..<count).reversed() {
            let raw = short ? (number >> (4 * UInt64(index))) & 0xF : (number >> (8 * UInt64(index))) & 0xFF
            channels.append(short ? Double(raw * 17) / 255 : Double(raw) / 255)
        }
        return QuestColor(red: channels[0], green: channels[1], blue: channels[2],
                          alpha: channels.count == 4 ? channels[3] : 1)
    }

    /// The numbers of `rgb()`/`hsl()`, comma or space separated, with an optional `/ alpha`.
    static func components(_ args: String) -> [String] {
        words(args.replacingOccurrences(of: ",", with: " ").replacingOccurrences(of: "/", with: " "))
    }

    static func number(_ text: String, percentScale: Double) -> Double? {
        if text.hasSuffix("%") { return Double(text.dropLast()).map { $0 / 100 * percentScale } }
        return Double(text)
    }

    static func alpha(_ parts: [String]) -> Double? {
        guard parts.count == 4 else { return parts.count == 3 ? 1 : nil }
        return number(parts[3], percentScale: 1).map(clamp)
    }

    static func rgb(_ args: String) -> QuestColor? {
        let parts = components(args)
        guard let alpha = alpha(parts) else { return nil }
        let channels = parts.prefix(3).compactMap { number($0, percentScale: 255) }
        guard channels.count == 3 else { return nil }
        return QuestColor(red: clamp(channels[0] / 255), green: clamp(channels[1] / 255),
                          blue: clamp(channels[2] / 255), alpha: alpha)
    }

    static func hsl(_ args: String) -> QuestColor? {
        let parts = components(args)
        guard let alpha = alpha(parts),
              let hue = Double(parts[0].replacingOccurrences(of: "deg", with: "")),
              let saturation = number(parts[1], percentScale: 100), let lightness = number(parts[2], percentScale: 100)
        else { return nil }
        let s = clamp(saturation / 100), l = clamp(lightness / 100)
        let h = (hue.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 30
        func channel(_ n: Double) -> Double {
            let k = (n + h).truncatingRemainder(dividingBy: 12)
            return l - s * min(l, 1 - l) * max(-1, min(k - 3, 9 - k, 1))
        }
        return QuestColor(red: channel(0), green: channel(8), blue: channel(4), alpha: alpha)
    }

    /// `color-mix(in srgb, A [p%], B [q%])`, mixed with premultiplied alpha as CSS does,
    /// so `color-mix(in srgb, X 10%, transparent)` is X at 10% opacity.
    static func mix(_ args: String, dark: Bool) -> QuestColor? {
        let parts = split(args, on: { $0 == "," })
        guard parts.count == 3, parts[0].hasPrefix("in "),
              let first = mixPart(parts[1], dark: dark), let second = mixPart(parts[2], dark: dark) else { return nil }
        let w1: Double, w2: Double
        switch (first.percent, second.percent) {
        case (nil, nil): (w1, w2) = (50, 50)
        case let (p?, nil): (w1, w2) = (p, 100 - p)
        case let (nil, q?): (w1, w2) = (100 - q, q)
        case let (p?, q?): (w1, w2) = (p, q)
        }
        let total = w1 + w2
        guard total > 0 else { return nil }
        let (a, b) = (first.color, second.color)
        let alpha = a.alpha * w1 / total + b.alpha * w2 / total
        func channel(_ x: Double, _ y: Double) -> Double {
            alpha == 0 ? 0 : (x * a.alpha * w1 / total + y * b.alpha * w2 / total) / alpha
        }
        return QuestColor(red: channel(a.red, b.red), green: channel(a.green, b.green),
                          blue: channel(a.blue, b.blue), alpha: alpha * min(total, 100) / 100)
    }

    static func mixPart(_ text: String, dark: Bool) -> (color: QuestColor, percent: Double?)? {
        var percent: Double?
        var rest: [String] = []
        for word in words(text) {
            if word.hasSuffix("%"), let value = Double(word.dropLast()) { percent = value } else { rest.append(word) }
        }
        guard let color = color(rest.joined(separator: " "), dark: dark) else { return nil }
        return (color, percent)
    }

    static func clamp(_ value: Double) -> Double { min(max(value, 0), 1) }

    // MARK: Lengths, shadows, fonts

    /// Points. `percentOf` is what 100% is.
    package static func length(_ value: String, fontSize: Double, percentOf: Double = 0) -> Double? {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        for (unit, scale) in [("rem", 16.0), ("px", 1), ("em", fontSize), ("%", percentOf / 100)] where text.hasSuffix(unit) {
            return Double(text.dropLast(unit.count)).map { $0 * scale }
        }
        return Double(text)
    }

    /// The vertical and horizontal of a 1-4 value padding.
    static func insets(_ value: String, fontSize: Double) -> QuestInsets? {
        let lengths = words(value).map { length($0, fontSize: fontSize) }
        guard (1...4).contains(lengths.count), let vertical = lengths[0],
              let horizontal = lengths.count > 1 ? lengths[1] : vertical else { return nil }
        return QuestInsets(vertical: vertical, horizontal: horizontal)
    }

    /// `x y [blur [spread]] [colour]`, or `none`. The first of several shadows; an inset
    /// one draws as none. nil when unreadable.
    static func shadow(_ value: String, dark: Bool) -> ShadowValue? {
        let text = (split(value, on: { $0 == "," }).first ?? "").lowercased()
        if text == "none" || words(text).contains("inset") { return ShadowValue.none }
        var lengths: [Double] = []
        var shade: QuestColor?
        for word in words(text) {
            if let number = length(word, fontSize: 16) {
                lengths.append(number)
            } else if let parsed = color(word, dark: dark) {
                shade = parsed
            } else {
                return nil
            }
        }
        guard (2...4).contains(lengths.count) else { return nil }
        return .shadow(QuestShadow(x: lengths[0], y: lengths[1], blur: lengths.count > 2 ? lengths[2] : 0,
                                   color: shade ?? .black))
    }

    enum ShadowValue: Equatable {
        case none
        case shadow(QuestShadow)

        var shadow: QuestShadow? {
            if case .shadow(let value) = self { return value }
            return nil
        }
    }

    static func fontWeight(_ value: String) -> Int? {
        switch value.trimmingCharacters(in: .whitespaces).lowercased() {
        case "normal": return 400
        case "bold": return 700
        case let text: return Int(text).flatMap { (1...1000).contains($0) ? $0 : nil }
        }
    }

    /// `"Inter", system-ui, sans-serif` → `["Inter", "system-ui", "sans-serif"]`.
    static func fontFamilies(_ value: String) -> [String] {
        split(value, on: { $0 == "," }).map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "\"' ")) }
            .filter { !$0.isEmpty }
    }
}
