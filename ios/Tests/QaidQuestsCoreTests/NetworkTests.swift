import XCTest
@testable import QaidQuestsCore

final class RequestTests: XCTestCase {
    let base = URL(string: "https://qaid.dev/api/quests")!

    func testDefinition() {
        let request = QuestRequests.definition(base: base, questId: "q 1/x", userAgent: "UA")
        XCTAssertEqual(request.method, "GET")
        XCTAssertEqual(request.url.absoluteString, "https://qaid.dev/api/quests/q%201%2Fx/definition")
        XCTAssertEqual(request.headers, ["Accept": "application/json", "User-Agent": "UA"])
        XCTAssertNil(request.body)
        let trailing = QuestRequests.definition(base: URL(string: "https://qaid.dev/api/quests/")!, questId: "q", userAgent: "")
        XCTAssertEqual(trailing.url.absoluteString, "https://qaid.dev/api/quests/q/definition")
    }

    func testEncodesLikeEncodeURIComponent() {
        XCTAssertEqual(QuestRequests.encodeComponent("aZ09-._~ /+?&é"), "aZ09-._~%20%2F%2B%3F%26%C3%A9")
        XCTAssertEqual(QuestRequests.url(URL(string: "https://x.test/a//")!, "b c").absoluteString, "https://x.test/a/b%20c")
    }

    func testTheme() {
        let request = QuestRequests.theme(base: URL(string: "http://localhost:4321/api/quests")!, themeId: "th_1",
                                          apiKey: "k+y&", userAgent: "UA")
        XCTAssertEqual(request?.url.absoluteString, "http://localhost:4321/api/quest-themes/th_1/public?key=k%2By%26")
        XCTAssertEqual(QuestRequests.theme(base: base, themeId: "t", apiKey: "", userAgent: "")?.url.absoluteString,
                       "https://qaid.dev/api/quest-themes/t/public")
        XCTAssertNil(QuestRequests.theme(base: URL(string: "relative/path")!, themeId: "t", apiKey: "", userAgent: ""))
    }

    func testCreatePatchSubmit() {
        let create = QuestRequests.create(base: base, body: ["apiKey": "k"], userAgent: "UA")
        XCTAssertEqual(create.method, "POST")
        XCTAssertEqual(create.url.absoluteString, "https://qaid.dev/api/quests/responses")
        XCTAssertEqual(create.headers["Content-Type"], "application/json")
        XCTAssertNil(create.headers["X-API-Key"])
        XCTAssertEqual(body(create), ["apiKey": "k"])

        let patch = QuestRequests.patch(base: base, responseId: "r1", questionId: "q", value: .number(12.5), userAgent: "UA")
        XCTAssertEqual(patch.method, "PATCH")
        XCTAssertEqual(patch.url.absoluteString, "https://qaid.dev/api/quests/responses/r1")
        XCTAssertEqual(String(decoding: patch.body!, as: UTF8.self), #"{"questionId":"q","value":12.5}"#)

        let submit = QuestRequests.submit(base: base, responseId: "r1", answers: ["a": .strings(["x"]), "b": .null],
                                          userAgent: "UA")
        XCTAssertEqual(submit.url.absoluteString, "https://qaid.dev/api/quests/responses/r1/submit")
        XCTAssertEqual(String(decoding: submit.body!, as: UTF8.self), #"{"answers":{"a":["x"],"b":null}}"#)
    }

    func testCreateBody() {
        let body = QuestRequests.createBody(apiKey: "k", questId: "q", pageUrl: "app://x", visitorId: "v",
                                            userAgent: "UA", metadata: ["a": 1])
        XCTAssertEqual(body.text, #"{"apiKey":"k","metadata":{"a":1},"pageUrl":"app://x","questId":"q","userAgent":"UA","visitorId":"v"}"#)
    }

    func testStatusChecks() {
        XCTAssertNil(QuestRequests.check(QuestHTTPResponse(status: 200)))
        XCTAssertEqual(QuestRequests.check(QuestHTTPResponse(status: 503)), .server(503))
        XCTAssertEqual(QuestRequests.check(QuestHTTPResponse(status: 410, body: Data(#"{"error":"Quest is archived"}"#.utf8))),
                       .rejected(status: 410, message: "Quest is archived"))
        XCTAssertEqual(QuestRequests.check(QuestHTTPResponse(status: 400, body: Data("<html>".utf8))),
                       .rejected(status: 400, message: ""))
    }

    func testResponseId() {
        XCTAssertEqual(QuestRequests.responseId(QuestHTTPResponse(status: 201, body: Data(#"{"id":"r9"}"#.utf8))), .success("r9"))
        XCTAssertEqual(QuestRequests.responseId(QuestHTTPResponse(status: 201, body: Data(#"{"id":42}"#.utf8))), .success("42"))
        XCTAssertEqual(QuestRequests.responseId(QuestHTTPResponse(status: 201, body: Data("{}".utf8))),
                       .failure(.invalidResponse("No id in the response")))
        XCTAssertEqual(QuestRequests.responseId(QuestHTTPResponse(status: 401, body: Data(#"{"error":"Invalid API key"}"#.utf8))),
                       .failure(.rejected(status: 401, message: "Invalid API key")))
    }

    func testRetryRule() {
        XCTAssertTrue(QaidQuestsError.network("x").isRetryable)
        XCTAssertTrue(QaidQuestsError.server(500).isRetryable)
        XCTAssertTrue(QaidQuestsError.rejected(status: 429, message: "").isRetryable)
        XCTAssertTrue(QaidQuestsError.rejected(status: 408, message: "").isRetryable)
        XCTAssertFalse(QaidQuestsError.rejected(status: 400, message: "").isRetryable)
        XCTAssertFalse(QaidQuestsError.invalidResponse("x").isRetryable)
        XCTAssertFalse(QaidQuestsError.storage("x").isRetryable)
        let policy = QuestRetryPolicy()
        XCTAssertEqual(policy.delays, [1, 2, 4])
        XCTAssertEqual(policy.delay(after: 0, error: .server(500)), 1)
        XCTAssertEqual(policy.delay(after: 2, error: .network("")), 4)
        XCTAssertNil(policy.delay(after: 3, error: .network("")))
        XCTAssertNil(policy.delay(after: 0, error: .rejected(status: 404, message: "")))
    }

    func testErrorReport() {
        let report = QaidQuestsErrorReport(operation: .saveAnswer, questId: "q", questionId: "a", error: .server(500))
        XCTAssertEqual(report.operation.rawValue, "saveAnswer")
        XCTAssertEqual(report.questionId, "a")
        XCTAssertEqual(report.questId, "q")
        XCTAssertEqual(report.error, .server(500))
    }

    func testRealSleepReturns() async {
        await realSleep(0)
        await realSleep(-1)
    }
}

final class ClientTests: XCTestCase {
    let base = URL(string: "https://qaid.dev/api/quests")!

    private func client(_ transport: ScriptedTransport, delays: [TimeInterval] = [1, 2, 4],
                        slept: SleepLog? = nil) -> QuestClient {
        QuestClient(base: base, userAgent: "UA", transport: transport, retry: QuestRetryPolicy(delays: delays),
                    sleep: { seconds in slept?.add(seconds) })
    }

    func testLoadDefinition() async {
        let transport = ScriptedTransport()
        let result = await client(transport).loadDefinition(questId: "quest_1")
        XCTAssertEqual(try result.get().questions.count, 6)
        XCTAssertEqual(transport.calls, ["GET /api/quests/quest_1/definition"])
    }

    func testLoadDefinitionFailures() async {
        let missing = ScriptedTransport { _ in QuestHTTPResponse(status: 404, body: Data(#"{"error":"Quest not found"}"#.utf8)) }
        let offline = ScriptedTransport { _ in throw Offline() }
        let empty = ScriptedTransport { _ in QuestHTTPResponse(status: 200, body: Data(#"{"questions":[]}"#.utf8)) }
        let result1 = await client(missing).loadDefinition(questId: "q")
        let result2 = await client(offline).loadDefinition(questId: "q")
        let result3 = await client(empty).loadDefinition(questId: "q")
        XCTAssertEqual(result1, .failure(.status(404)))
        XCTAssertEqual(result2, .failure(.network))
        XCTAssertEqual(result3, .failure(.empty))
        // No retry for a load: the person gets a Retry button.
        XCTAssertEqual(offline.requests.count, 1)
    }

    func testLoadTheme() async {
        let found = ScriptedTransport(ScriptedTransport.server(theme: #"{"preset":"pill"}"#))
        let document = await client(found).loadTheme(themeId: "t", apiKey: "k")
        XCTAssertEqual(document?.preset, .pill)
        XCTAssertEqual(found.requests.first?.url.query, "key=k")
        let missing = await client(ScriptedTransport()).loadTheme(themeId: "t", apiKey: "k")
        XCTAssertNil(missing)
        let offline = await client(ScriptedTransport { _ in throw Offline() }).loadTheme(themeId: "t", apiKey: "k")
        XCTAssertNil(offline)
        let noHost = QuestClient(base: URL(string: "relative")!, userAgent: "", transport: ScriptedTransport())
        let none = await noHost.loadTheme(themeId: "t", apiKey: "")
        XCTAssertNil(none)
    }

    func testRetriesTheNetworkAndServerWithBackoff() async {
        var attempts = 0
        let transport = ScriptedTransport { _ in
            attempts += 1
            if attempts == 1 { throw Offline() }
            if attempts == 2 { return QuestHTTPResponse(status: 502) }
            return QuestHTTPResponse(status: 201, body: Data(#"{"id":"r"}"#.utf8))
        }
        let slept = SleepLog()
        let result = await client(transport, slept: slept).create(body: [:])
        XCTAssertEqual(result, .success("r"))
        XCTAssertEqual(slept.values, [1, 2])
    }

    func testGivesUpAfterTheLastDelay() async {
        let transport = ScriptedTransport { _ in QuestHTTPResponse(status: 500) }
        let slept = SleepLog()
        let result = await client(transport, delays: [1, 2], slept: slept).patch(responseId: "r", questionId: "q", value: .null)
        XCTAssertEqual(result.failure, .server(500))
        XCTAssertEqual(transport.requests.count, 3)
        XCTAssertEqual(slept.values, [1, 2])
    }

    func testNeverRetriesARefusal() async {
        let transport = ScriptedTransport { _ in QuestHTTPResponse(status: 400, body: Data(#"{"error":"Unknown questionId"}"#.utf8)) }
        let result = await client(transport).patch(responseId: "r", questionId: "q", value: .string("x"))
        XCTAssertEqual(result.failure, .rejected(status: 400, message: "Unknown questionId"))
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testSubmit() async {
        let transport = ScriptedTransport()
        let ok = await client(transport).submit(responseId: "r", answers: ["a": .string("x")])
        XCTAssertNil(ok.failure)
        let refused = await client(ScriptedTransport { _ in QuestHTTPResponse(status: 404) }).submit(responseId: "r", answers: [:])
        XCTAssertEqual(refused.failure, .rejected(status: 404, message: ""))
    }
}

final class SleepLog: @unchecked Sendable {
    private let lock = NSLock()
    private var log: [TimeInterval] = []
    func add(_ value: TimeInterval) { lock.lock(); log.append(value); lock.unlock() }
    var values: [TimeInterval] { lock.lock(); defer { lock.unlock() }; return log }
}

extension Result {
    var failure: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}

/// Answers URLSession requests in-process, so the real transport is tested without a network.
final class StubProtocol: URLProtocol {
    nonisolated(unsafe) static var respond: (URLRequest) -> (URLResponse?, Data, Error?) = { _ in (nil, Data(), nil) }
    nonisolated(unsafe) static var seen: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var request = request
        if request.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 1024)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(buffer, count: count)
            }
            stream.close()
            request.httpBody = data
        }
        Self.seen.append(request)
        let (response, data, error) = Self.respond(request)
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        if let response { client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed) }
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class URLSessionTransportTests: XCTestCase {
    private func transport() -> URLSessionQuestTransport {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        return URLSessionQuestTransport(session: URLSession(configuration: configuration), timeout: 5)
    }

    func testSendsMethodHeadersAndBody() async throws {
        StubProtocol.seen = []
        StubProtocol.respond = { request in
            (HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil), Data(#"{"id":"r"}"#.utf8), nil)
        }
        let request = QuestHTTPRequest(method: "POST", url: URL(string: "https://qaid.dev/api/quests/responses")!,
                                       headers: ["Content-Type": "application/json", "User-Agent": "UA"],
                                       body: Data(#"{"a":1}"#.utf8))
        let response = try await transport().send(request)
        XCTAssertEqual(response, QuestHTTPResponse(status: 201, body: Data(#"{"id":"r"}"#.utf8)))
        let seen = try XCTUnwrap(StubProtocol.seen.first)
        XCTAssertEqual(seen.httpMethod, "POST")
        XCTAssertEqual(seen.value(forHTTPHeaderField: "User-Agent"), "UA")
        XCTAssertEqual(seen.httpBody, Data(#"{"a":1}"#.utf8))
        XCTAssertEqual(seen.timeoutInterval, 5)
    }

    func testANonHTTPResponseIsStatusZero() async throws {
        StubProtocol.respond = { request in
            (URLResponse(url: request.url!, mimeType: nil, expectedContentLength: 0, textEncodingName: nil), Data(), nil)
        }
        let response = try await transport().send(QuestHTTPRequest(method: "GET", url: URL(string: "https://x.test")!, headers: [:]))
        XCTAssertEqual(response.status, 0)
    }

    func testANetworkFailureThrows() async {
        StubProtocol.respond = { _ in (nil, Data(), URLError(.notConnectedToInternet)) }
        do {
            _ = try await transport().send(QuestHTTPRequest(method: "GET", url: URL(string: "https://x.test")!, headers: [:]))
            XCTFail("expected a throw")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
        }
        XCTAssertNotNil(URLSessionQuestTransport().session)
    }
}

final class ConfigurationTests: XCTestCase {
    func testDefaults() {
        let config = QaidQuestsConfiguration(apiKey: "k")
        XCTAssertEqual(config.base.absoluteString, "https://qaid.dev/api/quests")
        XCTAssertNil(config.pageUrl)
        XCTAssertEqual(config.appName, "")
        XCTAssertNil(config.theme)
        XCTAssertNil(config.themeId)
        XCTAssertFalse(config.autoAdvance)
        XCTAssertNil(config.visitorId)
        XCTAssertEqual(config.text, QaidQuestText())
        XCTAssertEqual(QaidQuestsSDK.version, "0.1.0")
        let theme = QaidQuestTheme()
        XCTAssertNil(theme.accent ?? theme.error ?? theme.focus ?? theme.fontFamily)
        XCTAssertNil(theme.cornerRadius)
    }

    func testPageUrl() {
        XCTAssertEqual(QaidQuestsConfiguration.defaultPageUrl(bundleIdentifier: " Com.Example.App ").absoluteString,
                       "app://com.example.app")
        XCTAssertEqual(QaidQuestsConfiguration.defaultPageUrl(bundleIdentifier: "").absoluteString, "app://app")
        let config = QaidQuestsConfiguration(apiKey: "k")
        XCTAssertEqual(QuestPageUrl.make(config: config, bundleIdentifier: "com.x", screen: "Call Sheets"),
                       "app://com.x/call-sheets")
        XCTAssertEqual(QuestPageUrl.make(config: config, bundleIdentifier: "com.x", screen: nil), "app://com.x")
        XCTAssertEqual(QuestPageUrl.make(config: config, bundleIdentifier: "com.x", screen: "!!"), "app://com.x")
        let web = QaidQuestsConfiguration(apiKey: "k", pageUrl: URL(string: "https://example.com/app/")!)
        XCTAssertEqual(QuestPageUrl.make(config: web, bundleIdentifier: "com.x", screen: "Home"), "https://example.com/app/home")
        XCTAssertEqual(QuestPageUrl.slug("  Ça va? Été 2026 "), "a-va-t-2026")
        XCTAssertEqual(QuestPageUrl.slug(String(repeating: "a", count: 80)).count, 60)
    }

    func testDeviceUserAgentAndMetadata() {
        let device = Fixtures.device
        XCTAssertEqual(device.userAgent, "Crew/1.4 (812; iOS 18.1; iPhone15,2) QaidQuests/0.1.0")
        var app = QuestAppMetadata(user: QuestUser(id: "u1", email: "", name: "Ann"))
        app.set("plan", "pro")
        let metadata = device.metadata(screen: "Home", app: app, extra: ["feedbackId": "fb_1", "source": "override"])
        XCTAssertEqual(metadata["platform"], "ios")
        XCTAssertEqual(metadata["sdk"], "qaid-quests-ios/0.1.0")
        XCTAssertEqual(metadata["screen"], "Home")
        XCTAssertEqual(metadata["user"], ["id": "u1", "name": "Ann"])
        XCTAssertEqual(metadata["custom"], ["plan": "pro"])
        XCTAssertEqual(metadata["feedbackId"], "fb_1")
        XCTAssertEqual(metadata["source"], "override")
        XCTAssertEqual(metadata["device"], "iPhone15,2")
        XCTAssertEqual(metadata["locale"], "en_US")
        let bare = device.metadata(screen: "", app: QuestAppMetadata(), extra: [:])
        XCTAssertEqual(bare["source"], "native-quest")
        XCTAssertNil(bare["screen"])
        XCTAssertNil(bare["user"])
        XCTAssertNil(bare["custom"])
    }

    func testMetadataLimits() {
        var app = QuestAppMetadata()
        app.set("  ", "x")
        XCTAssertTrue(app.custom.isEmpty)
        app.set(String(repeating: "k", count: 100), String(repeating: "v", count: 600))
        XCTAssertEqual(app.custom.keys.first?.count, 64)
        XCTAssertEqual(app.custom.values.first?.count, 500)
        for index in 0..<40 { app.set("key\(index)", "v") }
        XCTAssertEqual(app.custom.count, 30)
        app.set("key0", "changed")
        XCTAssertEqual(app.custom["key0"], "changed")
        app.set("key0", nil)
        XCTAssertNil(app.custom["key0"])
        app.removeAll()
        XCTAssertTrue(app.custom.isEmpty)
        XCTAssertNil(QuestUser().jsonObject)
        XCTAssertEqual(QuestUser(email: String(repeating: "e", count: 600)).jsonObject?["email"]?.count, 500)
    }

    func testMetadataStore() {
        let store = QuestMetadataStore()
        store.setUser(QuestUser(id: "u"))
        store.setMetadata("a", "1")
        XCTAssertEqual(store.snapshot().user?.id, "u")
        XCTAssertEqual(store.snapshot().custom, ["a": "1"])
        store.setUser(QuestUser())
        XCTAssertNil(store.snapshot().user)
        store.clearMetadata()
        XCTAssertTrue(store.snapshot().custom.isEmpty)
        XCTAssertNotNil(QuestMetadataStore.shared)
    }
}
