import XCTest
@testable import QaidQuestsCore

@MainActor
final class ResponseSyncTests: XCTestCase {
    var root: URL!
    var reports: [QaidQuestsErrorReport] = []
    var savingChanges: [Bool] = []

    override func setUp() async throws {
        root = temporaryDirectory()
        reports = []
        savingChanges = []
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func sync(_ transport: ScriptedTransport, debounce: TimeInterval = 0, store: Bool = true,
                      delays: [TimeInterval] = []) -> ResponseSync {
        let client = QuestClient(base: URL(string: "https://qaid.dev/api/quests")!, userAgent: "UA", transport: transport,
                                 retry: QuestRetryPolicy(delays: delays), sleep: noSleep)
        let sync = ResponseSync(client: client, questId: "quest_1", createBody: ["apiKey": "k", "questId": "quest_1"],
                                store: store ? OfflineQuestStore(root: root) : nil, debounce: debounce, sleep: realSleep)
        sync.onError = { self.reports.append($0) }
        sync.onSavingChange = { self.savingChanges.append($0) }
        return sync
    }

    func testAnswersBeforeCreateWaitAndGoInOrder() async {
        let transport = ScriptedTransport()
        let sync = sync(transport)
        sync.record(questionId: "a", value: .string("x"), debounce: false)
        sync.start()
        sync.start()
        sync.record(questionId: "b", value: .number(1), debounce: false)
        await sync.waitUntilIdle()
        XCTAssertEqual(sync.responseId, "resp_1")
        XCTAssertEqual(transport.calls, ["POST /api/quests/responses", "PATCH /api/quests/responses/resp_1",
                                         "PATCH /api/quests/responses/resp_1"])
        XCTAssertEqual(transport.patches, [#"a="x""#, "b=1"])
        XCTAssertEqual(body(transport.requests[0]), ["apiKey": "k", "questId": "quest_1"])
        XCTAssertEqual(savingChanges, [true, false])
    }

    func testTypingIsDebouncedAndRepeatsAreNotResent() async throws {
        let transport = ScriptedTransport()
        let sync = sync(transport, debounce: 0.05)
        sync.start()
        await sync.waitUntilIdle()
        for prefix in ["J", "Ja", "Jan"] { sync.record(questionId: "name", value: .string(prefix), debounce: true) }
        try await Task.sleep(nanoseconds: 200_000_000)
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.patches, [#"name="Jan""#])
        sync.record(questionId: "name", value: .string("Jan"), debounce: true)
        sync.flush()
        sync.flush()
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.patches, [#"name="Jan""#])
    }

    func testAnotherQuestionFlushesThePendingOneFirst() async {
        let transport = ScriptedTransport()
        let sync = sync(transport, debounce: 10)
        sync.start()
        sync.record(questionId: "a", value: .string("1"), debounce: true)
        sync.record(questionId: "b", value: .string("2"), debounce: true)
        sync.record(questionId: "c", value: .strings(["x"]), debounce: false)
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.patches, [#"a="1""#, #"b="2""#, #"c=["x"]"#])
    }

    func testARefusedAnswerIsReportedAndSkipped() async {
        let transport = ScriptedTransport { request in
            if request.method == "PATCH" && body(request)["questionId"] == "bad" {
                return QuestHTTPResponse(status: 400, body: Data(#"{"error":"Invalid value for text question"}"#.utf8))
            }
            return try ScriptedTransport.server()(request)
        }
        let sync = sync(transport)
        sync.start()
        sync.record(questionId: "bad", value: .number(1), debounce: false)
        sync.record(questionId: "good", value: .string("y"), debounce: false)
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.patches, ["bad=1", #"good="y""#])
        XCTAssertEqual(reports, [QaidQuestsErrorReport(operation: .saveAnswer, questId: "quest_1", questionId: "bad",
                                                       error: .rejected(status: 400, message: "Invalid value for text question"))])
    }

    func testSubmitPatchesNullsForHiddenThenSubmits() async {
        let transport = ScriptedTransport()
        let sync = sync(transport, debounce: 10)
        sync.start()
        sync.record(questionId: "why", value: .string("calm"), debounce: true)
        let outcome = await sync.submit(answers: ["color": .string("red")], clearing: ["why"])
        XCTAssertEqual(outcome, .sent)
        XCTAssertEqual(transport.patches, [#"why="calm""#, "why=null"])
        XCTAssertEqual(transport.calls.last, "POST /api/quests/responses/resp_1/submit")
        XCTAssertEqual(body(transport.requests.last!), ["answers": ["color": "red"]])
        // Done: nothing more goes out, and a second submit is the same answer.
        sync.record(questionId: "late", value: .string("x"), debounce: false)
        let again = await sync.submit(answers: [:], clearing: [])
        XCTAssertEqual(again, .sent)
        XCTAssertEqual(transport.requests.count, 4)
    }

    func testOfflineMidFlowRetriesAtSubmitThenQueues() async {
        let transport = ScriptedTransport { _ in throw Offline() }
        let sync = sync(transport, delays: [1, 2])
        sync.start()
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.requests.count, 3)
        // Offline: answers wait in memory, no retry storm per answer.
        sync.record(questionId: "a", value: .string("x"), debounce: false)
        sync.record(questionId: "b", value: .number(2), debounce: false)
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.requests.count, 3)
        let outcome = await sync.submit(answers: ["a": .string("x"), "b": .number(2)], clearing: [])
        XCTAssertEqual(outcome, .savedOffline)
        XCTAssertEqual(transport.requests.count, 6)
        let queued = OfflineQuestStore(root: root).entries()
        XCTAssertEqual(queued.count, 1)
        XCTAssertEqual(queued[0].start, .pending(createBody: ["apiKey": "k", "questId": "quest_1"]))
        XCTAssertEqual(queued[0].questId, "quest_1")
        XCTAssertEqual(queued[0].userAgent, "UA")
        XCTAssertEqual(queued[0].operations, [.patch(questionId: "a", value: .string("x")),
                                              .patch(questionId: "b", value: .number(2)),
                                              .submit(answers: ["a": .string("x"), "b": .number(2)])])
        XCTAssertTrue(reports.isEmpty)
        let again = await sync.submit(answers: [:], clearing: [])
        XCTAssertEqual(again, .savedOffline)
    }

    func testDroppingOffAfterCreateQueuesWithTheResponseId() async {
        var online = true
        let transport = ScriptedTransport { request in
            guard online else { throw Offline() }
            return try ScriptedTransport.server()(request)
        }
        let sync = sync(transport)
        sync.start()
        sync.record(questionId: "a", value: .string("x"), debounce: false)
        await sync.waitUntilIdle()
        online = false
        sync.record(questionId: "b", value: .string("y"), debounce: false)
        let outcome = await sync.submit(answers: ["a": .string("x"), "b": .string("y")], clearing: [])
        XCTAssertEqual(outcome, .savedOffline)
        let queued = OfflineQuestStore(root: root).entries()
        XCTAssertEqual(queued.first?.start, .created(responseId: "resp_1"))
        XCTAssertEqual(queued.first?.operations.count, 2)
    }

    func testBackOnlineAtSubmitSendsEverything() async {
        var online = false
        let transport = ScriptedTransport { request in
            guard online else { throw Offline() }
            return try ScriptedTransport.server()(request)
        }
        let sync = sync(transport)
        sync.start()
        sync.record(questionId: "a", value: .string("x"), debounce: false)
        await sync.waitUntilIdle()
        online = true
        let outcome = await sync.submit(answers: ["a": .string("x")], clearing: [])
        XCTAssertEqual(outcome, .sent)
        XCTAssertEqual(transport.calls.suffix(3), ["POST /api/quests/responses", "PATCH /api/quests/responses/resp_1",
                                                   "POST /api/quests/responses/resp_1/submit"])
    }

    func testARefusedCreateIsTriedOnceMoreAtSubmitThenFails() async {
        let transport = ScriptedTransport { _ in
            QuestHTTPResponse(status: 403, body: Data(#"{"error":"Domain not allowed"}"#.utf8))
        }
        let sync = sync(transport)
        sync.start()
        sync.record(questionId: "a", value: .string("x"), debounce: false)
        await sync.waitUntilIdle()
        XCTAssertEqual(transport.requests.count, 1)
        let outcome = await sync.submit(answers: [:], clearing: [])
        XCTAssertEqual(outcome, .failed(.rejected(status: 403, message: "Domain not allowed")))
        XCTAssertEqual(transport.requests.count, 2)
        XCTAssertEqual(reports.map(\.operation), [.createResponse, .createResponse])
        XCTAssertTrue(OfflineQuestStore(root: root).entries().isEmpty)
    }

    func testARefusedSubmitFailsAndCanBeTriedAgain() async {
        var refuse = true
        let transport = ScriptedTransport { request in
            if refuse && request.url.path.hasSuffix("/submit") { return QuestHTTPResponse(status: 404) }
            return try ScriptedTransport.server()(request)
        }
        let sync = sync(transport)
        sync.start()
        let first = await sync.submit(answers: ["a": .string("x")], clearing: [])
        XCTAssertEqual(first, .failed(.rejected(status: 404, message: "")))
        XCTAssertEqual(reports.map(\.operation), [.submit])
        refuse = false
        let second = await sync.submit(answers: ["a": .string("y")], clearing: [])
        XCTAssertEqual(second, .sent)
        let submits = transport.requests.filter { $0.url.path.hasSuffix("/submit") }
        XCTAssertEqual(submits.count, 2)
        XCTAssertEqual(body(submits[1]), ["answers": ["a": "y"]])
    }

    func testWithoutAStoreAnUnreachableSubmitFails() async {
        let sync = sync(ScriptedTransport { _ in throw Offline() }, store: false)
        sync.start()
        let outcome = await sync.submit(answers: [:], clearing: [])
        guard case .failed(.network) = outcome else { return XCTFail("\(outcome)") }
    }

    func testAQueueThatCantBeWrittenFails() async throws {
        try Data("file".utf8).write(to: root)
        let client = QuestClient(base: URL(string: "https://qaid.dev/api/quests")!, userAgent: "UA",
                                 transport: ScriptedTransport { _ in throw Offline() }, retry: QuestRetryPolicy(delays: []))
        let sync = ResponseSync(client: client, questId: "q", createBody: [:],
                                store: OfflineQuestStore(root: root.appendingPathComponent("queue")))
        sync.onError = { self.reports.append($0) }
        sync.start()
        let outcome = await sync.submit(answers: [:], clearing: [])
        guard case .failed(.storage) = outcome else { return XCTFail("\(outcome)") }
        XCTAssertEqual(reports.map(\.operation), [.offlineQueue])
    }
}
