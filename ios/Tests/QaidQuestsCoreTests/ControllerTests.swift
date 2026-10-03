import XCTest
@testable import QaidQuestsCore

@MainActor
final class ControllerTests: XCTestCase {
    var root: URL!
    var announcements: [String] = []
    var alerts: [String] = []
    var completed: [[String: QuestAnswer]] = []
    var reports: [QaidQuestsErrorReport] = []
    var logs: [String] = []
    var changes = 0

    override func setUp() async throws {
        root = temporaryDirectory()
        announcements = []
        alerts = []
        completed = []
        reports = []
        logs = []
        changes = 0
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func controller(_ transport: ScriptedTransport, config: QaidQuestsConfiguration = Fixtures.config(),
                            screen: String? = "Settings", metadata: [String: String] = ["feedbackId": "fb_9"],
                            app: QuestAppMetadata = QuestAppMetadata()) -> QuestController {
        let environment = QuestEnvironment(config: config, device: Fixtures.device, visitorId: "visitor_1",
                                           appMetadata: app, transport: transport, store: OfflineQuestStore(root: root),
                                           retry: QuestRetryPolicy(delays: []), debounce: 0, autoAdvanceDelay: 0,
                                           sleep: noSleep)
        let controller = QuestController(questId: "quest_1", screen: screen, metadata: metadata, environment: environment)
        controller.onAnnounce = { words, assertive in
            if assertive { self.alerts.append(words) } else { self.announcements.append(words) }
        }
        controller.onComplete = { self.completed.append($0) }
        controller.onError = { self.reports.append($0) }
        controller.onLog = { self.logs.append($0) }
        controller.onChange = { self.changes += 1 }
        return controller
    }

    func testNothingIsSentUntilStart() async {
        let transport = ScriptedTransport()
        let controller = controller(transport)
        controller.next()
        controller.close()
        await controller.waitUntilIdle()
        XCTAssertEqual(controller.screen, .idle)
        XCTAssertTrue(transport.requests.isEmpty)
        XCTAssertNil(controller.session)
    }

    func testFullRunFromLoadToThankYou() async throws {
        let transport = ScriptedTransport()
        var app = QuestAppMetadata(user: QuestUser(id: "u1"))
        app.set("plan", "pro")
        let controller = controller(transport, app: app)
        await controller.start()
        XCTAssertEqual(controller.screen, .quest)
        XCTAssertEqual(announcements.prefix(2), ["Loading…", "Step 1 of 5"])
        XCTAssertEqual(controller.titleFocus, 1)
        XCTAssertEqual(controller.text, QaidQuestText())

        controller.next()
        XCTAssertEqual(alerts, ["Please enter at least 2 characters."])
        XCTAssertEqual(controller.controlFocus, 1)
        controller.setText("name", "Jo")
        controller.next()
        controller.setAmount("budget", 40)
        controller.next()
        controller.setRange("score", 8)
        controller.next()
        controller.setDate("when", "2026-06-01")
        controller.next()
        controller.select("color", option: "blue")
        controller.next()
        controller.setText("why", "calm")
        controller.back()
        controller.select("color", option: "red")
        controller.next()
        await controller.waitUntilIdle()

        XCTAssertEqual(controller.session?.phase, .done(savedOffline: false))
        let expected: [String: QuestAnswer] = ["name": .string("Jo"), "budget": .number(40), "score": .number(8),
                                               "when": .string("2026-06-01"), "color": .string("red")]
        XCTAssertEqual(completed, [expected])
        XCTAssertEqual(announcements.last, "Thank you!")
        XCTAssertFalse(controller.saving)
        XCTAssertGreaterThan(changes, 10)

        let create = transport.requests[1]
        XCTAssertEqual(transport.calls[0], "GET /api/quests/quest_1/definition")
        XCTAssertEqual(create.method, "POST")
        let createBody = body(create)
        XCTAssertEqual(createBody["apiKey"], "key_1")
        XCTAssertEqual(createBody["questId"], "quest_1")
        XCTAssertEqual(createBody["pageUrl"], "app://com.example.crew/settings")
        XCTAssertEqual(createBody["visitorId"], "visitor_1")
        XCTAssertEqual(createBody["userAgent"], "Crew/1.4 (812; iOS 18.1; iPhone15,2) QaidQuests/0.1.0")
        XCTAssertEqual(createBody["metadata"]?["feedbackId"], "fb_9")
        XCTAssertEqual(createBody["metadata"]?["screen"], "Settings")
        XCTAssertEqual(createBody["metadata"]?["user"], ["id": "u1"])
        XCTAssertEqual(createBody["metadata"]?["custom"], ["plan": "pro"])
        XCTAssertEqual(transport.patches, [#"name="Jo""#,
                                           "budget=40", "score=8", #"when="2026-06-01""#, #"color="blue""#,
                                           #"why="calm""#, #"color="red""#, "why=null"])
        XCTAssertEqual(transport.calls.last, "POST /api/quests/responses/resp_1/submit")
        XCTAssertEqual(body(transport.requests.last!)["answers"], .object(expected.mapValues(\.json)))
        XCTAssertTrue(reports.isEmpty)
    }

    func testLoadFailureShowsRetry() async {
        var fail = true
        let transport = ScriptedTransport { request in
            if fail { return QuestHTTPResponse(status: 404, body: Data(#"{"error":"Quest not found"}"#.utf8)) }
            return try ScriptedTransport.server()(request)
        }
        let controller = controller(transport)
        await controller.start()
        XCTAssertEqual(controller.screen, .loadFailed("Failed to load questionnaire (404)"))
        XCTAssertEqual(alerts, ["Couldn't load form. Failed to load questionnaire (404)"])
        XCTAssertEqual(reports, [QaidQuestsErrorReport(operation: .loadQuest, questId: "quest_1",
                                                       error: .rejected(status: 404, message: ""))])
        XCTAssertEqual(transport.requests.count, 1)
        await controller.start()
        XCTAssertEqual(transport.requests.count, 1)
        fail = false
        await controller.retryLoad()
        XCTAssertEqual(controller.screen, .quest)
        await controller.retryLoad()
        await controller.waitUntilIdle()
        XCTAssertEqual(transport.calls.filter { $0.hasSuffix("/definition") }.count, 2)
    }

    func testLoadFailureKinds() {
        XCTAssertEqual(QuestController.error(for: .status(503)), .server(503))
        XCTAssertEqual(QuestController.error(for: .status(400)), .rejected(status: 400, message: ""))
        XCTAssertEqual(QuestController.error(for: .empty), .invalidResponse("The quest has no questions"))
        XCTAssertEqual(QuestController.error(for: .invalid), .invalidResponse("The quest definition couldn't be read"))
        XCTAssertEqual(QuestController.error(for: .network), .network("The quest couldn't be loaded"))
    }

    func testThemeIsAppliedAndAFailedOneIsLogged() async {
        let themed = ScriptedTransport(ScriptedTransport.server(theme: ##"{"tokens":{"--qaid-q-accent":"#ff0000"}}"##))
        let controller = controller(themed, config: Fixtures.config(themeId: "th_1"))
        await controller.start()
        await controller.waitUntilIdle()
        XCTAssertEqual(controller.style.light.accent, QuestColor(red: 1, green: 0, blue: 0))
        XCTAssertEqual(themed.calls.prefix(2).sorted(), ["GET /api/quest-themes/th_1/public", "GET /api/quests/quest_1/definition"])
        XCTAssertEqual(themed.calls[2], "POST /api/quests/responses")

        let missing = ScriptedTransport()
        let fallback = self.controller(missing, config: Fixtures.config(themeId: "th_2", theme: QaidQuestTheme(mode: .dark)))
        await fallback.start()
        XCTAssertEqual(fallback.style.mode, .dark)
        XCTAssertTrue(logs.contains { $0.contains("th_2") })
        XCTAssertEqual(reports.map(\.operation), [.loadTheme])

        let none = self.controller(ScriptedTransport(), config: Fixtures.config(themeId: "  "))
        await none.start()
        XCTAssertEqual(none.style, QuestStyleResolver.resolve(document: nil, host: nil))
    }

    func testOfflineSubmitShowsTheSavedNote() async {
        let transport = ScriptedTransport { request in
            if request.url.path.hasSuffix("/definition") {
                return QuestHTTPResponse(status: 200, body: Data(#"{"id":"quest_1","questions":[{"id":"a","type":"text","label":"A"}]}"#.utf8))
            }
            throw Offline()
        }
        let controller = controller(transport)
        await controller.start()
        controller.setText("a", "hi")
        controller.next()
        await controller.waitUntilIdle()
        XCTAssertEqual(controller.session?.phase, .done(savedOffline: true))
        XCTAssertEqual(completed, [["a": .string("hi")]])
        XCTAssertEqual(OfflineQuestStore(root: root).entries().count, 1)
    }

    func testRefusedSubmitThenTryAgain() async {
        var refuse = true
        let transport = ScriptedTransport { request in
            if refuse && request.url.path.hasSuffix("/submit") { return QuestHTTPResponse(status: 410) }
            return try ScriptedTransport.server(quest: #"{"questions":[{"id":"a","type":"date","label":"A"}]}"#)(request)
        }
        let controller = controller(transport)
        await controller.start()
        controller.next()
        await controller.waitUntilIdle()
        XCTAssertEqual(controller.session?.phase, .submitFailed)
        XCTAssertEqual(alerts.last, "Couldn't send your answers. Your answers are still here. Check your connection and try again.")
        XCTAssertEqual(reports.map(\.operation), [.submit])
        refuse = false
        controller.retrySubmit()
        await controller.waitUntilIdle()
        XCTAssertEqual(controller.session?.phase, .done(savedOffline: false))
        XCTAssertEqual(completed, [["a": .null]])
    }

    func testAutoAdvanceAndUnsupportedLog() async {
        let quest = #"{"questions":[{"id":"a","type":"multiple-choice","label":"A","options":[{"value":"x"}]},{"id":"b","type":"signature","label":"B"}]}"#
        let controller = controller(ScriptedTransport(ScriptedTransport.server(quest: quest)),
                                    config: Fixtures.config(autoAdvance: true))
        await controller.start()
        controller.select("a", option: "x")
        await controller.waitUntilIdle()
        XCTAssertEqual(controller.session?.currentQuestion.id, "b")
        XCTAssertTrue(logs.contains { $0.contains("\"b\" has type \"signature\"") })
    }

    func testSavingFlagFollowsTheSync() async {
        let controller = controller(ScriptedTransport())
        await controller.start()
        controller.setText("name", "Jo")
        XCTAssertFalse(controller.saving)
        controller.close()
        await controller.waitUntilIdle()
        XCTAssertFalse(controller.saving)
    }
}
