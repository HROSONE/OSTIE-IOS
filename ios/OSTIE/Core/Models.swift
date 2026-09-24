import Foundation

struct Message: Codable, Identifiable, Equatable {
    var id = UUID()
    var role: String
    var text: String
    var date = Date()
}
enum Provider: String, Codable, CaseIterable, Identifiable {
    case gemini = "Gemini", groq = "Groq", openRouter = "OpenRouter"
    var id: String { rawValue }
    var defaultModel: String {
        switch self { case .gemini: return "gemini-3.8-flash"; case .groq: return "openai/gpt-oss-20b"; case .openRouter: return "openrouter/free" }
    }
}
struct Settings: Codable, Equatable {
    var name = ""
    var provider = Provider.gemini
    var geminiModel = "gemini-3.8-flash"
    var groqModel = "openai/gpt-oss-20b"
    var routerModel = "openrouter/free"
    var liveModel = "gemini-3.8-live"
    var voice = "Puck"
    var fallback = true
    var googleSearch = true
    var darkMode = true
    var saveTranscript = false
    var speakChat = false
    var thinking = "low"
    var ttsModel = "gemini-3.8-flash-lite-tts"
    var codeWriter = "ask"
    var model: String {
        switch provider { case .gemini: return geminiModel; case .groq: return groqModel; case .openRouter: return routerModel }
    }
}
struct Routine: Codable, Identifiable {
    var id = UUID()
    var title: String
    var prompt: String
    var hour: Int
    var minute: Int
    var weekdays: [Int] = [] // Calendar: Sunday = 1
    var enabled = true
    var lastResult = ""
}
struct Attachment: Identifiable {
    var id = UUID()
    var name: String
    var mime: String
    var data: Data
}
struct ModelCatalog {
    static let live = ["gemini-3.8-live", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025"]
    static let text = ["gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-2.5-flash"]
    static let voices = ["Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"]
    static func candidates(_ selected: String, defaults: [String], fallback: Bool) -> [String] {
        [selected] + (fallback ? defaults.filter { $0 != selected } : [])
    }
}
enum AppError: LocalizedError {
    case message(String), http(Int)
    var errorDescription: String? {
        switch self {
        case .message(let value): return value
        case .http(let code):
            switch code { case 401, 403: return "Chave recusada. Confira a chave e o acesso ao modelo."; case 429: return "Cota ou limite de uso atingido."; default: return "O provedor respondeu HTTP \(code)." }
        }
    }
    var allowsFallback: Bool {
        if case .http(let code) = self { return code == 404 || code == 429 || code >= 500 }; return false
    }
}
struct DocumentFormat {
    static func unfenced(_ value: String) -> String {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.hasPrefix("```"), let start = text.firstIndex(of: "\n"), let end = text.range(of: "```", options: .backwards), end.lowerBound > start else { return text }
        return String(text[text.index(after: start)..<end.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func isWeb(_ value: String) -> Bool {
        let t = unfenced(value).lowercased(); return t.contains("<html") || t.contains("<!doctype html") || t.contains("<svg")
    }
}
struct SSEDecoder {
    private var lines: [String] = []
    mutating func consume(_ line: String) -> String? {
        if line.isEmpty { defer { lines.removeAll() }; return lines.isEmpty ? nil : lines.joined(separator: "\n") }
        if line.hasPrefix("data:") { lines.append(String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)) }
        return nil
    }
}
