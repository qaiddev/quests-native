import Foundation

/// `visibleIf`, exactly as the web embed's `visibility.ts` evaluates it.
///
/// Questions resolve in order, and a rule only sees the answers of questions already
/// resolved visible: a hidden question reads as unanswered whatever was typed into
/// it, so a chain of follow-ups collapses behind it. An unknown or later question id
/// reads as unanswered too. Nothing here throws.
package enum QuestVisibility {
    package static func visibleQuestions(_ questions: [QuestQuestion],
                                         answers: [String: QuestAnswer]) -> [QuestQuestion] {
        var seen: [String: QuestAnswer] = [:]
        var visible: [QuestQuestion] = []
        for question in questions {
            if let rule = question.visibleIf, !evaluate(rule, answers: seen) { continue }
            visible.append(question)
            seen[question.id] = answers[question.id]
        }
        return visible
    }

    /// What is submitted: the answers of the questions visible now, and nothing else.
    package static func visibleAnswers(_ questions: [QuestQuestion],
                                       answers: [String: QuestAnswer]) -> [String: QuestAnswer] {
        var out: [String: QuestAnswer] = [:]
        for question in visibleQuestions(questions, answers: answers) {
            if let value = answers[question.id] { out[question.id] = value }
        }
        return out
    }

    package static func evaluate(_ rule: VisibilityRule, answers: [String: QuestAnswer]) -> Bool {
        switch rule {
        case .allOf(let rules):
            return rules.allSatisfy { evaluate($0, answers: answers) }
        case .anyOf(let rules):
            return rules.contains { evaluate($0, answers: answers) }
        case let .answered(id, wanted):
            return isAnswered(answers[id]) == wanted
        case let .equals(id, target):
            // A comparison against an unanswered question is false — notEquals too, so a
            // "not Y" follow-up doesn't appear before X is answered at all.
            guard let value = answers[id], isAnswered(value) else { return false }
            return matches(value, target)
        case let .notEquals(id, target):
            guard let value = answers[id], isAnswered(value) else { return false }
            return !matches(value, target)
        case let .oneOf(id, targets):
            guard let value = answers[id], isAnswered(value) else { return false }
            return targets.contains { matches(value, $0) }
        case .invalid:
            return false
        }
    }

    /// Not null, not a whitespace-only string (JavaScript's whitespace), not an empty list.
    /// `0` is an answer.
    package static func isAnswered(_ value: QuestAnswer?) -> Bool {
        switch value {
        case nil, .null?: return false
        case .string(let text)?: return !JSText.isBlank(text)
        case .strings(let list)?: return !list.isEmpty
        case .number?: return true
        }
    }

    /// Strict equality, no coercion, no trimming. A multi-select matches when any
    /// selected value does.
    static func matches(_ value: QuestAnswer, _ target: RuleValue) -> Bool {
        switch (value, target) {
        case let (.string(text), .string(wanted)): return text == wanted
        case let (.number(number), .number(wanted)): return number == wanted
        case let (.strings(list), .string(wanted)): return list.contains(wanted)
        default: return false
        }
    }
}
