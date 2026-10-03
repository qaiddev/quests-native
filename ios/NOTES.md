# QaidQuests (iOS) 0.1.0 — public API

SwiftPM product `QaidQuests` (iOS 15+). `import QaidQuests` re-exports the public types of
`QaidQuestsCore`.

## QaidQuests (`@MainActor public enum`)

```swift
public static private(set) var configuration: QaidQuestsConfiguration?
public static var isConfigured: Bool { get }
public static var onError: ((QaidQuestsErrorReport) -> Void)?
public static func configure(_ configuration: QaidQuestsConfiguration)
public static func present(questId: String, from presenter: UIViewController? = nil, screen: String? = nil,
                           metadata: [String: String] = [:],
                           onComplete: (([String: QuestAnswer]) -> Void)? = nil)
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
                onComplete: (([String: QuestAnswer]) -> Void)? = nil)
}
```

## Configuration

```swift
public struct QaidQuestsConfiguration: Equatable, Sendable {
    public var apiKey: String
    public var base: URL
    public var pageUrl: URL?
    public var appName: String
    public var theme: QaidQuestTheme?
    public var themeId: String?
    public var text: QaidQuestText
    public var autoAdvance: Bool
    public var visitorId: String?
    public init(apiKey: String, base: URL = QaidQuestsConfiguration.defaultBase, pageUrl: URL? = nil,
                appName: String = "", theme: QaidQuestTheme? = nil, themeId: String? = nil,
                text: QaidQuestText = QaidQuestText(), autoAdvance: Bool = false, visitorId: String? = nil)
    public static let defaultBase: URL   // https://qaid.dev/api/quests
    public static func defaultPageUrl(bundleIdentifier: String) -> URL
}

public struct QaidQuestTheme: Equatable, Sendable {
    public enum Mode: String, Equatable, Sendable { case auto, light, dark }
    public enum Preset: String, Equatable, Sendable { case `default`, minimal, pill, dense }
    public var accent: String?        // CSS colour
    public var error: String?
    public var focus: String?
    public var fontFamily: String?    // CSS font-family list
    public var cornerRadius: Double?  // points: card, buttons, options, fields
    public var mode: Mode?
    public var preset: Preset?
    public init(accent: String? = nil, error: String? = nil, focus: String? = nil, fontFamily: String? = nil,
                cornerRadius: Double? = nil, mode: Mode? = nil, preset: Preset? = nil)
}

public struct QaidQuestText: Equatable, Sendable   // every drawn string; memberwise init, all defaulted:
// next, back, submit, thankYouTitle, close,
// validationRequired, validationMinLength ({count}), validationAmount, validationDate,
// validationSelectOne, validationSelectMany,
// loading, loadErrorTitle, loadErrorStatus ({status}), loadErrorEmpty, loadErrorInvalid, loadErrorOffline,
// retry, submitErrorTitle, submitErrorMessage, tryAgain, sending, saving, savedOffline, notConfigured,
// unsupportedQuestion, datePlaceholder, clearDate, required, closeForm, progressLabel,
// progressValue ({n}, {total}), stepAnnouncement ({n}, {total})

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
    public var isRetryable: Bool { get }
}

public struct QaidQuestsErrorReport: Equatable, Sendable {
    public enum Operation: String, Equatable, Sendable {
        case loadQuest, loadTheme, createResponse, saveAnswer, submit, offlineQueue
    }
    public var operation: Operation
    public var questId: String
    public var questionId: String?
    public var error: QaidQuestsError
    public init(operation: Operation, questId: String, questionId: String? = nil, error: QaidQuestsError)
}
```

## Notes

- Theme documents' `css` and `unstyled` are ignored natively; every control is drawn by the SDK.
- The visitor id is shared with the QaidFeedback SDK (`UserDefaults` key `dev.qaid.feedback.visitorId`).
- Offline queue: `Application Support/QaidQuests/queue/`, excluded from backup, 20 responses, 7 days.
