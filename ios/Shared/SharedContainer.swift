import Foundation

// Same identifier in all three entitlements; change together when signing with another team.
enum SharedContainer {
    static let group = "group.com.hrosone.ostie.ios"
    static var root: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) }
    static var frameURL: URL? { root?.appendingPathComponent("latest-screen.jpg") }
    static var consentURL: URL? { root?.appendingPathComponent("screen-session.json") }
    static var inbox: URL? { root?.appendingPathComponent("Inbox", isDirectory: true) }
    static func allowScreen(_ allowed: Bool) throws {
        guard let url = consentURL else { throw NSError(domain: "OSTIE", code: 1, userInfo: [NSLocalizedDescriptionKey: "O App Group precisa estar configurado na assinatura."]) }
        if allowed {
            try JSONEncoder().encode(Date()).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } else {
            try? FileManager.default.removeItem(at: url)
            if let frameURL { try? FileManager.default.removeItem(at: frameURL) }
        }
    }
    static var screenAllowed: Bool {
        guard let url = consentURL, let data = try? Data(contentsOf: url), let date = try? JSONDecoder().decode(Date.self, from: data) else { return false }
        return Date().timeIntervalSince(date) < 8 // main app renews consent while Live actually runs
    }
}
