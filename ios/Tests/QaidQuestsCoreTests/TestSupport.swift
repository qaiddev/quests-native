import Foundation
import XCTest
@testable import QaidQuestsCore

/// A transport that records every request and answers from a script. Never the network.
final class ScriptedTransport: QuestTransport, @unchecked Sendable {
    typealias Handler = (QuestHTTPRequest) throws -> QuestHTTPResponse

    private let lock = NSLock()
    private var log: [QuestHTTPRequest] = []
    private var current: Handler

    init(_ handler: @escaping Handler = ScriptedTransport.server()) {
        current = handler
    }

    var handler: Handler {
        get { lock.lock(); defer { lock.unlock() }; return current }
        set { lock.lock(); current = newValue; lock.unlock() }
    }

    var requests: [QuestHTTPRequest] {
        lock.lock()
        defer { lock.unlock() }
        return log
    }

    /// `METHOD path`, in send order.
    var calls: [String] { requests.map { "\($0.method) \($0.url.path)" } }

    /// The PATCH bodies, as `questionId=value`.
    var patches: [String] {
        requests.filter { $0.method == "PATCH" }.map { request in
            let json = body(request)
            return "\(json["questionId"]?.stringValue ?? "?")=\(json["value"]?.text ?? "?")"
        }
    }

    func send(_ request: QuestHTTPRequest) async throws -> QuestHTTPResponse {
        try record(request)(request)
    }

    private func record(_ request: QuestHTTPRequest) -> Handler {
        lock.lock()
        defer { lock.unlock() }
        log.append(request)
        return current
    }

    /// A qaid that accepts everything: the definition, the theme, create, PATCH, submit.
    static func server(quest: String = Fixtures.questJSON, theme: String? = nil, responseId: String = "resp_1") -> Handler {
        { request in
            let path = request.url.path
            switch request.method {
            case "GET" where path.hasSuffix("/definition"):
                return QuestHTTPResponse(status: 200, body: Data(quest.utf8))
            case "GET" where path.hasPrefix("/api/quest-themes/"):
                guard let theme else { return QuestHTTPResponse(status: 404, body: Data(#"{"error":"Theme not found"}"#.utf8)) }
                return QuestHTTPResponse(status: 200, body: Data(theme.utf8))
            case "POST" where path.hasSuffix("/responses"):
                return QuestHTTPResponse(status: 201, body: Data(#"{"id":"\#(responseId)"}"#.utf8))
            case "PATCH", "POST":
                return QuestHTTPResponse(status: 200, body: Data(#"{"ok":true}"#.utf8))
            default:
                return QuestHTTPResponse(status: 405)
            }
        }
    }
}

struct Offline: Error {}

func body(_ request: QuestHTTPRequest) -> QuestJSON {
    (try? QuestJSON.parse(request.body ?? Data())) ?? .null
}

func json(_ text: String) -> QuestJSON {
    (try? QuestJSON.parse(Data(text.utf8))) ?? .null
}

func temporaryDirectory() -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("qaid-quests-tests-\(UUID().uuidString)")
    return url
}

let noSleep: QuestSleep = { _ in }

enum Fixtures {
    static let questJSON = """
    {"id":"quest_1","slug":"onboarding","versionId":"v1","versionNumber":3,"title":"Tell us",
     "description":"Two minutes","unknownField":{"x":1},"questions":[
      {"id":"name","type":"text","label":"Your name","required":true,"minLength":2,"maxLength":10},
      {"id":"budget","type":"currency","label":"Budget","currency":"EUR","min":0,"max":100},
      {"id":"score","type":"range","label":"Score","min":0,"max":10,"step":1,"defaultValue":5,"unit":"/10"},
      {"id":"when","type":"date","label":"When","min":"2026-01-01","max":"2026-12-31"},
      {"id":"color","type":"multiple-choice","label":"Colour","required":true,
       "options":[{"value":"red","label":"Red"},{"value":"blue","label":"Blue","description":"Calm"}]},
      {"id":"why","type":"text","label":"Why blue?","multiline":true,
       "visibleIf":{"questionId":"color","equals":"blue"}}
    ]}
    """

    static var quest: Questionnaire {
        guard case .success(let quest) = Questionnaire.decode(Data(questJSON.utf8), fallbackId: "quest_1") else {
            fatalError("fixture")
        }
        return quest
    }

    static func quest(_ questions: [QuestQuestion], title: String? = "T") -> Questionnaire {
        Questionnaire(id: "q", title: title, questions: questions)
    }

    static func text(_ id: String, required: Bool = false, minLength: Int? = nil, maxLength: Int? = nil,
                     visibleIf: VisibilityRule? = nil) -> QuestQuestion {
        QuestQuestion(id: id, label: id.capitalized, required: required, visibleIf: visibleIf,
                      kind: .text(TextOptions(maxLength: maxLength, minLength: minLength)))
    }

    static func choice(_ id: String, _ values: [String], multiple: Bool = false, required: Bool = false,
                       visibleIf: VisibilityRule? = nil) -> QuestQuestion {
        QuestQuestion(id: id, label: id, required: required, visibleIf: visibleIf,
                      kind: .multipleChoice(ChoiceOptions(options: values.map { ChoiceOption(value: $0, label: $0) },
                                                          multiple: multiple)))
    }

    static let device = QuestDeviceInfo(osVersion: "18.1", model: "iPhone15,2", appName: "Crew", appVersion: "1.4",
                                        build: "812", locale: "en_US", bundleIdentifier: "com.Example.Crew")

    static func config(themeId: String? = nil, theme: QaidQuestTheme? = nil, autoAdvance: Bool = false) -> QaidQuestsConfiguration {
        QaidQuestsConfiguration(apiKey: "key_1", appName: "Crew", theme: theme, themeId: themeId, autoAdvance: autoAdvance)
    }
}
