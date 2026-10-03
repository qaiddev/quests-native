import Foundation

/// Accessibility identifiers on the quest's controls, for an app's own UI tests
/// (`app.buttons[QaidQuestTags.primary]`). The same strings as Android's `QaidQuestTags`
/// test tags.
public enum QaidQuestTags {
    public static let card = "qaid-quest"
    public static let primary = "qaid-quest-primary"
    public static let back = "qaid-quest-back"
    public static let close = "qaid-quest-close"
    public static let error = "qaid-quest-error"
    public static let progress = "qaid-quest-progress"
    public static let text = "qaid-quest-text"
    public static let currency = "qaid-quest-currency"
    public static let range = "qaid-quest-range"
    public static let date = "qaid-quest-date"
    public static let retry = "qaid-quest-retry"

    /// One option of a multiple-choice question, by its value.
    public static func option(_ value: String) -> String {
        "qaid-quest-option-\(value)"
    }
}
