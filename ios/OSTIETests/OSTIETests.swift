import XCTest
@testable import OSTIE

final class OSTIETests: XCTestCase {
    func testStreamFramesAndMultilineEvents() {
        var decoder = SSEDecoder()
        XCTAssertNil(decoder.consume(": keepalive"))
        XCTAssertNil(decoder.consume("data: {\"text\":"))
        XCTAssertNil(decoder.consume("data: \"Olá\"}"))
        XCTAssertEqual(decoder.consume(""), "{\"text\":\n\"Olá\"}")
        XCTAssertNil(decoder.consume(""))
        XCTAssertNil(decoder.consume("data: [DONE]"))
        XCTAssertEqual(decoder.consume(""), "[DONE]")
    }
    func testFallbackDoesNotDuplicateSelectedAndHonorsDisabled() {
        XCTAssertEqual(ModelCatalog.candidates("custom", defaults: ["a", "b"], fallback: false), ["custom"])
        XCTAssertEqual(ModelCatalog.candidates("b", defaults: ["a", "b"], fallback: true), ["b", "a"])
        XCTAssertFalse(AppError.http(401).allowsFallback)
        XCTAssertFalse(AppError.http(403).allowsFallback)
        XCTAssertTrue(AppError.http(429).allowsFallback)
    }
    func testDocumentDetectionAndFenceRemoval() {
        XCTAssertEqual(DocumentFormat.unfenced("```html\n<svg></svg>\n```"), "<svg></svg>")
        XCTAssertTrue(DocumentFormat.isWeb("```html\n<!doctype html><html></html>\n```"))
        XCTAssertFalse(DocumentFormat.isWeb("Texto comum"))
        XCTAssertEqual(DocumentFormat.unfenced("```incompleto"), "```incompleto")
    }
    func testPersistenceRoundtripAndCorruptionPreservesFile() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LocalFiles(root: root)
        let messages = [Message(role: "user", text: "Olá 👋"), Message(role: "assistant", text: "Tudo bem?")]
        try store.write(messages, name: "chat.json")
        XCTAssertEqual(try store.read("chat.json", as: [Message].self), messages)
        try store.writeText("{broken", name: "chat.json")
        XCTAssertThrowsError(try store.read("chat.json", as: [Message].self))
        XCTAssertEqual(try store.text("chat.json"), "{broken")
    }
    func testToolRegistryHasNoAndroidControlClaims() {
        let names = ToolRegistry.declarations(search: false).compactMap { $0["name"] as? String }
        XCTAssertEqual(Set(names).count, names.count)
        XCTAssertFalse(names.contains("read_notifications"))
        XCTAssertFalse(names.contains("tap"))
        XCTAssertFalse(names.contains("web_search"))
        XCTAssertTrue(ToolRegistry.declarations(search: true).contains { $0["name"] as? String == "web_search" })
    }
}
