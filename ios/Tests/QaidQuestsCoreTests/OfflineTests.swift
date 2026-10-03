import XCTest
@testable import QaidQuestsCore

final class OfflinePolicyTests: XCTestCase {
    func testDropsOldThenOldest() {
        let now = Date(timeIntervalSince1970: 10_000_000)
        let policy = OfflineQueuePolicy(maxItems: 2, maxAge: 100)
        let entries: [(id: String, createdAt: Date)] = [
            ("c", now.addingTimeInterval(-10)), ("old", now.addingTimeInterval(-101)),
            ("a", now.addingTimeInterval(-50)), ("b", now.addingTimeInterval(-20)),
        ]
        XCTAssertEqual(policy.idsToDrop(entries, now: now), ["old", "a"])
        XCTAssertEqual(policy.idsToDrop([], now: now), [])
        XCTAssertEqual(OfflineQueuePolicy(), OfflineQueuePolicy(maxItems: 20, maxAge: 7 * 24 * 3600))
    }
}

final class OfflineStoreTests: XCTestCase {
    var root: URL!

    override func setUp() {
        root = temporaryDirectory()
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    private func entry(start: OfflineResponse.Start = .created(responseId: "r1"),
                       operations: [OfflineOperation] = [.patch(questionId: "a", value: .string("x"))]) -> OfflineResponse {
        OfflineResponse(questId: "q", base: URL(string: "https://qaid.dev/api/quests")!, userAgent: "UA",
                        start: start, operations: operations)
    }

    func testRoundTripsEveryShape() throws {
        let store = OfflineQuestStore(root: root)
        let now = Date(timeIntervalSince1970: 1_790_985_600)
        let original = entry(start: .pending(createBody: ["apiKey": "k"]), operations: [
            .patch(questionId: "a", value: .strings(["x", "y"])),
            .patch(questionId: "b", value: .null),
            .submit(answers: ["a": .strings(["x", "y"]), "c": .number(3)]),
        ])
        let id = try store.add(original, now: now)
        XCTAssertTrue(id.hasPrefix("1790985600000-"))
        let read = store.entries(now: now)
        XCTAssertEqual(read.count, 1)
        var expected = original
        expected.id = id
        expected.createdAt = now
        XCTAssertEqual(read.first, expected)
        let values = try root.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
    }

    func testUpdateAndRemove() throws {
        let store = OfflineQuestStore(root: root)
        let id = try store.add(entry())
        var saved = try XCTUnwrap(store.entries().first)
        saved.operations = []
        try store.update(saved)
        XCTAssertEqual(store.entries().first?.operations, [])
        store.remove("")
        store.remove("../x")
        XCTAssertEqual(store.entries().count, 1)
        store.remove(id)
        XCTAssertTrue(store.entries().isEmpty)
    }

    func testPrunesByAgeAndCount() throws {
        let store = OfflineQuestStore(root: root, policy: OfflineQueuePolicy(maxItems: 2, maxAge: 1000))
        let start = Date(timeIntervalSince1970: 1_000_000)
        for offset in 0..<4 { try store.add(entry(), now: start.addingTimeInterval(Double(offset))) }
        XCTAssertEqual(store.entries(now: start.addingTimeInterval(10)).map(\.createdAt),
                       [start.addingTimeInterval(2), start.addingTimeInterval(3)])
        XCTAssertTrue(store.entries(now: start.addingTimeInterval(2000)).isEmpty)
    }

    func testIgnoresAndCleansWhatItCantRead() throws {
        let store = OfflineQuestStore(root: root)
        XCTAssertTrue(store.entries().isEmpty)
        try store.add(entry())
        let junk = ["5-a.json": "not json", "6-b.json": #"{"questId":"q"}"#,
                    "7-c.json": #"{"questId":"q","base":"https://x","userAgent":"u","operations":[]}"#,
                    "8-d.json": #"{"questId":"q","base":"https://x","userAgent":"u","responseId":"r","operations":[{"nope":1}]}"#,
                    "9-e.json": #"{"questId":"q","base":"https://x","userAgent":"u","responseId":"r","operations":[{"patch":{"questionId":"a","value":true}}]}"#]
        for (name, text) in junk { try Data(text.utf8).write(to: root.appendingPathComponent(name)) }
        try Data("x".utf8).write(to: root.appendingPathComponent("notes.txt"))
        try Data("{}".utf8).write(to: root.appendingPathComponent("nodate.json"))
        XCTAssertEqual(store.entries().count, 1)
        for name in junk.keys { XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(name).path)) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("notes.txt").path))
        XCTAssertNil(OfflineQuestStore.createdAt(name: "abc-1"))
        XCTAssertNil(OfflineQuestStore.createdAt(name: "123"))
    }

    func testAddFailsWhenTheRootCantBeMade() throws {
        try Data("file".utf8).write(to: root)
        let store = OfflineQuestStore(root: root.appendingPathComponent("queue"))
        XCTAssertThrowsError(try store.add(entry()))
    }
}

final class OfflineFlusherTests: XCTestCase {
    var root: URL!
    var store: OfflineQuestStore!
    var reports: [QaidQuestsErrorEvent] = []

    override func setUp() {
        root = temporaryDirectory()
        store = OfflineQuestStore(root: root)
        reports = []
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    private func add(_ start: OfflineResponse.Start, _ operations: [OfflineOperation], at seconds: Double = 0) throws {
        try store.add(OfflineResponse(questId: "q", base: URL(string: "https://qaid.dev/api/quests")!, userAgent: "UA",
                                      start: start, operations: operations),
                      now: Date().addingTimeInterval(seconds - 100))
    }

    private func flush(_ transport: ScriptedTransport) async -> OfflineFlusher.Outcome {
        await OfflineFlusher.flush(store: store, transport: transport) { self.reports.append($0) }
    }

    func testSendsInOrderCreateFirst() async throws {
        try add(.pending(createBody: ["apiKey": "k", "questId": "q"]),
                [.patch(questionId: "a", value: .string("x")), .submit(answers: ["a": .string("x")])], at: 0)
        try add(.created(responseId: "r0"), [.patch(questionId: "b", value: .number(2))], at: 1)
        let transport = ScriptedTransport()
        let outcome = await flush(transport)
        XCTAssertEqual(outcome, .finished)
        XCTAssertEqual(transport.calls, [
            "POST /api/quests/responses", "PATCH /api/quests/responses/resp_1",
            "POST /api/quests/responses/resp_1/submit", "PATCH /api/quests/responses/r0",
        ])
        XCTAssertEqual(transport.requests[0].headers["User-Agent"], "UA")
        XCTAssertTrue(store.entries().isEmpty)
        XCTAssertTrue(reports.isEmpty)
    }

    func testStopsOfflineAndKeepsProgress() async throws {
        try add(.pending(createBody: ["apiKey": "k"]),
                [.patch(questionId: "a", value: .string("x")), .patch(questionId: "b", value: .string("y"))])
        var sent = 0
        let transport = ScriptedTransport { request in
            if request.method == "POST" { return QuestHTTPResponse(status: 201, body: Data(#"{"id":"r7"}"#.utf8)) }
            sent += 1
            if sent > 1 { throw Offline() }
            return QuestHTTPResponse(status: 200)
        }
        let outcome = await flush(transport)
        XCTAssertEqual(outcome, .stopped)
        let left = try XCTUnwrap(store.entries().first)
        XCTAssertEqual(left.start, .created(responseId: "r7"))
        XCTAssertEqual(left.operations, [.patch(questionId: "b", value: .string("y"))])
        // Next time, no second create.
        let again = ScriptedTransport()
        let second = await flush(again)
        XCTAssertEqual(second, .finished)
        XCTAssertEqual(again.calls, ["PATCH /api/quests/responses/r7"])
    }

    func testCreateStillUnreachableStops() async throws {
        try add(.pending(createBody: [:]), [])
        let outcome = await flush(ScriptedTransport { _ in QuestHTTPResponse(status: 503) })
        XCTAssertEqual(outcome, .stopped)
        XCTAssertEqual(store.entries().count, 1)
    }

    func testRefusalsAreReportedAndDropped() async throws {
        try add(.pending(createBody: [:]), [.patch(questionId: "a", value: .null)], at: 0)
        try add(.created(responseId: "r1"), [.patch(questionId: "gone", value: .string("x")),
                                             .patch(questionId: "b", value: .string("y")),
                                             .submit(answers: [:])], at: 1)
        let transport = ScriptedTransport { request in
            if request.method == "POST" && request.url.path.hasSuffix("/responses") {
                return QuestHTTPResponse(status: 401, body: Data(#"{"error":"Invalid API key"}"#.utf8))
            }
            if request.method == "PATCH" && body(request)["questionId"] == "gone" {
                return QuestHTTPResponse(status: 400, body: Data(#"{"error":"Unknown questionId"}"#.utf8))
            }
            if request.url.path.hasSuffix("/submit") { return QuestHTTPResponse(status: 410) }
            return QuestHTTPResponse(status: 200)
        }
        let outcome = await flush(transport)
        XCTAssertEqual(outcome, .finished)
        XCTAssertEqual(reports.map(\.operation), [.createResponse, .saveAnswer, .submit])
        XCTAssertEqual(reports[1].questionId, "gone")
        XCTAssertEqual(reports[0].error, .rejected(status: 401, message: "Invalid API key"))
        XCTAssertEqual(transport.patches, ["gone=\"x\"", "b=\"y\""])
        XCTAssertTrue(store.entries().isEmpty)
    }

    func testSubmitUnreachableStops() async throws {
        try add(.created(responseId: "r"), [.submit(answers: [:])])
        let outcome = await flush(ScriptedTransport { _ in throw Offline() })
        XCTAssertEqual(outcome, .stopped)
        XCTAssertEqual(store.entries().first?.operations, [.submit(answers: [:])])
    }
}
