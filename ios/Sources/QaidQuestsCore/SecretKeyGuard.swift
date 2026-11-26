import CryptoKit
import Foundation
import os

/// Catches a platform API secret (`QAID_SECRET_…`) put in this SDK's configuration or
/// metadata.
///
/// That key reads and changes a whole project, and anything in an app can be read by
/// whoever has the app. When one turns up, the SDK:
/// 1. drops it, so it is never sent with a response,
/// 2. logs an error saying what to do,
/// 3. reports its sha256 (never the key) to qaid, which revokes it and emails the owner.
///
/// The same rule as the web embeds' `secret-key-guard.ts`, and as qaid thumbs for iOS.
package enum SecretKeyGuard {
    /// Only the new secret prefix; embed keys (`QAID_PUB_…`) never match.
    package static let pattern = "QAID_SECRET_[0-9A-Za-z]{16,}"
    // A constant pattern; it compiles or no test passes.
    // swiftlint:disable:next force_try
    private static let regex = try! NSRegularExpression(pattern: pattern)

    /// What a report sends as `source`.
    package static let source = "quests-ios"

    package static let message =
        "[qaid] A secret platform API key (QAID_SECRET_…) is in this app's config. " +
        "Anyone who has the app can read it, and it can read and change your project's data. " +
        "The SDK ignored it, and qaid revoked it and emailed the project owner. " +
        "Make a new platform key, keep it on your server, and use the embed key (QAID_PUB_…) here."

    /// `text` with every secret taken out (then trimmed), and the secrets found. A text
    /// with none comes back as it was.
    package static func scrub(_ text: String) -> (value: String, found: [String]) {
        let whole = NSRange(location: 0, length: (text as NSString).length)
        let found = regex.matches(in: text, range: whole).map { (text as NSString).substring(with: $0.range) }
        guard !found.isEmpty else { return (text, []) }
        let cleaned = regex.stringByReplacingMatches(in: text, range: whole, withTemplate: "")
        return (cleaned.trimmingCharacters(in: .whitespacesAndNewlines), found)
    }

    package static func scrub(_ text: String?) -> (value: String?, found: [String]) {
        guard let text else { return (nil, []) }
        let result = scrub(text)
        return (result.value, result.found)
    }

    /// Every configured string that leaves the device: the key, the base URL, the page
    /// URL, the app name (it is in the user agent), the theme id and the visitor id. A base
    /// that is nothing but a secret falls back to qaid's; a page URL, to none. The SDK's
    /// words and look are never sent and not looked at.
    package static func scrub(_ config: QaidQuestsConfiguration) -> (value: QaidQuestsConfiguration, found: [String]) {
        var found: [String] = []
        func cleanText(_ text: String) -> String {
            let result = scrub(text)
            found += result.found
            return result.value
        }
        func cleanOptional(_ text: String?) -> String? {
            text.map(cleanText)
        }
        func cleanURL(_ url: URL) -> URL? {
            let text = url.absoluteString
            let cleaned = cleanText(text)
            return cleaned == text ? url : URL(string: cleaned)
        }

        var out = config
        out.apiKey = cleanText(config.apiKey)
        out.appName = cleanText(config.appName)
        out.base = cleanURL(config.base) ?? QaidQuestsConfiguration.defaultBase
        out.pageUrl = config.pageUrl.flatMap(cleanURL)
        out.themeId = cleanOptional(config.themeId)
        out.visitorId = cleanOptional(config.visitorId)
        return found.isEmpty ? (config, []) : (out, found)
    }

    /// The values of a metadata dictionary (a view's `metadata:`). Keys are left alone.
    package static func scrub(_ metadata: [String: String]) -> (value: [String: String], found: [String]) {
        var found: [String] = []
        let out = metadata.mapValues { value -> String in
            let result = scrub(value)
            found += result.found
            return result.value
        }
        return (out, found)
    }

    /// The user and the custom pairs the app set with `QaidQuests.setUser` / `setMetadata`.
    package static func scrub(_ app: QuestAppMetadata) -> (value: QuestAppMetadata, found: [String]) {
        let id = scrub(app.user?.id), email = scrub(app.user?.email), name = scrub(app.user?.name)
        let custom = scrub(app.custom)
        let found = id.found + email.found + name.found + custom.found
        guard !found.isEmpty else { return (app, []) }
        var out = QuestAppMetadata(user: app.user.map { _ in QuestUser(id: id.value, email: email.value, name: name.value) })
        for (key, value) in custom.value { out.set(key, value) }
        return (out, found)
    }

    // MARK: The report

    /// `/api/leaked-key` on the base's origin; nil for a base with no host.
    package static func reportURL(base: URL) -> URL? {
        guard base.host != nil else { return nil }
        var parts = URLComponents()
        parts.scheme = base.scheme
        parts.host = base.host
        parts.port = base.port
        parts.path = "/api/leaked-key"
        return parts.url
    }

    /// Lower-case hex sha256 of the UTF-8 text.
    package static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// `{"keyHash", "pageUrl", "source"}`. Carries the key's hash, never the key.
    package static func reportBody(secret: String, pageUrl: String?) -> Data {
        var body: [String: QuestJSON] = ["keyHash": .string(sha256Hex(secret)), "source": .string(source)]
        if let pageUrl { body["pageUrl"] = .string(pageUrl) }
        return QuestJSON.object(body).data
    }

    /// The report's request: a `text/plain` POST, as the web embeds send it.
    package static func reportRequest(secret: String, base: URL, pageUrl: String?) -> QuestHTTPRequest? {
        guard let url = reportURL(base: base) else { return nil }
        return QuestHTTPRequest(method: "POST", url: url, headers: ["Content-Type": "text/plain"],
                                body: reportBody(secret: secret, pageUrl: pageUrl))
    }

    private static let logger = Logger(subsystem: "dev.qaid.quests", category: "QaidQuests")

    /// The default log: an error in the unified log (and Xcode's console).
    package static func log(_ message: String) {
        logger.error("\(message, privacy: .public)")
    }
}

/// Logs a found secret and reports each one once per process. Thread-safe.
package final class SecretKeyReporter: @unchecked Sendable {
    package static let shared = SecretKeyReporter()

    private let lock = NSLock()
    private var reported = Set<String>()

    package init() {}

    /// Logs the error, then sends one report for each secret not reported yet over
    /// `transport`, without waiting. Every failure is swallowed.
    package func report(_ found: [String], base: URL, pageUrl: String?, transport: QuestTransport,
                        log: (String) -> Void = SecretKeyGuard.log) {
        guard !found.isEmpty else { return }
        log(SecretKeyGuard.message)
        for secret in claim(found) {
            guard let request = SecretKeyGuard.reportRequest(secret: secret, base: base, pageUrl: pageUrl) else { continue }
            Task { _ = try? await transport.send(request) }
        }
    }

    private func claim(_ found: [String]) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return found.filter { reported.insert($0).inserted }
    }

    /// Test hook: forget what this process reported.
    package func reset() {
        lock.lock()
        defer { lock.unlock() }
        reported.removeAll()
    }
}
