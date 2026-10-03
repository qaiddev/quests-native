# QaidQuests (iOS) 0.1.0 — public API

SwiftPM product `QaidQuests` (iOS 15+). `import QaidQuests` re-exports the public types of
`QaidQuestsCore`. Names, options, text keys, error cases and operations match the Android SDK
(`dev.qaid:quests`); only the type idioms differ (CSS colour strings here, ARGB ints there).

## QaidQuests (`@MainActor public enum`)

```swift
public static private(set) var configuration: QaidQuestsConfiguration?
public static var isConfigured: Bool { get }
public static var onError: ((QaidQuestsErrorEvent) -> Void)?
public static func configure(_ configuration: QaidQuestsConfiguration)
public static func present(questId: String, from presenter: UIViewController? = nil, screen: String? = nil,
                           metadata: [String: String] = [:],
                           onComplete: (([String: QuestAnswer]) -> Void)? = nil,
                           onClose: (() -> Void)? = nil)
public static func flushOfflineQueue()
nonisolated public static func setUser(id: String? = nil, email: String? = nil, name: String? = nil)
nonisolated public static func clearUser()
nonisolated public static func setMetadata(key: String, value: String?)
nonisolated public static func clearMetadata()
```

## Views

```swift
public struct QaidQuestView: View {
    public init(questId: String, screen: String? = nil, metadata: [String: String] = [:],
                onComplete: (([String: QuestAnswer]) -> Void)? = nil, onClose: (() -> Void)? = nil)
}

public final class QaidQuestViewController: UIViewController, UIAdaptivePresentationControllerDelegate {
    public let questId: String
    public var onClose: (() -> Void)?
    public init(questId: String, screen: String? = nil, metadata: [String: String] = [:],
                onComplete: (([String: QuestAnswer]) -> Void)? = nil, onClose: (() -> Void)? = nil)
}

/// Accessibility identifiers, the same strings as Android's test tags.
public enum QaidQuestTags {
    static let card, primary, back, close, error, progress, text, currency, range, date, retry: String
    static func option(_ value: String) -> String
}
```

## Configuration

```swift
public struct QaidQuestsConfiguration: Equatable, Sendable {
    public var apiKey: String
    public var appName: String
    public var base: URL
    public var pageUrl: URL?
    public var theme: QaidQuestTheme?
    public var themeId: String?
    public var themeVersion: Int?
    public var text: QaidQuestText
    public var autoAdvance: Bool
    public var progressPosition: QaidQuestProgressPosition?
    public var visitorId: String?
    public var saveDebounceMs: Int
    public init(apiKey: String, appName: String = "", base: URL = QaidQuestsConfiguration.defaultBase,
                pageUrl: URL? = nil, theme: QaidQuestTheme? = nil, themeId: String? = nil, themeVersion: Int? = nil,
                text: QaidQuestText = QaidQuestText(), autoAdvance: Bool = false,
                progressPosition: QaidQuestProgressPosition? = nil, visitorId: String? = nil, saveDebounceMs: Int = 500)
    public static let defaultBase: URL   // https://qaid.dev/api/quests
    public static func defaultPageUrl(bundleIdentifier: String) -> URL
}

public enum QaidQuestProgressPosition: String, Equatable, Sendable { case top, bottom }

public struct QaidQuestTheme: Equatable, Sendable {
    public enum Mode: String, Equatable, Sendable { case auto, light, dark }
    public enum Preset: String, Equatable, Sendable, CaseIterable { case `default`, minimal, pill, dense }
    public var accent: String?        // CSS colour
    public var error: String?
    public var focus: String?
    public var fontFamily: String?    // CSS font-family list
    public var fontSize: Double?      // base size in points (qaid's default 16); scales with Dynamic Type
    public var cornerRadius: Double?  // points: card, buttons, options, fields
    public var mode: Mode?
    public var preset: Preset?
    public init(accent: String? = nil, error: String? = nil, focus: String? = nil, fontFamily: String? = nil,
                fontSize: Double? = nil, cornerRadius: Double? = nil, mode: Mode? = nil, preset: Preset? = nil)
}

public struct QaidQuestText: Equatable, Sendable   // every drawn string; memberwise init, all defaulted:
// next, back, submit, thankYouTitle, close,
// validationRequired, validationMinLength ({count}), validationAmount, validationDate,
// validationSelectOne, validationSelectMany,
// loading, loadErrorTitle, loadErrorStatus ({status}), loadErrorEmpty, loadErrorInvalid, loadErrorOffline,
// retry, submitErrorTitle, submitErrorMessage, tryAgain, sending, saving, savedOffline, notConfigured,
// unsupportedQuestion, datePlaceholder, clearDate, dateConfirm, dateCancel (Android's dialog only),
// required, closeForm, progressLabel, progressValue ({n}, {total}), stepAnnouncement ({n}, {total})

public enum QaidQuestsSDK { public static let version: String }   // "0.1.0"
```

## Answers and errors

```swift
public enum QuestAnswer: Equatable, Sendable {
    case string(String)     // text, date (YYYY-MM-DD), single choice
    case number(Double)     // currency, range
    case strings([String])  // multiple choice, in pick order
    case null
}

public enum QaidQuestsError: Error, Equatable, Sendable {
    case network(String)
    case server(Int)
    case rejected(status: Int, message: String)
    case invalidResponse(String)
    case storage(String)
    public var isRetryable: Bool { get }   // network, server, 408, 429
}

public struct QaidQuestsErrorEvent: Equatable, Sendable {
    public enum Operation: String, Equatable, Sendable {
        case loadQuest, loadTheme, createResponse, saveAnswer, submit, offlineQueue
    }
    public var questId: String
    public var operation: Operation
    public var questionId: String?    // saveAnswer
    public var error: QaidQuestsError
    public init(questId: String, operation: Operation, questionId: String? = nil, error: QaidQuestsError)
}
```

## Notes

- Theme documents' `css` and `unstyled` are ignored natively (logged); every control is drawn by the SDK.
- A theme that can't be loaded is reported as `loadTheme` and the quest draws with qaid's defaults.
- Requests never come from the URL cache (the definition is served with `max-age=60`).
- Submit: once a call has failed on the network, submit goes straight to the offline queue; it
  never stays on "Sending…" more than 20 s before queueing. A queued response is flushed at
  once (in case the network is back), on `configure`, on foreground and when the network returns.
- Text lengths (`minLength`, `maxLength`) count UTF-16 units and blanks use JavaScript's
  whitespace, as the web embed and Android do. Currency reads the device's decimal separator
  and `.`; a grouped number ("1,000" in English) is sent as null.
- The visitor id is shared with the QaidFeedback SDK (`UserDefaults` key `dev.qaid.feedback.visitorId`).
- Offline queue: `Application Support/QaidQuests/queue/`, excluded from backup, 20 responses, 7 days.
