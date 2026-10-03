import Foundation

/// One answer, as it goes on the wire: a string (text, date, single choice), a number
/// (currency, range), a list of option values (multiple choice), or null (cleared).
public enum QuestAnswer: Equatable, Sendable {
    case string(String)
    case number(Double)
    case strings([String])
    case null

    package var json: QuestJSON {
        switch self {
        case .string(let text): return .string(text)
        case .number(let value): return .number(value)
        case .strings(let list): return .array(list.map(QuestJSON.string))
        case .null: return .null
        }
    }

    /// Back from the offline queue. A shape no question type produces is nil.
    package init?(json: QuestJSON) {
        switch json {
        case .string(let text): self = .string(text)
        case .number(let value): self = .number(value)
        case .null: self = .null
        case .array(let list):
            let strings = list.compactMap(\.stringValue)
            guard strings.count == list.count else { return nil }
            self = .strings(strings)
        case .bool, .object: return nil
        }
    }
}

package enum ProgressPosition: String, Equatable, Sendable {
    case top, bottom
}

/// A quest definition, as `GET {base}/{id}/definition` returns it.
package struct Questionnaire: Equatable, Sendable {
    /// What a response is created against. The definition always carries it; the id the
    /// app asked for stands in when it doesn't.
    package var id: String
    package var slug: String?
    package var versionId: String?
    package var versionNumber: Int?
    package var title: String?
    package var description: String?
    package var hideTitle: Bool
    package var hideProgress: Bool
    package var progressPosition: ProgressPosition
    package var thankYouTitle: String?
    package var thankYouMessage: String?
    package var submitLabel: String?
    package var nextLabel: String?
    package var backLabel: String?
    package var questions: [QuestQuestion]

    package init(id: String, title: String? = nil, description: String? = nil, hideTitle: Bool = false,
                 hideProgress: Bool = false, progressPosition: ProgressPosition = .top,
                 thankYouTitle: String? = nil, thankYouMessage: String? = nil, submitLabel: String? = nil,
                 nextLabel: String? = nil, backLabel: String? = nil, questions: [QuestQuestion]) {
        self.id = id
        self.title = title
        self.description = description
        self.hideTitle = hideTitle
        self.hideProgress = hideProgress
        self.progressPosition = progressPosition
        self.thankYouTitle = thankYouTitle
        self.thankYouMessage = thankYouMessage
        self.submitLabel = submitLabel
        self.nextLabel = nextLabel
        self.backLabel = backLabel
        self.questions = questions
    }

    package func question(_ id: String) -> QuestQuestion? {
        questions.first { $0.id == id }
    }
}

package struct QuestQuestion: Equatable, Sendable {
    package var id: String
    package var label: String
    package var description: String?
    package var required: Bool
    package var visibleIf: VisibilityRule?
    package var kind: QuestionKind

    package init(id: String, label: String, description: String? = nil, required: Bool = false,
                 visibleIf: VisibilityRule? = nil, kind: QuestionKind) {
        self.id = id
        self.label = label
        self.description = description
        self.required = required
        self.visibleIf = visibleIf
        self.kind = kind
    }

    /// Typing collapses into one save per pause; a tap saves at once (as the web embed).
    package var debouncesSaves: Bool {
        switch kind {
        case .text, .currency, .range: return true
        case .date, .multipleChoice, .unknown: return false
        }
    }
}

package enum QuestionKind: Equatable, Sendable {
    case text(TextOptions)
    case currency(CurrencyOptions)
    case range(RangeOptions)
    case date(DateOptions)
    case multipleChoice(ChoiceOptions)
    /// A type this version can't draw: a newer server type, or a known type whose
    /// options are unusable (a range without bounds, a choice without options).
    case unknown(String)
}

package enum TextInputType: String, Equatable, Sendable {
    case text, email, tel, url
}

package struct TextOptions: Equatable, Sendable {
    package var placeholder: String?
    package var multiline = false
    package var maxLength: Int?
    package var minLength: Int?
    package var inputType = TextInputType.text

    package init(placeholder: String? = nil, multiline: Bool = false, maxLength: Int? = nil,
                 minLength: Int? = nil, inputType: TextInputType = .text) {
        self.placeholder = placeholder
        self.multiline = multiline
        self.maxLength = maxLength
        self.minLength = minLength
        self.inputType = inputType
    }
}

package struct CurrencyOptions: Equatable, Sendable {
    package var currency = "USD"
    package var locale: String?
    package var min: Double?
    package var max: Double?
    package var placeholder: String?

    package init(currency: String = "USD", locale: String? = nil, min: Double? = nil, max: Double? = nil,
                 placeholder: String? = nil) {
        self.currency = currency
        self.locale = locale
        self.min = min
        self.max = max
        self.placeholder = placeholder
    }
}

package struct RangeOptions: Equatable, Sendable {
    package var min: Double
    package var max: Double
    package var step: Double = 1
    package var defaultValue: Double?
    package var unit: String?

    package init(min: Double, max: Double, step: Double = 1, defaultValue: Double? = nil, unit: String? = nil) {
        self.min = min
        self.max = max
        self.step = step
        self.defaultValue = defaultValue
        self.unit = unit
    }
}

package struct DateOptions: Equatable, Sendable {
    /// `YYYY-MM-DD`; only bounds the picker, never validated (as the web embed).
    package var min: String?
    package var max: String?

    package init(min: String? = nil, max: String? = nil) {
        self.min = min
        self.max = max
    }
}

package enum ImageAlignment: String, Equatable, Sendable {
    case horizontal, vertical
}

package struct ChoiceOption: Equatable, Sendable {
    package var value: String
    package var label: String
    package var description: String?
    package var image: URL?

    package init(value: String, label: String, description: String? = nil, image: URL? = nil) {
        self.value = value
        self.label = label
        self.description = description
        self.image = image
    }
}

package struct ChoiceOptions: Equatable, Sendable {
    package var options: [ChoiceOption]
    package var multiple = false
    package var imageAlignment = ImageAlignment.horizontal

    package init(options: [ChoiceOption], multiple: Bool = false, imageAlignment: ImageAlignment = .horizontal) {
        self.options = options
        self.multiple = multiple
        self.imageAlignment = imageAlignment
    }

    /// The alignment only applies when some option has an image.
    package var hasImages: Bool { options.contains { $0.image != nil } }
}

/// A `visibleIf` predicate. `invalid` is a rule this SDK can't read; it evaluates to
/// false, which is what the web embed's evaluator returns for one.
package indirect enum VisibilityRule: Equatable, Sendable {
    case allOf([VisibilityRule])
    case anyOf([VisibilityRule])
    case equals(String, RuleValue)
    case notEquals(String, RuleValue)
    case oneOf(String, [RuleValue])
    case answered(String, Bool)
    case invalid
}

/// A rule's comparison target. Only strings and numbers can ever match an answer.
package enum RuleValue: Equatable, Sendable {
    case string(String)
    case number(Double)
    case other

    init(json: QuestJSON) {
        switch json {
        case .string(let text): self = .string(text)
        case .number(let value): self = .number(value)
        default: self = .other
        }
    }
}

// MARK: Decoding

/// Why a definition couldn't be used. The words come from `QaidQuestText`.
package enum QuestLoadFailure: Error, Equatable, Sendable {
    case status(Int)
    case empty
    case invalid
    case network
}

extension Questionnaire {
    /// Lenient: unknown fields are ignored, a field of the wrong type reads as missing,
    /// a question without an id (or with a repeated one) is dropped, and an unknown
    /// question type becomes `.unknown`. Fails only when nothing can be shown.
    package static func decode(_ data: Data, fallbackId: String) -> Result<Questionnaire, QuestLoadFailure> {
        guard let json = try? QuestJSON.parse(data), json.objectValue != nil else { return .failure(.invalid) }
        var seen = Set<String>()
        var questions: [QuestQuestion] = []
        for item in json["questions"]?.arrayValue ?? [] {
            guard let question = QuestQuestion.decode(item), seen.insert(question.id).inserted else { continue }
            questions.append(question)
        }
        guard !questions.isEmpty else { return .failure(.empty) }
        var quest = Questionnaire(
            id: nonBlank(json["id"]?.stringValue) ?? fallbackId,
            title: json["title"]?.stringValue,
            description: json["description"]?.stringValue,
            hideTitle: json["hideTitle"]?.boolValue ?? false,
            hideProgress: json["hideProgress"]?.boolValue ?? false,
            progressPosition: (json["progressPosition"]?.stringValue).flatMap(ProgressPosition.init) ?? .top,
            thankYouTitle: nonBlank(json["thankYouTitle"]?.stringValue),
            thankYouMessage: nonBlank(json["thankYouMessage"]?.stringValue),
            submitLabel: nonBlank(json["submitLabel"]?.stringValue),
            nextLabel: nonBlank(json["nextLabel"]?.stringValue),
            backLabel: nonBlank(json["backLabel"]?.stringValue),
            questions: questions
        )
        quest.slug = json["slug"]?.stringValue
        quest.versionId = json["versionId"]?.stringValue
        quest.versionNumber = json["versionNumber"]?.numberValue.flatMap(wholeNumber)
        return .success(quest)
    }
}

/// The number as an Int, rounded down; nil when it doesn't fit (`Int(1e300)` would trap).
package func wholeNumber(_ value: Double) -> Int? {
    guard abs(value) < 1e15 else { return nil }
    return Int(value.rounded(.down))
}

/// nil for nil, empty or whitespace-only text.
package func nonBlank(_ text: String?) -> String? {
    guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return text
}

extension QuestQuestion {
    package static func decode(_ json: QuestJSON) -> QuestQuestion? {
        guard let id = nonBlank(json["id"]?.stringValue) else { return nil }
        return QuestQuestion(
            id: id,
            label: json["label"]?.stringValue ?? "",
            description: json["description"]?.stringValue,
            required: json["required"]?.boolValue ?? false,
            visibleIf: json["visibleIf"].flatMap { $0 == .null ? nil : VisibilityRule.decode($0) },
            kind: QuestionKind.decode(json)
        )
    }
}

extension QuestionKind {
    package static func decode(_ json: QuestJSON) -> QuestionKind {
        let type = json["type"]?.stringValue ?? ""
        switch type {
        case "text":
            let max = json["maxLength"]?.numberValue.flatMap(wholeNumber).flatMap { $0 > 0 ? $0 : nil }
            let min = json["minLength"]?.numberValue.flatMap(wholeNumber).flatMap { $0 >= 0 ? $0 : nil }
            return .text(TextOptions(
                placeholder: json["placeholder"]?.stringValue,
                multiline: json["multiline"]?.boolValue ?? false,
                maxLength: max,
                minLength: min,
                inputType: (json["inputType"]?.stringValue).flatMap(TextInputType.init) ?? .text
            ))
        case "currency":
            let code = json["currency"]?.stringValue ?? ""
            let valid = code.count == 3 && code.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) }
            return .currency(CurrencyOptions(
                currency: valid ? code : "USD",
                locale: nonBlank(json["locale"]?.stringValue),
                min: json["min"]?.numberValue,
                max: json["max"]?.numberValue,
                placeholder: json["placeholder"]?.stringValue
            ))
        case "range":
            guard let min = json["min"]?.numberValue, let max = json["max"]?.numberValue, min < max else {
                return .unknown(type)
            }
            let step = json["step"]?.numberValue.flatMap { $0 > 0 ? $0 : nil } ?? 1
            return .range(RangeOptions(min: min, max: max, step: step,
                                       defaultValue: json["defaultValue"]?.numberValue,
                                       unit: json["unit"]?.stringValue))
        case "date":
            return .date(DateOptions(
                min: json["min"]?.stringValue.flatMap { QuestDate.isWire($0) ? $0 : nil },
                max: json["max"]?.stringValue.flatMap { QuestDate.isWire($0) ? $0 : nil }
            ))
        case "multiple-choice":
            var values = Set<String>()
            var options: [ChoiceOption] = []
            for item in json["options"]?.arrayValue ?? [] {
                guard let value = nonBlank(item["value"]?.stringValue), values.insert(value).inserted else { continue }
                options.append(ChoiceOption(
                    value: value,
                    label: nonBlank(item["label"]?.stringValue) ?? value,
                    description: item["description"]?.stringValue,
                    image: nonBlank(item["image"]?.stringValue).flatMap(URL.init(string:))
                ))
            }
            guard !options.isEmpty else { return .unknown(type) }
            return .multipleChoice(ChoiceOptions(
                options: options,
                multiple: json["multiple"]?.boolValue ?? false,
                imageAlignment: (json["imageAlignment"]?.stringValue).flatMap(ImageAlignment.init) ?? .horizontal
            ))
        default:
            return .unknown(type)
        }
    }

    /// The type name, for logs.
    package var typeName: String {
        switch self {
        case .text: return "text"
        case .currency: return "currency"
        case .range: return "range"
        case .date: return "date"
        case .multipleChoice: return "multiple-choice"
        case .unknown(let type): return type
        }
    }
}

extension VisibilityRule {
    /// Reads keys in the web evaluator's order: `allOf`, `anyOf`, then the atom forms.
    package static func decode(_ json: QuestJSON) -> VisibilityRule {
        guard let dict = json.objectValue else { return .invalid }
        if let all = dict["allOf"] {
            return all.arrayValue.map { .allOf($0.map(decode)) } ?? .invalid
        }
        if let any = dict["anyOf"] {
            return any.arrayValue.map { .anyOf($0.map(decode)) } ?? .invalid
        }
        let questionId = dict["questionId"]?.stringValue ?? ""
        if let answered = dict["answered"] { return .answered(questionId, answered.isTruthy) }
        if let target = dict["equals"] { return .equals(questionId, RuleValue(json: target)) }
        if let target = dict["notEquals"] { return .notEquals(questionId, RuleValue(json: target)) }
        if let targets = dict["in"] {
            return targets.arrayValue.map { .oneOf(questionId, $0.map(RuleValue.init(json:))) } ?? .invalid
        }
        return .invalid
    }
}
