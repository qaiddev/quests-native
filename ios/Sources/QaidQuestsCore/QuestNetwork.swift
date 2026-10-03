import Foundation

package struct QuestHTTPRequest: Equatable, Sendable {
    package var method: String
    package var url: URL
    package var headers: [String: String]
    package var body: Data?

    package init(method: String, url: URL, headers: [String: String], body: Data? = nil) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
    }
}

package struct QuestHTTPResponse: Equatable, Sendable {
    package var status: Int
    package var body: Data

    package init(status: Int, body: Data = Data()) {
        self.status = status
        self.body = body
    }
}

/// The one way out to the network. Tests script it; the app uses `URLSessionQuestTransport`.
/// A thrown error means the server was never reached.
package protocol QuestTransport: Sendable {
    func send(_ request: QuestHTTPRequest) async throws -> QuestHTTPResponse
}

package struct URLSessionQuestTransport: QuestTransport {
    let session: URLSession
    let timeout: TimeInterval

    package init(session: URLSession = .shared, timeout: TimeInterval = 30) {
        self.session = session
        self.timeout = timeout
    }

    package func send(_ request: QuestHTTPRequest) async throws -> QuestHTTPResponse {
        let (data, response) = try await session.data(for: Self.urlRequest(request, timeout: timeout))
        return QuestHTTPResponse(status: (response as? HTTPURLResponse)?.statusCode ?? 0, body: data)
    }

    /// Never from the local cache: the definition is served with `max-age=60`, and a quest
    /// just published (or a theme just changed) must show at once, as Android's does.
    package static func urlRequest(_ request: QuestHTTPRequest, timeout: TimeInterval) -> URLRequest {
        var urlRequest = URLRequest(url: request.url, cachePolicy: .reloadIgnoringLocalCacheData,
                                    timeoutInterval: timeout)
        urlRequest.httpMethod = request.method
        for (name, value) in request.headers { urlRequest.setValue(value, forHTTPHeaderField: name) }
        urlRequest.httpBody = request.body
        return urlRequest
    }
}

/// What went wrong talking to qaid, for the app's `onError`. The same cases as Android's
/// `QaidQuestsError`.
public enum QaidQuestsError: Error, Equatable, Sendable {
    /// The server was never reached: offline, timed out, DNS.
    case network(String)
    /// A 5xx. Tried again with backoff, then queued.
    case server(Int)
    /// A 4xx: the request itself is refused (bad key, Domain Restriction, quest archived…).
    /// Never retried. `message` is the server's `error` text.
    case rejected(status: Int, message: String)
    /// A 2xx without what it should carry, or a body that isn't JSON.
    case invalidResponse(String)
    /// The offline queue couldn't be written.
    case storage(String)

    /// Worth trying again by itself: the network or the server, never the request.
    /// 408 and 429 are the two 4xx answers that mean "later".
    public var isRetryable: Bool {
        switch self {
        case .network, .server: return true
        case .rejected(let status, _): return status == 408 || status == 429
        case .invalidResponse, .storage: return false
        }
    }
}

/// One failure, with what the SDK was doing when it happened. Never thrown, never blocks
/// the person answering.
public struct QaidQuestsErrorEvent: Equatable, Sendable {
    public enum Operation: String, Equatable, Sendable {
        case loadQuest, loadTheme, createResponse, saveAnswer, submit, offlineQueue
    }

    public var questId: String
    public var operation: Operation
    /// For `saveAnswer`.
    public var questionId: String?
    public var error: QaidQuestsError

    public init(questId: String, operation: Operation, questionId: String? = nil, error: QaidQuestsError) {
        self.questId = questId
        self.operation = operation
        self.questionId = questionId
        self.error = error
    }
}

/// The five quest endpoints (protocol §0). No `X-API-Key` header: the server reads the
/// key from the create body only.
package enum QuestRequests {
    static func headers(userAgent: String, json: Bool) -> [String: String] {
        var headers = ["Accept": "application/json", "User-Agent": userAgent]
        if json { headers["Content-Type"] = "application/json" }
        return headers
    }

    /// What `encodeURIComponent` does: everything but `A-Z a-z 0-9 - . _ ~` as UTF-8
    /// `%XX`. A `+` left bare would read as a space, a `/` as a path.
    package static func encodeComponent(_ text: String) -> String {
        var out = ""
        for byte in text.utf8 {
            let scalar = Unicode.Scalar(byte)
            if byte < 0x80 && (CharacterSet.alphanumerics.contains(scalar) || "-._~".unicodeScalars.contains(scalar)) {
                out.unicodeScalars.append(scalar)
            } else {
                out += String(format: "%%%02X", byte)
            }
        }
        return out
    }

    /// `base` plus each segment, percent-encoded as the web embed's `encodeURIComponent`
    /// does. (`appendingPathComponent` leaves a `/` in an id alone.)
    package static func url(_ base: URL, _ segments: String...) -> URL {
        var text = base.absoluteString
        while text.hasSuffix("/") { text.removeLast() }
        for segment in segments { text += "/" + encodeComponent(segment) }
        return URL(string: text) ?? base
    }

    package static func definition(base: URL, questId: String, userAgent: String) -> QuestHTTPRequest {
        QuestHTTPRequest(method: "GET", url: url(base, questId, "definition"),
                         headers: headers(userAgent: userAgent, json: false))
    }

    /// `/api/quest-themes/<id>/public?key=<apiKey>` on the base's origin, or
    /// `/api/quest-themes/<id>/v/<version>/public` for one published version. nil for a
    /// base without a host.
    package static func theme(base: URL, themeId: String, version: Int? = nil, apiKey: String,
                              userAgent: String) -> QuestHTTPRequest? {
        var parts = URLComponents()
        parts.scheme = base.scheme
        parts.host = base.host
        parts.port = base.port
        parts.path = "/api/quest-themes/\(themeId)" + (version.map { "/v/\($0)" } ?? "") + "/public"
        if !apiKey.isEmpty { parts.percentEncodedQuery = "key=\(encodeComponent(apiKey))" }
        guard base.host != nil, let url = parts.url else { return nil }
        return QuestHTTPRequest(method: "GET", url: url, headers: headers(userAgent: userAgent, json: false))
    }

    package static func responses(_ base: URL) -> URL {
        url(base, "responses")
    }

    package static func createBody(apiKey: String, questId: String, pageUrl: String, visitorId: String,
                                   userAgent: String, metadata: QuestJSON) -> QuestJSON {
        [
            "apiKey": .string(apiKey),
            "questId": .string(questId),
            "pageUrl": .string(pageUrl),
            "visitorId": .string(visitorId),
            "userAgent": .string(userAgent),
            "metadata": metadata,
        ]
    }

    package static func create(base: URL, body: QuestJSON, userAgent: String) -> QuestHTTPRequest {
        QuestHTTPRequest(method: "POST", url: responses(base), headers: headers(userAgent: userAgent, json: true),
                         body: body.data)
    }

    package static func patch(base: URL, responseId: String, questionId: String, value: QuestAnswer,
                              userAgent: String) -> QuestHTTPRequest {
        let body: QuestJSON = ["questionId": .string(questionId), "value": value.json]
        return QuestHTTPRequest(method: "PATCH", url: url(base, "responses", responseId),
                                headers: headers(userAgent: userAgent, json: true), body: body.data)
    }

    /// The server stores answers from PATCHes only and never reads this body; it is
    /// sent because the web embed sends it.
    package static func submit(base: URL, responseId: String, answers: [String: QuestAnswer],
                               userAgent: String) -> QuestHTTPRequest {
        let body: QuestJSON = ["answers": .object(answers.mapValues(\.json))]
        return QuestHTTPRequest(method: "POST",
                                url: url(base, "responses", responseId, "submit"),
                                headers: headers(userAgent: userAgent, json: true), body: body.data)
    }

    /// Success, or the error a status means.
    package static func check(_ response: QuestHTTPResponse) -> QaidQuestsError? {
        switch response.status {
        case 200..<300: return nil
        case 500...: return .server(response.status)
        default:
            let message = (try? QuestJSON.parse(response.body))?["error"]?.stringValue ?? ""
            return .rejected(status: response.status, message: message)
        }
    }

    /// The new response's id from a 201.
    package static func responseId(_ response: QuestHTTPResponse) -> Result<String, QaidQuestsError> {
        if let error = check(response) { return .failure(error) }
        switch (try? QuestJSON.parse(response.body))?["id"] {
        case .string(let id)?: return .success(id)
        case .number(let id)?: return .success(QuestJSON.number(id))
        default: return .failure(.invalidResponse("No id in the response"))
        }
    }
}

/// How long to wait before trying a refused-for-now request again.
package struct QuestRetryPolicy: Equatable, Sendable {
    package var delays: [TimeInterval]

    package init(delays: [TimeInterval] = [1, 2, 4]) {
        self.delays = delays
    }

    /// The wait before retry `attempt` (0-based), or nil to give up.
    package func delay(after attempt: Int, error: QaidQuestsError) -> TimeInterval? {
        guard error.isRetryable, attempt < delays.count else { return nil }
        return delays[attempt]
    }
}

package typealias QuestSleep = @Sendable (TimeInterval) async -> Void

package let realSleep: QuestSleep = { seconds in
    try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0) * 1_000_000_000))
}

/// The protocol over a transport, with the retry rule: the network and 5xx are tried
/// again with backoff, a 4xx never is.
package struct QuestClient: Sendable {
    package var base: URL
    package var userAgent: String
    package var transport: QuestTransport
    package var retry: QuestRetryPolicy
    package var sleep: QuestSleep

    package init(base: URL, userAgent: String, transport: QuestTransport, retry: QuestRetryPolicy = QuestRetryPolicy(),
                 sleep: @escaping QuestSleep = realSleep) {
        self.base = base
        self.userAgent = userAgent
        self.transport = transport
        self.retry = retry
        self.sleep = sleep
    }

    /// Once, no retry: a failed load shows Retry to the person instead.
    package func loadDefinition(questId: String) async -> Result<Questionnaire, QuestLoadFailure> {
        let request = QuestRequests.definition(base: base, questId: questId, userAgent: userAgent)
        guard let response = try? await transport.send(request) else { return .failure(.network) }
        guard (200..<300).contains(response.status) else { return .failure(.status(response.status)) }
        return Questionnaire.decode(response.body, fallbackId: questId)
    }

    /// Once, no retry. On a failure the quest draws with the default look, as on the web.
    package func loadTheme(themeId: String, version: Int? = nil,
                           apiKey: String) async -> Result<QuestThemeDocument, QaidQuestsError> {
        guard let request = QuestRequests.theme(base: base, themeId: themeId, version: version, apiKey: apiKey,
                                                userAgent: userAgent) else {
            return .failure(.invalidResponse("No host in \(base.absoluteString)"))
        }
        let response: QuestHTTPResponse
        do {
            response = try await transport.send(request)
        } catch {
            return .failure(.network(error.localizedDescription))
        }
        if let error = QuestRequests.check(response) { return .failure(error) }
        guard let document = QuestThemeDocument.decode(response.body) else {
            return .failure(.invalidResponse("Invalid theme"))
        }
        return .success(document)
    }

    package func create(body: QuestJSON) async -> Result<String, QaidQuestsError> {
        await withRetry(QuestRequests.create(base: base, body: body, userAgent: userAgent)) {
            QuestRequests.responseId($0)
        }
    }

    package func patch(responseId: String, questionId: String, value: QuestAnswer) async -> Result<Void, QaidQuestsError> {
        await withRetry(QuestRequests.patch(base: base, responseId: responseId, questionId: questionId,
                                            value: value, userAgent: userAgent)) { response in
            QuestRequests.check(response).map { .failure($0) } ?? .success(())
        }
    }

    package func submit(responseId: String, answers: [String: QuestAnswer]) async -> Result<Void, QaidQuestsError> {
        await withRetry(QuestRequests.submit(base: base, responseId: responseId, answers: answers,
                                             userAgent: userAgent)) { response in
            QuestRequests.check(response).map { .failure($0) } ?? .success(())
        }
    }

    private func withRetry<T>(_ request: QuestHTTPRequest,
                              parse: (QuestHTTPResponse) -> Result<T, QaidQuestsError>) async -> Result<T, QaidQuestsError> {
        var attempt = 0
        while true {
            let outcome: Result<T, QaidQuestsError>
            do {
                outcome = parse(try await transport.send(request))
            } catch {
                outcome = .failure(.network(error.localizedDescription))
            }
            // A cancelled call (submit's time limit) stops here rather than retrying.
            guard case .failure(let error) = outcome, !Task.isCancelled,
                  let wait = retry.delay(after: attempt, error: error) else {
                return outcome
            }
            attempt += 1
            await sleep(wait)
        }
    }
}
