import Foundation

/// A JSON value. Quest definitions, theme documents, request bodies and the offline
/// queue all go through this one type, so decoding can be lenient (a wrong type is
/// read as missing, never a crash) and encoding is byte-for-byte the same on every
/// iOS version: `JSONSerialization` writes `12.0` as `12` on some releases and as
/// `12.0` on others.
package enum QuestJSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([QuestJSON])
    case object([String: QuestJSON])

    package static func parse(_ data: Data) throws -> QuestJSON {
        QuestJSON(any: try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    /// From what `JSONSerialization` returns. Anything else (it returns nothing else) is null.
    init(any: Any) {
        switch any {
        case let number as NSNumber:
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                self = .bool(number.boolValue)
            } else {
                self = .number(number.doubleValue)
            }
        case let text as String:
            self = .string(text)
        case let list as [Any]:
            self = .array(list.map(QuestJSON.init(any:)))
        case let dict as [String: Any]:
            self = .object(dict.mapValues(QuestJSON.init(any:)))
        default:
            self = .null
        }
    }

    package subscript(key: String) -> QuestJSON? {
        if case .object(let dict) = self { return dict[key] }
        return nil
    }

    package var stringValue: String? {
        if case .string(let text) = self { return text }
        return nil
    }

    package var numberValue: Double? {
        if case .number(let value) = self, value.isFinite { return value }
        return nil
    }

    package var boolValue: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }

    package var arrayValue: [QuestJSON]? {
        if case .array(let list) = self { return list }
        return nil
    }

    package var objectValue: [String: QuestJSON]? {
        if case .object(let dict) = self { return dict }
        return nil
    }

    /// JavaScript truthiness, for rule fields a hand-written quest may get wrong.
    package var isTruthy: Bool {
        switch self {
        case .null: return false
        case .bool(let value): return value
        case .number(let value): return value != 0 && !value.isNaN
        case .string(let text): return !text.isEmpty
        case .array, .object: return true
        }
    }

    // MARK: Encoding

    /// Compact JSON with sorted keys and unescaped slashes.
    package var text: String {
        switch self {
        case .null: return "null"
        case .bool(let value): return value ? "true" : "false"
        case .number(let value): return value.isFinite ? Self.number(value) : "null"
        case .string(let text): return Self.quote(text)
        case .array(let list): return "[" + list.map(\.text).joined(separator: ",") + "]"
        case .object(let dict):
            return "{" + dict.sorted { $0.key < $1.key }.map { Self.quote($0.key) + ":" + $0.value.text }
                .joined(separator: ",") + "}"
        }
    }

    package var data: Data { Data(text.utf8) }

    /// The way JavaScript's `String(n)` writes a number: `12`, `12.5`, `0.1`.
    package static func number(_ value: Double) -> String {
        if value == value.rounded(), abs(value) < 1e15 { return String(Int64(value)) }
        return "\(value)"
    }

    static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }
}

extension QuestJSON: ExpressibleByStringLiteral, ExpressibleByFloatLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByNilLiteral {
    package init(stringLiteral value: String) { self = .string(value) }
    package init(floatLiteral value: Double) { self = .number(value) }
    package init(integerLiteral value: Int) { self = .number(Double(value)) }
    package init(booleanLiteral value: Bool) { self = .bool(value) }
    package init(arrayLiteral elements: QuestJSON...) { self = .array(elements) }
    package init(dictionaryLiteral elements: (String, QuestJSON)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
    package init(nilLiteral: ()) { self = .null }
}
