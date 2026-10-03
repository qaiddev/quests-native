import Foundation

/// One response's requests that couldn't reach qaid, kept in order: create (if the
/// response has no id yet), then each answer, then submit.
package enum OfflineOperation: Equatable, Sendable {
    case patch(questionId: String, value: QuestAnswer)
    case submit(answers: [String: QuestAnswer])
}

package struct OfflineResponse: Equatable, Sendable {
    /// The file name, set by the store.
    package var id: String
    package var createdAt: Date
    package var questId: String
    package var base: URL
    package var userAgent: String
    package var start: Start
    package var operations: [OfflineOperation]

    package enum Start: Equatable, Sendable {
        /// The response exists on the server.
        case created(responseId: String)
        /// It doesn't yet: this body creates it.
        case pending(createBody: QuestJSON)
    }

    package init(id: String = "", createdAt: Date = Date(), questId: String, base: URL, userAgent: String,
                 start: Start, operations: [OfflineOperation]) {
        self.id = id
        self.createdAt = createdAt
        self.questId = questId
        self.base = base
        self.userAgent = userAgent
        self.start = start
        self.operations = operations
    }

    var json: QuestJSON {
        var out: [String: QuestJSON] = [
            "v": 1,
            "questId": .string(questId),
            "base": .string(base.absoluteString),
            "userAgent": .string(userAgent),
            "operations": .array(operations.map { operation in
                switch operation {
                case let .patch(questionId, value):
                    return ["patch": ["questionId": .string(questionId), "value": value.json]]
                case let .submit(answers):
                    return ["submit": .object(answers.mapValues(\.json))]
                }
            }),
        ]
        switch start {
        case .created(let responseId): out["responseId"] = .string(responseId)
        case .pending(let body): out["create"] = body
        }
        return .object(out)
    }

    /// nil for a file this version can't send.
    init?(json: QuestJSON, id: String, createdAt: Date) {
        guard let questId = json["questId"]?.stringValue, let base = json["base"]?.stringValue.flatMap(URL.init(string:)),
              let userAgent = json["userAgent"]?.stringValue, let list = json["operations"]?.arrayValue else { return nil }
        var operations: [OfflineOperation] = []
        for item in list {
            if let patch = item["patch"], let questionId = patch["questionId"]?.stringValue,
               let value = patch["value"].flatMap(QuestAnswer.init(json:)) {
                operations.append(.patch(questionId: questionId, value: value))
            } else if let answers = item["submit"]?.objectValue {
                operations.append(.submit(answers: answers.compactMapValues(QuestAnswer.init(json:))))
            } else {
                return nil
            }
        }
        let start: Start
        if let responseId = json["responseId"]?.stringValue {
            start = .created(responseId: responseId)
        } else if let body = json["create"]?.objectValue {
            start = .pending(createBody: .object(body))
        } else {
            // Without an id and without the body to get one, nothing in it can be sent.
            return nil
        }
        self.init(id: id, createdAt: createdAt, questId: questId, base: base, userAgent: userAgent,
                  start: start, operations: operations)
    }
}

/// How much the queue may hold. Old answers lose value and a phone's disk is not ours.
package struct OfflineQueuePolicy: Equatable, Sendable {
    package var maxItems: Int
    package var maxAge: TimeInterval

    package init(maxItems: Int = 20, maxAge: TimeInterval = 7 * 24 * 60 * 60) {
        self.maxItems = maxItems
        self.maxAge = maxAge
    }

    /// Everything past `maxAge`, then the oldest until `maxItems` fit. Oldest first.
    package func idsToDrop(_ entries: [(id: String, createdAt: Date)], now: Date) -> [String] {
        var drop: [String] = []
        var keep: [String] = []
        for entry in entries.sorted(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) {
            if now.timeIntervalSince(entry.createdAt) > maxAge { drop.append(entry.id) } else { keep.append(entry.id) }
        }
        if keep.count > maxItems { drop += keep.prefix(keep.count - maxItems) }
        return drop
    }
}

/// One JSON file per response, named `<epoch ms>-<uuid>.json` so its age survives
/// without a database. Written atomically, so a crash never leaves half a file.
package final class OfflineQuestStore: @unchecked Sendable {
    package let root: URL
    package let policy: OfflineQueuePolicy
    private let files = FileManager.default

    package init(root: URL, policy: OfflineQueuePolicy = OfflineQueuePolicy()) {
        self.root = root
        self.policy = policy
    }

    @discardableResult
    package func add(_ entry: OfflineResponse, now: Date = Date()) throws -> String {
        try prepareRoot()
        var entry = entry
        entry.id = "\(Int64((now.timeIntervalSince1970 * 1000).rounded()))-\(UUID().uuidString.lowercased())"
        entry.createdAt = now
        try write(entry)
        prune(now: now)
        return entry.id
    }

    /// Saves progress, so a request already sent is not sent again after a crash.
    package func update(_ entry: OfflineResponse) throws {
        try write(entry)
    }

    private func write(_ entry: OfflineResponse) throws {
        try entry.json.data.write(to: root.appendingPathComponent(entry.id + ".json"), options: .atomic)
    }

    private func prepareRoot() throws {
        guard !files.fileExists(atPath: root.path) else { return }
        try files.createDirectory(at: root, withIntermediateDirectories: true)
        // Answers waiting for the network don't belong in a backup.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = root
        try? url.setResourceValues(values)
    }

    package func remove(_ id: String) {
        guard !id.isEmpty, !id.contains("/") else { return }
        try? files.removeItem(at: root.appendingPathComponent(id + ".json"))
    }

    /// Waiting responses, oldest first, after dropping what the policy no longer allows
    /// and anything unreadable.
    package func entries(now: Date = Date()) -> [OfflineResponse] {
        prune(now: now)
        return scan()
    }

    package func prune(now: Date = Date()) {
        for id in policy.idsToDrop(scan().map { ($0.id, $0.createdAt) }, now: now) { remove(id) }
    }

    /// The epoch-ms prefix of a file name, or nil for anything that isn't one.
    package static func createdAt(name: String) -> Date? {
        guard let dash = name.firstIndex(of: "-"), let ms = Int64(name[..<dash]) else { return nil }
        return Date(timeIntervalSince1970: Double(ms) / 1000)
    }

    private func scan() -> [OfflineResponse] {
        guard let names = try? files.contentsOfDirectory(atPath: root.path) else { return [] }
        var out: [OfflineResponse] = []
        for name in names where name.hasSuffix(".json") {
            let id = String(name.dropLast(5))
            guard let created = Self.createdAt(name: id) else { continue }
            let url = root.appendingPathComponent(name)
            guard let data = try? Data(contentsOf: url), let json = try? QuestJSON.parse(data),
                  let entry = OfflineResponse(json: json, id: id, createdAt: created) else {
                try? files.removeItem(at: url)
                continue
            }
            out.append(entry)
        }
        return out.sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }
}

/// Sends what the queue holds. Runs on launch, on return to the foreground and when
/// the network comes back.
package enum OfflineFlusher {
    package enum Outcome: Equatable {
        /// Everything was sent or refused for good.
        case finished
        /// The network or server is still down; the rest waits for the next trigger.
        case stopped
    }

    /// Oldest response first, each in its own order. Sent → gone; refused for good →
    /// reported and gone (an answer alone, or the whole response if its create or
    /// submit is refused); unreachable → kept, and the flush stops there.
    package static func flush(store: OfflineQuestStore, transport: QuestTransport, now: Date = Date(),
                              report: (QaidQuestsErrorReport) -> Void) async -> Outcome {
        for var entry in store.entries(now: now) {
            // No in-flight retries here: the queue itself is the retry.
            let client = QuestClient(base: entry.base, userAgent: entry.userAgent, transport: transport,
                                     retry: QuestRetryPolicy(delays: []))
            let responseId: String
            switch entry.start {
            case .created(let existing):
                responseId = existing
            case .pending(let body):
                switch await client.create(body: body) {
                case .success(let id):
                    responseId = id
                    entry.start = .created(responseId: id)
                    try? store.update(entry)
                case .failure(let error):
                    if error.isRetryable { return .stopped }
                    report(QaidQuestsErrorReport(operation: .createResponse, questId: entry.questId, error: error))
                    store.remove(entry.id)
                    continue
                }
            }
            while let operation = entry.operations.first {
                let result: Result<Void, QaidQuestsError>
                let failed: QaidQuestsErrorReport.Operation
                var questionId: String?
                switch operation {
                case let .patch(id, value):
                    result = await client.patch(responseId: responseId, questionId: id, value: value)
                    failed = .saveAnswer
                    questionId = id
                case let .submit(answers):
                    result = await client.submit(responseId: responseId, answers: answers)
                    failed = .submit
                }
                if case .failure(let error) = result {
                    if error.isRetryable { return .stopped }
                    report(QaidQuestsErrorReport(operation: failed, questId: entry.questId, questionId: questionId,
                                                 error: error))
                }
                entry.operations.removeFirst()
                try? store.update(entry)
            }
            store.remove(entry.id)
        }
        return .finished
    }
}
