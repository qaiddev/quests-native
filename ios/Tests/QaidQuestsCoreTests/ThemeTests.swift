import XCTest
@testable import QaidQuestsCore

private func rgb(_ r: Double, _ g: Double, _ b: Double, _ a: Double = 1) -> QuestColor {
    QuestColor(red: r / 255, green: g / 255, blue: b / 255, alpha: a)
}

private func assertColor(_ actual: QuestColor?, _ expected: QuestColor, file: StaticString = #filePath, line: UInt = #line) {
    guard let actual else { return XCTFail("nil colour", file: file, line: line) }
    for (a, e) in [(actual.red, expected.red), (actual.green, expected.green), (actual.blue, expected.blue),
                   (actual.alpha, expected.alpha)] {
        XCTAssertEqual(a, e, accuracy: 0.002, "\(actual) vs \(expected)", file: file, line: line)
    }
}

final class CSSColorTests: XCTestCase {
    func testHex() {
        assertColor(CSS.color("#10b981", dark: false), rgb(16, 185, 129))
        assertColor(CSS.color("#FFF", dark: false), .white)
        assertColor(CSS.color("#0008", dark: false), rgb(0, 0, 0, 136.0 / 255))
        assertColor(CSS.color("#10b98180", dark: false), rgb(16, 185, 129, 128.0 / 255))
        XCTAssertNil(CSS.color("#12345", dark: false))
        XCTAssertNil(CSS.color("#gggggg", dark: false))
    }

    func testRGBAndHSL() {
        assertColor(CSS.color("rgb(16, 185, 129)", dark: false), rgb(16, 185, 129))
        assertColor(CSS.color("rgba(0,0,0,0.25)", dark: false), rgb(0, 0, 0, 0.25))
        assertColor(CSS.color("rgb(16 185 129 / 50%)", dark: false), rgb(16, 185, 129, 0.5))
        assertColor(CSS.color("rgb(100% 0% 0%)", dark: false), rgb(255, 0, 0))
        assertColor(CSS.color("rgb(300, -5, 0, 2)", dark: false), rgb(255, 0, 0, 1))
        XCTAssertNil(CSS.color("rgb(1, 2)", dark: false))
        XCTAssertNil(CSS.color("rgb(1, 2, x)", dark: false))
        XCTAssertNil(CSS.color("rgb(1, 2, 3, x)", dark: false))
        assertColor(CSS.color("hsl(0, 100%, 50%)", dark: false), rgb(255, 0, 0))
        assertColor(CSS.color("hsl(120deg 100% 25%)", dark: false), rgb(0, 128, 0))
        assertColor(CSS.color("hsla(240, 100%, 50%, 0.5)", dark: false), rgb(0, 0, 255, 0.5))
        assertColor(CSS.color("hsl(-120, 0%, 100%)", dark: false), .white)
        XCTAssertNil(CSS.color("hsl(x, 1%, 1%)", dark: false))
        XCTAssertNil(CSS.color("hsl(1, 1%)", dark: false))
    }

    func testNamesAndUnknowns() {
        assertColor(CSS.color("transparent", dark: false), .clear)
        assertColor(CSS.color(" White ", dark: false), .white)
        assertColor(CSS.color("grey", dark: false), rgb(128, 128, 128))
        XCTAssertNil(CSS.color("rebeccapurple", dark: false))
        XCTAssertNil(CSS.color("oklch(0.7 0.1 200)", dark: false))
    }

    func testLightDark() {
        let value = "light-dark(#ffffff, #1f2937)"
        assertColor(CSS.color(value, dark: false), .white)
        assertColor(CSS.color(value, dark: true), rgb(31, 41, 55))
        assertColor(CSS.color("light-dark(rgba(0, 0, 0, 0.25), rgba(0, 0, 0, 0.6))", dark: true), rgb(0, 0, 0, 0.6))
        XCTAssertNil(CSS.color("light-dark(#fff)", dark: false))
    }

    func testColorMix() {
        assertColor(CSS.color("color-mix(in srgb, #10b981 10%, transparent)", dark: false), rgb(16, 185, 129, 0.1))
        assertColor(CSS.color("color-mix(in srgb, #000, #fff)", dark: false), rgb(127.5, 127.5, 127.5))
        assertColor(CSS.color("color-mix(in srgb, #000 25%, #fff)", dark: false), rgb(191.25, 191.25, 191.25))
        assertColor(CSS.color("color-mix(in srgb, #000, #fff 25%)", dark: false), rgb(63.75, 63.75, 63.75))
        // Percentages under 100 in total lower the alpha, as CSS does.
        assertColor(CSS.color("color-mix(in srgb, #000 20%, #fff 30%)", dark: false), rgb(153, 153, 153, 0.5))
        assertColor(CSS.color("color-mix(in srgb, transparent, transparent)", dark: false), .clear)
        XCTAssertNil(CSS.color("color-mix(in srgb, #000 0%, #fff 0%)", dark: false))
        XCTAssertNil(CSS.color("color-mix(#000, #fff)", dark: false))
        XCTAssertNil(CSS.color("color-mix(in srgb, nope, #fff)", dark: false))
    }

    func testLuminance() {
        XCTAssertEqual(QuestColor.white.luminance, 1, accuracy: 0.0001)
        XCTAssertEqual(QuestColor.black.luminance, 0, accuracy: 0.0001)
        XCTAssertEqual(rgb(16, 185, 129).luminance, 0.3639, accuracy: 0.001)
        XCTAssertEqual(QuestColor.white.opacity(0.5).alpha, 0.5)
    }
}

final class CSSValueTests: XCTestCase {
    func testVarSubstitution() {
        let tokens = ["--a": "#fff", "--b": "var(--a)", "--loop": "var(--loop)"]
        func lookup(_ name: String, _ depth: Int) -> String? {
            guard depth < 16, let raw = tokens[name] else { return nil }
            return CSS.substitute(raw, depth: depth + 1, lookup: lookup)
        }
        XCTAssertEqual(CSS.substitute("var(--b)", depth: 0, lookup: lookup), "#fff")
        XCTAssertEqual(CSS.substitute("var(--missing, var(--a))", depth: 0, lookup: lookup), "#fff")
        XCTAssertEqual(CSS.substitute("var(--missing, rgb(1, 2, 3))", depth: 0, lookup: lookup), "rgb(1, 2, 3)")
        XCTAssertEqual(CSS.substitute("0 1px var(--a)", depth: 0, lookup: lookup), "0 1px #fff")
        XCTAssertNil(CSS.substitute("var(--missing)", depth: 0, lookup: lookup))
        XCTAssertNil(CSS.substitute("var(--missing, var(--gone))", depth: 0, lookup: lookup))
        XCTAssertNil(CSS.substitute("var(--loop)", depth: 0, lookup: lookup))
        XCTAssertNil(CSS.substitute("var(--a", depth: 0, lookup: lookup))
    }

    func testSplitting() {
        XCTAssertEqual(CSS.split("a, rgb(1, 2), b", on: { $0 == "," }), ["a", "rgb(1, 2)", "b"])
        XCTAssertEqual(CSS.words("0  25px\tred"), ["0", "25px", "red"])
        XCTAssertNil(CSS.call("rgb(1)(2)"))
        XCTAssertNil(CSS.call("plain"))
    }

    func testLengths() {
        XCTAssertEqual(CSS.length("1.5rem", fontSize: 16), 24)
        XCTAssertEqual(CSS.length("12px", fontSize: 16), 12)
        XCTAssertEqual(CSS.length("0.5em", fontSize: 20), 10)
        XCTAssertEqual(CSS.length("50%", fontSize: 16, percentOf: 20), 10)
        XCTAssertEqual(CSS.length("0", fontSize: 16), 0)
        XCTAssertEqual(CSS.length("8", fontSize: 16), 8)
        XCTAssertNil(CSS.length("calc(1px + 2px)", fontSize: 16))
        XCTAssertNil(CSS.length("xpx", fontSize: 16))
    }

    func testInsets() {
        XCTAssertEqual(CSS.insets("0.75rem 0.875rem", fontSize: 16), QuestInsets(vertical: 12, horizontal: 14))
        XCTAssertEqual(CSS.insets("8px", fontSize: 16), QuestInsets(vertical: 8, horizontal: 8))
        XCTAssertEqual(CSS.insets("1px 2px 3px 4px", fontSize: 16), QuestInsets(vertical: 1, horizontal: 2))
        XCTAssertNil(CSS.insets("", fontSize: 16))
        XCTAssertNil(CSS.insets("1px 2px 3px 4px 5px", fontSize: 16))
        XCTAssertNil(CSS.insets("x", fontSize: 16))
        XCTAssertNil(CSS.insets("1px x", fontSize: 16))
    }

    func testShadows() {
        XCTAssertEqual(CSS.shadow("0 25px 50px -12px rgba(0, 0, 0, 0.25)", dark: false),
                       .shadow(QuestShadow(x: 0, y: 25, blur: 50, color: rgb(0, 0, 0, 0.25))))
        XCTAssertEqual(CSS.shadow("2px 3px", dark: false), .shadow(QuestShadow(x: 2, y: 3, blur: 0, color: .black)))
        XCTAssertEqual(CSS.shadow("none", dark: false), CSS.ShadowValue.none)
        XCTAssertEqual(CSS.shadow("inset 0 1px red", dark: false), CSS.ShadowValue.none)
        XCTAssertEqual(CSS.shadow("1px 1px red, 2px 2px blue", dark: false)?.shadow?.x, 1)
        XCTAssertNil(CSS.shadow("1px", dark: false))
        XCTAssertNil(CSS.shadow("1px 2px wobbly", dark: false))
        XCTAssertNil(CSS.shadow("", dark: false))
        XCTAssertNil(CSS.ShadowValue.none.shadow)
    }

    func testFontsAndWeights() {
        XCTAssertEqual(CSS.fontFamilies(#""Inter Var", 'Helvetica', system-ui,"#), ["Inter Var", "Helvetica", "system-ui"])
        XCTAssertEqual(CSS.fontWeight("bold"), 700)
        XCTAssertEqual(CSS.fontWeight(" normal"), 400)
        XCTAssertEqual(CSS.fontWeight("600"), 600)
        XCTAssertNil(CSS.fontWeight("2000"))
        XCTAssertNil(CSS.fontWeight("heavy"))
    }
}

final class ThemeResolutionTests: XCTestCase {
    func testDefaultsMatchTheWebStylesheet() {
        let style = QuestStyleResolver.resolve(document: nil, host: nil)
        XCTAssertEqual(style.mode, .auto)
        assertColor(style.light.accent, rgb(16, 185, 129))
        assertColor(style.light.error, rgb(239, 68, 68))
        assertColor(style.light.focus, rgb(99, 102, 241))
        assertColor(style.light.cardBackground, .white)
        assertColor(style.dark.cardBackground, rgb(31, 41, 55))
        assertColor(style.light.cardForeground, rgb(31, 41, 55))
        assertColor(style.dark.muted, rgb(156, 163, 175))
        assertColor(style.light.primaryBackground, rgb(16, 185, 129))
        assertColor(style.light.primaryForeground, .white)
        assertColor(style.light.primaryBorder, .clear)
        assertColor(style.light.optionSelectedBackground, rgb(16, 185, 129, 0.1))
        assertColor(style.dark.optionBackground, rgb(17, 24, 39))
        assertColor(style.light.markerSelectedBackground, rgb(16, 185, 129))
        assertColor(style.dark.markerIcon, rgb(31, 41, 55))
        assertColor(style.light.inputPlaceholder, rgb(156, 163, 175))
        XCTAssertEqual(style.light.cardShadow, QuestShadow(x: 0, y: 25, blur: 50, color: rgb(0, 0, 0, 0.25)))
        XCTAssertEqual(style.dark.cardShadow?.color.alpha ?? 0, 0.6, accuracy: 0.001)
        XCTAssertEqual(style.metrics, QuestMetrics(
            fontSize: 16, cardRadius: 16, cardPadding: 24, buttonRadius: 8,
            buttonPadding: QuestInsets(vertical: 10, horizontal: 18), buttonFontSize: 15, buttonFontWeight: 500,
            optionRadius: 10, optionPadding: QuestInsets(vertical: 12, horizontal: 14), inputRadius: 10,
            inputPadding: QuestInsets(vertical: 12, horizontal: 14), markerRadius: 10, gap: 12, sectionGap: 20
        ))
        XCTAssertEqual(style.fontFamilies, ["system-ui", "-apple-system", "sans-serif"])
        XCTAssertEqual(style.palette(systemDark: true), style.dark)
        XCTAssertEqual(style.palette(systemDark: false), style.light)
    }

    func testThemeTokensPresetAndMode() throws {
        let document = try XCTUnwrap(QuestThemeDocument.decode(Data(#"""
        {"id":"t","preset":"pill","mode":"dark","unstyled":true,"css":".x{}",
         "tokens":{"--qaid-q-accent":"#3b82f6","--qaid-q-btn-radius":"4px","--qaid-q-card-bg":"  ",
                   "--qaid-font-size":"20px","--qaid-q-gap":"1em","not-a-token":"x","--qaid-q-error":5}}
        """#.utf8)))
        XCTAssertEqual(document.unstyled, true)
        XCTAssertEqual(document.css, ".x{}")
        XCTAssertNil(document.tokens["not-a-token"])
        XCTAssertNil(document.tokens["--qaid-q-error"])
        let style = QuestStyleResolver.resolve(document: document, host: nil)
        XCTAssertEqual(style.mode, .dark)
        // Forced dark: both appearances draw dark.
        XCTAssertEqual(style.light, style.dark)
        XCTAssertEqual(style.palette(systemDark: false), style.dark)
        assertColor(style.dark.cardBackground, rgb(31, 41, 55))
        assertColor(style.dark.accent, rgb(59, 130, 246))
        // Derived tokens follow the theme's accent.
        assertColor(style.dark.primaryBackground, rgb(59, 130, 246))
        assertColor(style.dark.optionSelectedBackground, rgb(59, 130, 246, 0.1))
        // A theme's accent keeps white button text (the web embed's rule).
        assertColor(style.dark.primaryForeground, .white)
        // The preset beats the theme's own token for the same key, as on the web.
        XCTAssertEqual(style.metrics.buttonRadius, 9999)
        XCTAssertEqual(style.metrics.buttonPadding.horizontal, 24)
        XCTAssertEqual(style.metrics.fontSize, 20)
        XCTAssertEqual(style.metrics.gap, 20)
        XCTAssertEqual(style.metrics.buttonFontSize, 18.75)
    }

    func testForcedLight() {
        let style = QuestStyleResolver.resolve(document: QuestThemeDocument(mode: .light), host: nil)
        XCTAssertEqual(style.light, style.dark)
        assertColor(style.palette(systemDark: true).cardBackground, .white)
    }

    func testPresets() {
        let minimal = QuestStyleResolver.resolve(document: QuestThemeDocument(preset: .minimal), host: nil)
        XCTAssertEqual(minimal.metrics.cardRadius, 0)
        XCTAssertNil(minimal.light.cardShadow)
        assertColor(minimal.light.optionSelectedBackground, .clear)
        let dense = QuestStyleResolver.resolve(document: nil, host: QaidQuestTheme(preset: .dense))
        XCTAssertEqual(dense.metrics.cardPadding, 16)
        XCTAssertEqual(dense.metrics.sectionGap, 14)
        XCTAssertEqual(dense.metrics.optionPadding, QuestInsets(vertical: 8, horizontal: 10))
        // The app's preset beats the theme's.
        let hostWins = QuestStyleResolver.resolve(document: QuestThemeDocument(preset: .pill),
                                                  host: QaidQuestTheme(preset: .default))
        XCTAssertEqual(hostWins.metrics.buttonRadius, 8)
        XCTAssertEqual(QuestStyleResolver.presetTokens(.default), [:])
    }

    func testHostOverridesWin() {
        let document = QuestThemeDocument(mode: .dark, tokens: ["--qaid-q-accent": "#000000"])
        let host = QaidQuestTheme(accent: "#fde047", error: "#dc2626", focus: "rgb(1, 2, 3)", fontFamily: "Avenir, serif",
                                  cornerRadius: 6, mode: .auto)
        let style = QuestStyleResolver.resolve(document: document, host: host)
        XCTAssertEqual(style.mode, .auto)
        assertColor(style.light.accent, rgb(253, 224, 71))
        // A light accent gets black text.
        assertColor(style.light.primaryForeground, .black)
        assertColor(style.light.error, rgb(220, 38, 38))
        assertColor(style.light.focus, rgb(1, 2, 3))
        XCTAssertEqual(style.fontFamilies, ["Avenir", "serif"])
        XCTAssertEqual([style.metrics.cardRadius, style.metrics.buttonRadius, style.metrics.optionRadius,
                        style.metrics.inputRadius], [6, 6, 6, 6])
        let dark = QuestStyleResolver.resolve(document: nil, host: QaidQuestTheme(accent: "#1e3a8a", cornerRadius: -3))
        assertColor(dark.light.primaryForeground, .white)
        XCTAssertEqual(dark.metrics.cardRadius, 0)
        let unreadable = QuestStyleResolver.resolve(document: nil, host: QaidQuestTheme(accent: "nope"))
        assertColor(unreadable.light.primaryForeground, .white)
        // An accent the SDK can't read falls back to the stylesheet's.
        assertColor(unreadable.light.accent, rgb(16, 185, 129))
    }

    func testUnreadableThemeValuesFallBackToTheStylesheet() {
        let document = QuestThemeDocument(tokens: [
            "--qaid-q-card-bg": "oklch(1 0 0)", "--qaid-q-card-radius": "calc(1px)", "--qaid-q-option-padding": "x",
            "--qaid-q-card-shadow": "wobbly", "--qaid-q-btn-font-weight": "heavy", "--qaid-q-muted-fg": "var(--nope)",
        ])
        let style = QuestStyleResolver.resolve(document: document, host: nil)
        assertColor(style.light.cardBackground, .white)
        assertColor(style.light.muted, rgb(107, 114, 128))
        XCTAssertEqual(style.metrics.cardRadius, 16)
        XCTAssertEqual(style.metrics.optionPadding, QuestInsets(vertical: 12, horizontal: 14))
        XCTAssertEqual(style.light.cardShadow?.y, 25)
        XCTAssertEqual(style.metrics.buttonFontWeight, 500)
    }

    func testLayerFallbacksForNamesWithoutADefault() {
        let layer = QuestStyleResolver.Layer(tokens: [:], dark: false)
        XCTAssertEqual(layer.color("--qaid-q-nothing"), .black)
        XCTAssertEqual(layer.length("--qaid-q-nothing", fontSize: 16), 0)
        XCTAssertEqual(layer.insets("--qaid-q-nothing", fontSize: 16), QuestInsets(vertical: 0, horizontal: 0))
        XCTAssertNil(layer.shadow("--qaid-q-nothing"))
        XCTAssertNil(layer.resolved("--qaid-q-nothing"))
    }

    func testDocumentDecoding() {
        XCTAssertNil(QuestThemeDocument.decode(Data("[]".utf8)))
        XCTAssertNil(QuestThemeDocument.decode(Data("x".utf8)))
        XCTAssertEqual(QuestThemeDocument.decode(Data(#"{"preset":"neon","mode":"sepia","tokens":null}"#.utf8)),
                       QuestThemeDocument())
    }

    func testFontChoice() {
        func choice(_ families: [String], installed: Set<String> = []) -> QuestFontChoice {
            var style = QuestStyleResolver.resolve(document: nil, host: nil)
            style.fontFamilies = families
            return style.font { installed.contains($0) }
        }
        XCTAssertEqual(choice(["Inter", "system-ui"]), .system(.standard))
        XCTAssertEqual(choice(["Inter", "system-ui"], installed: ["Inter"]), .family("Inter"))
        XCTAssertEqual(choice(["Georgia", "ui-serif"]), .system(.serif))
        XCTAssertEqual(choice(["monospace"]), .system(.monospaced))
        XCTAssertEqual(choice(["ui-rounded"]), .system(.rounded))
        XCTAssertEqual(choice(["Nope"]), .system(.standard))
        XCTAssertEqual(choice([]), .system(.standard))
    }
}
