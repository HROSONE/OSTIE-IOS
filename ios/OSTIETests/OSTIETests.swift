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
    func testOfficeTextAndZipListing() throws {
        let data = Data(base64Encoded: "UEsDBBQAAAAIAHqDOF1emH2SQAAAAE4AAAARAAAAd29yZC9kb2N1bWVudC54bWyzKbdKyU8uzU3NK1GoyM3JK7Yqt1UqLcqzKkktLlGysym3KgARJXb+OYcX6ij4B4d4uira6INEQGQBmISZYAcAUEsBAhQDFAAAAAgAeoM4XV6YfZJAAAAATgAAABEAAAAAAAAAAAAAAIABAAAAAHdvcmQvZG9jdW1lbnQueG1sUEsFBgAAAAABAAEAPwAAAG8AAAAAAA==")!
        XCTAssertTrue(try ArchiveReader.describe(data, office: true).contains("Olá, OSTIE!"))
        XCTAssertTrue(try ArchiveReader.describe(data, office: false).contains("word/document.xml"))
        XCTAssertThrowsError(try ArchiveReader.extract(ArchiveReader.list(data)[0], data: data, limit: 1))
    }
    func testArchiveRejectsTruncatedAndRandomInput() {
        XCTAssertThrowsError(try ArchiveReader.list(Data()))
        XCTAssertThrowsError(try ArchiveReader.list(Data(repeating: 0, count: 80)))
    }
    @MainActor func testPCMToWAVHasCorrectHeaderAndPayload() {
        let pcm = Data([0, 0, 255, 127])
        let wav = ChatSpeaker.wav(pcm)
        XCTAssertEqual(String(data: wav.prefix(4), encoding: .utf8), "RIFF")
        XCTAssertEqual(wav.count, 48)
        XCTAssertEqual(wav.suffix(4), pcm)
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
