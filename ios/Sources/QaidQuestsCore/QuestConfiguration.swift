import Foundation

public enum QaidQuestsSDK {
    public static let version = "0.1.0"
}

/// Everything the SDK needs to know about the qaid project its quests belong to.
///
/// `pageUrl` is what qaid stores with each response and checks against the project's
/// Domain Restriction. Leave it nil and responses go out as `app://<bundle id>`, which
/// qaid reads as a reversed domain: `app://com.example.myapp` passes a restriction of
/// `example.com`. A `screen` passed to a quest view is appended as one more path segment.
public struct QaidQuestsConfiguration: Equatable, Sendable {
    /// The project's embed API key — the same one the web embed uses.
    public var apiKey: String
    /// `https://qaid.dev/api/quests`. Definitions are `<base>/<id>/definition`, responses
    /// `<base>/responses`.
    public var base: URL
    public var pageUrl: URL?
    /// Sent in the user agent and the response metadata. Empty uses the bundle's name.
    public var appName: String
    /// The app's own look, laid over the saved theme. nil keeps the theme as saved.
    public var theme: QaidQuestTheme?
    /// A saved quest theme, fetched from `/api/quest-themes/<id>/public` on the base's origin.
    public var themeId: String?
    /// Pins `themeId` to one published version instead of whichever is live.
    public var themeVersion: Int?
    /// Every word the SDK draws itself.
    public var text: QaidQuestText
    /// Picking an option of a single-choice question moves on by itself, 180 ms later.
    public var autoAdvance: Bool
    /// Overrides the quest's own progress position.
    public var progressPosition: QaidQuestProgressPosition?
    /// Overrides the visitor id kept on the device (shared with the QaidFeedback SDK).
    public var visitorId: String?
    /// How long typing settles before an answer is saved, in milliseconds.
    public var saveDebounceMs: Int

    public init(
        apiKey: String,
        appName: String = "",
        base: URL = QaidQuestsConfiguration.defaultBase,
        pageUrl: URL? = nil,
        theme: QaidQuestTheme? = nil,
        themeId: String? = nil,
        themeVersion: Int? = nil,
        text: QaidQuestText = QaidQuestText(),
        autoAdvance: Bool = false,
        progressPosition: QaidQuestProgressPosition? = nil,
        visitorId: String? = nil,
        saveDebounceMs: Int = 500
    ) {
        self.apiKey = apiKey
        self.appName = appName
        self.base = base
        self.pageUrl = pageUrl
        self.theme = theme
        self.themeId = themeId
        self.themeVersion = themeVersion
        self.text = text
        self.autoAdvance = autoAdvance
        self.progressPosition = progressPosition
        self.visitorId = visitorId
        self.saveDebounceMs = saveDebounceMs
    }

    public static let defaultBase = URL(string: "https://qaid.dev/api/quests")!

    /// `app://<bundle id>`, lower case, what responses use when no `pageUrl` is set.
    public static func defaultPageUrl(bundleIdentifier: String) -> URL {
        let id = bundleIdentifier.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return URL(string: "app://\(id.isEmpty ? "app" : id)") ?? URL(string: "app://app")!
    }
}

/// The app's own look for quests, laid over the saved theme (it wins, as the web
/// embed's host config does). Colours are CSS colours: `#10b981`, `rgb(16 185 129)`.
public struct QaidQuestTheme: Equatable, Sendable {
    public enum Mode: String, Equatable, Sendable {
        /// Follows the system's light or dark appearance.
        case auto
        case light
        case dark
    }

    /// qaid's theme editor presets.
    public enum Preset: String, Equatable, Sendable, CaseIterable {
        case `default`, minimal, pill, dense
    }

    /// Buttons, selection, progress, the range value. Text on the primary button turns
    /// black or white to stay readable.
    public var accent: String?
    public var error: String?
    /// The focus ring.
    public var focus: String?
    /// The first installed family wins; `system-ui` (or nothing) is the system font.
    public var fontFamily: String?
    /// The base text size in points; every other size scales from it (and with Dynamic
    /// Type). qaid's default is 16.
    public var fontSize: Double?
    /// Points, for the card, buttons, options and fields alike.
    public var cornerRadius: Double?
    public var mode: Mode?
    public var preset: Preset?

    public init(accent: String? = nil, error: String? = nil, focus: String? = nil, fontFamily: String? = nil,
                fontSize: Double? = nil, cornerRadius: Double? = nil, mode: Mode? = nil, preset: Preset? = nil) {
        self.accent = accent
        self.error = error
        self.focus = focus
        self.fontFamily = fontFamily
        self.fontSize = fontSize
        self.cornerRadius = cornerRadius
        self.mode = mode
        self.preset = preset
    }
}

// MARK: Device, metadata, page URL

/// The app and device a response came from: its user agent, and the response's `metadata`.
package struct QuestDeviceInfo: Equatable, Sendable {
    package var osVersion: String
    package var model: String
    package var appName: String
    package var appVersion: String
    package var build: String
    package var locale: String
    package var bundleIdentifier: String

    package init(osVersion: String, model: String, appName: String, appVersion: String, build: String,
                 locale: String, bundleIdentifier: String) {
        self.osVersion = osVersion
        self.model = model
        self.appName = appName
        self.appVersion = appVersion
        self.build = build
        self.locale = locale
        self.bundleIdentifier = bundleIdentifier
    }

    package var userAgent: String {
        "\(appName)/\(appVersion) (\(build); iOS \(osVersion); \(model)) QaidQuests/\(QaidQuestsSDK.version)"
    }

    /// The built-in keys stay flat, as the feedback SDK sends them; `user` and `custom`
    /// are nested and left out when empty. The view's own `metadata` goes on top, so a
    /// `feedbackId` it passes reaches the server (the only key the server reads).
    package func metadata(screen: String?, app: QuestAppMetadata, extra: [String: String]) -> QuestJSON {
        var out: [String: QuestJSON] = [
            "platform": "ios",
            "osVersion": .string(osVersion),
            "device": .string(model),
            "app": .string(appName),
            "appVersion": .string(appVersion),
            "build": .string(build),
            "locale": .string(locale),
            "sdk": .string("qaid-quests-ios/\(QaidQuestsSDK.version)"),
            "source": "native-quest",
        ]
        if let screen, !screen.isEmpty { out["screen"] = .string(screen) }
        if let user = app.user?.jsonObject { out["user"] = .object(user.mapValues(QuestJSON.string)) }
        if !app.custom.isEmpty { out["custom"] = .object(app.custom.mapValues(QuestJSON.string)) }
        for (key, value) in extra { out[key] = .string(value) }
        return .object(out)
    }
}

/// Who is using the app, as the app knows them. Sent as `metadata.user`.
package struct QuestUser: Equatable, Sendable {
    package var id: String?
    package var email: String?
    package var name: String?

    package init(id: String? = nil, email: String? = nil, name: String? = nil) {
        self.id = id
        self.email = email
        self.name = name
    }

    /// Only the fields that were set; nil when none were, so `user` is left out entirely.
    package var jsonObject: [String: String]? {
        var out: [String: String] = [:]
        for (key, value) in [("id", id), ("email", email), ("name", name)] {
            if let value, !value.isEmpty { out[key] = String(value.prefix(QuestAppMetadata.maxValueLength)) }
        }
        return out.isEmpty ? nil : out
    }
}

/// What the app adds to every response: the user and its own key/value pairs. The
/// same limits as the feedback SDK.
package struct QuestAppMetadata: Equatable, Sendable {
    package static let maxKeys = 30
    package static let maxKeyLength = 64
    package static let maxValueLength = 500

    package var user: QuestUser?
    package private(set) var custom: [String: String] = [:]

    package init(user: QuestUser? = nil) {
        self.user = user
    }

    /// Sets or (with nil) removes one pair. A 31st key is ignored; over-long keys and
    /// values are cut rather than refused, so a response never fails over metadata.
    package mutating func set(_ key: String, _ value: String?) {
        let key = String(key.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxKeyLength))
        guard !key.isEmpty else { return }
        guard let value else {
            custom.removeValue(forKey: key)
            return
        }
        guard custom[key] != nil || custom.count < Self.maxKeys else { return }
        custom[key] = String(value.prefix(Self.maxValueLength))
    }

    package mutating func removeAll() {
        custom.removeAll()
    }
}

/// The user and metadata the app sets between quests. Thread-safe.
package final class QuestMetadataStore: @unchecked Sendable {
    package static let shared = QuestMetadataStore()

    private let lock = NSLock()
    private var app = QuestAppMetadata()

    package init() {}

    package func setUser(_ user: QuestUser?) {
        lock.lock()
        defer { lock.unlock() }
        app.user = user?.jsonObject == nil ? nil : user
    }

    package func setMetadata(_ key: String, _ value: String?) {
        lock.lock()
        defer { lock.unlock() }
        app.set(key, value)
    }

    package func clearMetadata() {
        lock.lock()
        defer { lock.unlock() }
        app.removeAll()
    }

    package func snapshot() -> QuestAppMetadata {
        lock.lock()
        defer { lock.unlock() }
        return app
    }
}

package enum QuestPageUrl {
    /// The configured page URL, or `app://<bundle id>`, plus the screen.
    package static func make(config: QaidQuestsConfiguration, bundleIdentifier: String, screen: String?) -> String {
        make(base: config.pageUrl ?? QaidQuestsConfiguration.defaultPageUrl(bundleIdentifier: bundleIdentifier),
             screen: screen)
    }

    package static func make(base: URL, screen: String?) -> String {
        guard let screen, !slug(screen).isEmpty else { return base.absoluteString }
        var text = base.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        return "\(text)/\(slug(screen))"
    }

    /// "Call Sheets" → "call-sheets": lower case, letters and digits, single hyphens.
    package static func slug(_ text: String) -> String {
        var out = ""
        var pendingHyphen = false
        for scalar in text.lowercased().unicodeScalars {
            if scalar.isASCII && CharacterSet.alphanumerics.contains(scalar) {
                if pendingHyphen && !out.isEmpty { out.append("-") }
                pendingHyphen = false
                out.unicodeScalars.append(scalar)
            } else {
                pendingHyphen = true
            }
        }
        return String(out.prefix(60))
    }
}
