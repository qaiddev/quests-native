import CryptoKit
import XCTest
@testable import QaidQuestsCore

final class SecretKeyGuardTests: XCTestCase {
    private let secret = "QAID_SECRET_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
    private let pub = "QAID_PUB_abcdefghijklmnopqrstuvwxyz0123456789"

    override func setUp() {
        SecretKeyReporter.shared.reset()
    }

    private func sha256(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The report runs in its own task; give it a moment.
    private func waitForReports(_ transport: ScriptedTransport, count: Int = 1) async {
        for _ in 0..<200 where leakedKeyRequests(transport).count < count {
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
    }

    private func leakedKeyRequests(_ transport: ScriptedTransport) -> [QuestHTTPRequest] {
        transport.requests.filter { $0.url.path == "/api/leaked-key" }
    }

    // MARK: Matching and scrubbing

    func testACleanConfigIsUntouched() {
        let config = QaidQuestsConfiguration(apiKey: pub, appName: "Crew", themeId: "t1", visitorId: "v1")
        let result = SecretKeyGuard.scrub(config)
        XCTAssertEqual(result.found, [])
        XCTAssertEqual(result.value, config)
    }

    func testASecretApiKeyIsDropped() {
        let result = SecretKeyGuard.scrub(QaidQuestsConfiguration(apiKey: secret, appName: "Crew"))
        XCTAssertEqual(result.found, [secret])
        XCTAssertEqual(result.value.apiKey, "")
        XCTAssertEqual(result.value.appName, "Crew")
        XCTAssertEqual(result.value.base, QaidQuestsConfiguration.defaultBase)
    }

    func testSecretsAnywhereSentAreDropped() {
        let config = QaidQuestsConfiguration(
            apiKey: pub, appName: "Crew \(secret)",
            base: URL(string: "https://stg.qaid.dev/api/quests?k=\(secret)")!,
            pageUrl: URL(string: "https://acme.com/app/\(secret)")!,
            themeId: secret, visitorId: "v \(secret)"
        )
        let result = SecretKeyGuard.scrub(config)
        XCTAssertEqual(result.found.count, 5)
        XCTAssertEqual(result.value.apiKey, pub)
        XCTAssertEqual(result.value.appName, "Crew")
        XCTAssertEqual(result.value.base.absoluteString, "https://stg.qaid.dev/api/quests?k=")
        XCTAssertEqual(result.value.pageUrl?.absoluteString, "https://acme.com/app/")
        XCTAssertEqual(result.value.themeId, "")
        XCTAssertEqual(result.value.visitorId, "v")
    }

    func testAUrlThatIsOnlyASecretFallsBack() {
        let bare = URL(string: secret)!
        let result = SecretKeyGuard.scrub(QaidQuestsConfiguration(apiKey: pub, base: bare, pageUrl: bare))
        XCTAssertEqual(result.found, [secret, secret])
        XCTAssertEqual(result.value.base, QaidQuestsConfiguration.defaultBase)
        XCTAssertNil(result.value.pageUrl)
    }

    func testASecretInMetadataIsDropped() {
        let extra = SecretKeyGuard.scrub(["feedbackId": "fb_1", "token": "x \(secret)"])
        XCTAssertEqual(extra.value, ["feedbackId": "fb_1", "token": "x"])
        XCTAssertEqual(extra.found, [secret])

        var app = QuestAppMetadata(user: QuestUser(id: secret, email: "a@b.co"))
        app.set("plan", "pro")
        app.set("token", secret)
        let scrubbed = SecretKeyGuard.scrub(app)
        XCTAssertEqual(scrubbed.found, [secret, secret])
        XCTAssertEqual(scrubbed.value.user, QuestUser(id: "", email: "a@b.co"))
        XCTAssertEqual(scrubbed.value.custom, ["plan": "pro", "token": ""])

        var noUser = QuestAppMetadata()
        noUser.set("token", secret)
        XCTAssertNil(SecretKeyGuard.scrub(noUser).value.user)

        let clean = QuestAppMetadata(user: QuestUser(name: "Jo"))
        XCTAssertEqual(SecretKeyGuard.scrub(clean).value, clean)
        XCTAssertEqual(SecretKeyGuard.scrub(clean).found, [])
        XCTAssertNil(SecretKeyGuard.scrub(nil as String?).value)
    }

    func testTheEmbedKeyAndAShortTailDoNotMatch() {
        XCTAssertEqual(SecretKeyGuard.scrub(pub).found, [])
        XCTAssertEqual(SecretKeyGuard.scrub("QAID_SECRET_short").found, [])
        XCTAssertEqual(SecretKeyGuard.scrub("QAID_SECRET_0123456789abcde").found, []) // 15
        XCTAssertEqual(SecretKeyGuard.scrub("QAID_SECRET_0123456789abcdef").found, ["QAID_SECRET_0123456789abcdef"])
        // Old-format keys are cut off server-side and are not looked for.
        XCTAssertEqual(SecretKeyGuard.scrub("qd_live_abcdefghijklmnopqrstuvwxyz0123456789ABCD").found, [])
    }

    // MARK: The report

    func testTheReportEndpointIsOnTheBasesOrigin() {
        XCTAssertEqual(SecretKeyGuard.reportURL(base: QaidQuestsConfiguration.defaultBase)?.absoluteString,
                       "https://qaid.dev/api/leaked-key")
        XCTAssertEqual(SecretKeyGuard.reportURL(base: URL(string: "http://localhost:4321/api/quests")!)?.absoluteString,
                       "http://localhost:4321/api/leaked-key")
        XCTAssertNil(SecretKeyGuard.reportURL(base: URL(string: "/api/quests")!))
        XCTAssertNil(SecretKeyGuard.reportRequest(secret: secret, base: URL(string: "quests")!, pageUrl: nil))
    }

    func testTheReportCarriesTheHashNotTheKey() throws {
        let request = try XCTUnwrap(SecretKeyGuard.reportRequest(
            secret: secret, base: URL(string: "https://stg.qaid.dev/api/quests")!, pageUrl: "app://com.acme.crew"))
        XCTAssertEqual(request.method, "POST")
        XCTAssertEqual(request.url.absoluteString, "https://stg.qaid.dev/api/leaked-key")
        XCTAssertEqual(request.headers, ["Content-Type": "text/plain"])
        let data = try XCTUnwrap(request.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
        XCTAssertEqual(json, ["keyHash": sha256(secret), "pageUrl": "app://com.acme.crew", "source": "quests-ios"])
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains(secret))

        let bare = try XCTUnwrap(JSONSerialization.jsonObject(with: SecretKeyGuard.reportBody(secret: secret, pageUrl: nil))
            as? [String: String])
        XCTAssertEqual(Set(bare.keys), ["keyHash", "source"])
        XCTAssertEqual(SecretKeyGuard.sha256Hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    func testReportsOncePerProcessAndLogsEachTime() async {
        let reporter = SecretKeyReporter()
        let transport = ScriptedTransport()
        var logs: [String] = []
        for _ in 0..<2 {
            reporter.report([secret], base: QaidQuestsConfiguration.defaultBase, pageUrl: "app://x",
                            transport: transport, log: { logs.append($0) })
        }
        await waitForReports(transport)
        XCTAssertEqual(logs, [SecretKeyGuard.message, SecretKeyGuard.message])
        XCTAssertTrue(SecretKeyGuard.message.contains("QAID_PUB_"))
        XCTAssertEqual(leakedKeyRequests(transport).count, 1)

        reporter.reset()
        reporter.report([secret], base: QaidQuestsConfiguration.defaultBase, pageUrl: nil, transport: transport,
                        log: { logs.append($0) })
        await waitForReports(transport, count: 2)
        XCTAssertEqual(leakedKeyRequests(transport).count, 2)
    }

    func testNothingFoundOrNoHostSendsNothing() async {
        let reporter = SecretKeyReporter()
        let transport = ScriptedTransport { _ in throw Offline() }
        var logs = 0
        reporter.report([], base: QaidQuestsConfiguration.defaultBase, pageUrl: nil, transport: transport,
                        log: { _ in logs += 1 })
        XCTAssertEqual(logs, 0)
        reporter.report([secret], base: URL(string: "quests")!, pageUrl: nil, transport: transport,
                        log: { _ in logs += 1 })
        XCTAssertEqual(logs, 1)
        // A failed send is swallowed.
        reporter.report(["QAID_SECRET_ZZZZZZZZZZZZZZZZZZZZZZZZ"], base: QaidQuestsConfiguration.defaultBase,
                        pageUrl: nil, transport: transport)
        await waitForReports(transport)
        XCTAssertEqual(transport.requests.count, 1)
    }

    // MARK: Through the controller

    @MainActor
    func testTheControllerNeverSendsTheSecretAndReportsItOnce() async throws {
        let transport = ScriptedTransport()
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        var app = QuestAppMetadata()
        app.set("token", secret)
        let config = QaidQuestsConfiguration(apiKey: secret, appName: "Crew")
        let environment = QuestEnvironment(config: config, device: Fixtures.device, visitorId: "visitor_1",
                                           appMetadata: app, transport: transport, store: OfflineQuestStore(root: root),
                                           retry: QuestRetryPolicy(delays: []), debounce: 0, autoAdvanceDelay: 0,
                                           sleep: noSleep)
        let controller = QuestController(questId: "quest_1", screen: "Settings",
                                         metadata: ["feedbackId": "fb_9", "note": secret], environment: environment)
        XCTAssertEqual(controller.environment.config.apiKey, "")
        await controller.start()
        await controller.waitUntilIdle()
        await waitForReports(transport)

        let create = try XCTUnwrap(transport.requests.first { $0.url.path == "/api/quests/responses" })
        XCTAssertEqual(body(create)["apiKey"], "")
        XCTAssertEqual(body(create)["metadata"]?["feedbackId"], "fb_9")
        XCTAssertEqual(body(create)["metadata"]?["note"], "")
        for request in transport.requests {
            XCTAssertFalse(String(decoding: request.body ?? Data(), as: UTF8.self).contains(secret))
            XCTAssertFalse(request.url.absoluteString.contains(secret))
        }
        let reports = leakedKeyRequests(transport)
        XCTAssertEqual(reports.count, 1)
        XCTAssertEqual(body(reports[0])["keyHash"], .string(sha256(secret)))
        XCTAssertEqual(body(reports[0])["pageUrl"], "app://com.example.crew/settings")
        XCTAssertEqual(body(reports[0])["source"], "quests-ios")

        // A second quest in the same process does not report the same key again.
        _ = QuestController(questId: "quest_2", screen: nil, metadata: [:], environment: environment)
        try? await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(leakedKeyRequests(transport).count, 1)
    }
}
