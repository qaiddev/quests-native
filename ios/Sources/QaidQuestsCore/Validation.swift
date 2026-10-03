import Foundation

/// The checks the web embed runs on Next, with its exact messages. Nothing stricter:
/// email/url/tel format, date bounds, currency decimals and a non-required
/// `minLength` are not checked there, so they are not checked here.
package enum QuestValidation {
    /// The message to show, or nil when the answer may go on.
    package static func message(for question: QuestQuestion, value: QuestAnswer?, text: QaidQuestText) -> String? {
        switch question.kind {
        case .text(let options):
            guard question.required else { return nil }
            let trimmed: String
            if case .string(let typed)? = value {
                trimmed = typed.trimmingCharacters(in: .whitespacesAndNewlines)
            } else {
                trimmed = ""
            }
            let minLength = options.minLength ?? 0
            guard trimmed.isEmpty || trimmed.count < minLength else { return nil }
            // The web embed names the length whenever one is set, even for an empty field.
            return minLength > 0 ? text.minLengthMessage(minLength) : text.validationRequired
        case .currency(let options):
            guard case .number(let amount)? = value else {
                return question.required ? text.validationAmount : nil
            }
            if let min = options.min, amount < min { return text.validationAmount }
            if let max = options.max, amount > max { return text.validationAmount }
            return nil
        case .date:
            guard question.required, !QuestVisibility.isAnswered(value) else { return nil }
            return text.validationDate
        case .multipleChoice(let options):
            guard question.required, !QuestVisibility.isAnswered(value) else { return nil }
            return options.multiple ? text.validationSelectMany : text.validationSelectOne
        case .range, .unknown:
            // A range always holds a value; an unknown question is never required.
            return nil
        }
    }
}

/// The range slider's arithmetic, as an HTML `<input type=range>` does it.
package enum RangeMath {
    /// Snapped to the step grid from `min`, clamped to `min` and the last step that
    /// fits under `max`. Rounded to 10 places so 0.1 steps don't drift.
    package static func snap(_ value: Double, options: RangeOptions) -> Double {
        let steps = ((value - options.min) / options.step).rounded(.toNearestOrAwayFromZero)
        let last = options.min + ((options.max - options.min) / options.step).rounded(.down) * options.step
        let snapped = Swift.min(Swift.max(options.min + steps * options.step, options.min), last)
        return (snapped * 1e10).rounded() / 1e10
    }

    /// The slider's value when a step is shown: the answer if there is one, else
    /// `defaultValue`, else `min`.
    package static func initialValue(_ options: RangeOptions, answer: QuestAnswer?) -> Double {
        if case .number(let value)? = answer { return snap(value, options: options) }
        return snap(options.defaultValue ?? options.min, options: options)
    }

    /// `5`, `2.5`, `5%` — the value and bound labels.
    package static func label(_ value: Double, unit: String?) -> String {
        QuestJSON.number(value) + (unit ?? "")
    }
}

/// The currency field: its symbol, and reading what was typed.
package enum CurrencyFormat {
    /// The narrow symbol (`$`, `€`) for `code` in `locale`, or the code itself.
    package static func symbol(code: String, locale: Locale) -> String {
        let zero = Decimal(0).formatted(
            .currency(code: code).presentation(.narrow).precision(.fractionLength(0)).locale(locale)
        )
        let symbol = zero.unicodeScalars
            .filter { !CharacterSet.decimalDigits.contains($0) && !CharacterSet.whitespaces.contains($0) }
        let text = String(String.UnicodeScalarView(symbol))
        return text.isEmpty ? code : text
    }

    /// The amount typed, or nil for an empty or unreadable field (the web embed sends
    /// null for both). The locale's decimal separator and `.` are both accepted.
    package static func parse(_ typed: String, locale: Locale) -> Double? {
        var text = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if let separator = locale.decimalSeparator, separator != "." {
            text = text.replacingOccurrences(of: separator, with: ".")
        }
        guard !text.isEmpty, let value = Double(text), value.isFinite else { return nil }
        return value
    }

    /// What the field shows for a stored amount.
    package static func display(_ answer: QuestAnswer?) -> String {
        guard case .number(let value)? = answer else { return "" }
        return QuestJSON.number(value)
    }
}

/// `YYYY-MM-DD`, the date wire format, in the Gregorian calendar of the device's zone.
package enum QuestDate {
    package static func isWire(_ text: String) -> Bool {
        components(text) != nil
    }

    static func components(_ text: String) -> DateComponents? {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              parts.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              (1...12).contains(month), (1...31).contains(day) else { return nil }
        return DateComponents(year: year, month: month, day: day)
    }

    package static func calendar(timeZone: TimeZone = .current) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar
    }

    /// Midnight of that day in `calendar`'s zone, or nil for text that isn't a date.
    package static func date(_ wire: String, calendar: Calendar) -> Date? {
        guard let parts = components(wire), let date = calendar.date(from: parts),
              calendar.component(.day, from: date) == parts.day else { return nil }
        return date
    }

    package static func wire(_ date: Date, calendar: Calendar) -> String {
        String(format: "%04d-%02d-%02d", calendar.component(.year, from: date),
               calendar.component(.month, from: date), calendar.component(.day, from: date))
    }

    /// What a first tap on an empty date field picks: today, kept inside the bounds.
    package static func initial(today: Date, options: DateOptions, calendar: Calendar) -> Date {
        var day = calendar.startOfDay(for: today)
        if let min = options.min.flatMap({ date($0, calendar: calendar) }), day < min { day = min }
        if let max = options.max.flatMap({ date($0, calendar: calendar) }), day > max { day = max }
        return day
    }
}
