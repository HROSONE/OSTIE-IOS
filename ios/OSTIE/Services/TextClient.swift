import Foundation

@MainActor
final class TextClient {
    typealias ToolRunner = (String, [String: Any]) async -> [String: Any]
    func answer(settings: Settings, key: String, messages: [Message], system: String,
                attachments: [Attachment] = [], declarations: [[String: Any]] = [],
                run: ToolRunner? = nil, onText: @escaping (String) -> Void) async throws -> String {
        let provider: Provider = attachments.isEmpty ? settings.provider : .gemini
        let selected = attachments.isEmpty ? settings.model : "gemini-2.5-flash"
        let candidates = ModelCatalog.candidates(selected, defaults: provider == .gemini ? ModelCatalog.text : [], fallback: settings.fallback)
        for (index, model) in candidates.enumerated() {
            var emitted = false
            do {
                return try await stream(provider: provider, model: model, key: key, messages: messages, system: system,
                    attachments: attachments, declarations: declarations, run: run, grounded: false) { delta in emitted = true; onText(delta) }
            } catch let error as AppError {
                // Never replay an action or duplicate a partially delivered response.
                if emitted || !error.allowsFallback || index == candidates.count - 1 { throw error }
            }
        }
        throw AppError.message("Nenhum modelo respondeu.")
    }
    func search(query: String, settings: Settings, key: String) async throws -> String {
        try await stream(provider: .gemini, model: settings.geminiModel, key: key,
            messages: [Message(role: "user", text: query)], system: "Pesquise informações atuais. Responda em português com fatos e fontes.",
            attachments: [], declarations: [], run: nil, grounded: true, onText: { _ in })
    }
    private func stream(provider: Provider, model: String, key: String, messages: [Message], system: String,
                        attachments: [Attachment], declarations: [[String: Any]], run: ToolRunner?,
                        grounded: Bool, onText: @escaping (String) -> Void) async throws -> String {
        guard !key.isEmpty else { throw AppError.message("Salve a chave de \(provider.rawValue) nos Ajustes.") }
        var contents: [[String: Any]] = messages.suffix(12).map {
            if provider == .gemini { return ["role": $0.role == "assistant" ? "model" : "user", "parts": [["text": $0.text]]] }
            return ["role": $0.role, "content": $0.text]
        }
        if provider == .gemini, !attachments.isEmpty {
            let parts: [[String: Any]] = attachments.map { ["inlineData": ["mimeType": $0.mime, "data": $0.data.base64EncodedString()]] }
            if let last = contents.indices.last {
                contents[last]["parts"] = (contents[last]["parts"] as? [[String: Any]] ?? []) + parts
            }
        }
        var complete = ""
        for _ in 0..<8 {
            try Task.checkCancellation()
            var request: URLRequest
            var body: [String: Any]
            if provider == .gemini {
                guard let safeModel = model.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed), !safeModel.contains("/") else { throw AppError.message("ID de modelo inválido.") }
                request = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(safeModel):streamGenerateContent?alt=sse")!)
                request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
                body = ["contents": contents, "systemInstruction": ["parts": [["text": system]]]]
                if grounded { body["tools"] = [["googleSearch": [:]]] }
                else if !declarations.isEmpty { body["tools"] = [["functionDeclarations": declarations]] }
            } else {
                let endpoint = provider == .groq ? "https://api.groq.com/openai/v1/chat/completions" : "https://openrouter.ai/api/v1/chat/completions"
                request = URLRequest(url: URL(string: endpoint)!)
                request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
                body = ["model": model, "stream": true, "messages": [["role": "system", "content": system]] + contents]
                if !declarations.isEmpty { body["tools"] = declarations.map { ["type": "function", "function": $0] } }
            }
            request.httpMethod = "POST"; request.timeoutInterval = 90
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw AppError.message("Resposta de rede inválida.") }
            guard 200..<300 ~= http.statusCode else { throw AppError.http(http.statusCode) }
            var decoder = SSEDecoder()
            var calls: [[String: Any]] = []
            var openAICalls: [Int: (id: String, name: String, arguments: String)] = [:]
            var turnText = ""
            var sources: Set<String> = []
            for try await line in bytes.lines {
                try Task.checkCancellation()
                guard let event = decoder.consume(line), event != "[DONE]", let data = event.data(using: .utf8),
                      let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                if json["error"] != nil { throw AppError.message("O provedor interrompeu a resposta.") }
                if provider == .gemini {
                    let candidate = (json["candidates"] as? [[String: Any]])?.first ?? [:]
                    let content = candidate["content"] as? [String: Any] ?? [:]
                    for part in content["parts"] as? [[String: Any]] ?? [] {
                        if let value = part["text"] as? String, part["thought"] as? Bool != true { turnText += value; onText(value) }
                        if let call = part["functionCall"] as? [String: Any] { calls.append(part); _ = call }
                    }
                    let grounding = candidate["groundingMetadata"] as? [String: Any] ?? [:]
                    for chunk in grounding["groundingChunks"] as? [[String: Any]] ?? [] {
                        if let web = chunk["web"] as? [String: Any], let uri = web["uri"] as? String, uri.hasPrefix("https://") { sources.insert(uri) }
                    }
                } else {
                    let choice = (json["choices"] as? [[String: Any]])?.first ?? [:]
                    let delta = choice["delta"] as? [String: Any] ?? [:]
                    if let value = delta["content"] as? String { turnText += value; onText(value) }
                    for call in delta["tool_calls"] as? [[String: Any]] ?? [] {
                        let i = call["index"] as? Int ?? 0
                        var current = openAICalls[i] ?? ("", "", "")
                        let fn = call["function"] as? [String: Any] ?? [:]
                        current.id += call["id"] as? String ?? ""; current.name += fn["name"] as? String ?? ""; current.arguments += fn["arguments"] as? String ?? ""
                        openAICalls[i] = current
                    }
                }
            }
            complete += turnText
            if !sources.isEmpty {
                let suffix = "\n\nFontes:\n" + sources.sorted().prefix(5).joined(separator: "\n")
                complete += suffix; onText(suffix)
            }
            if calls.isEmpty && openAICalls.isEmpty {
                guard !complete.isEmpty else { throw AppError.message("O modelo não retornou conteúdo.") }; return complete
            }
            guard let run else { throw AppError.message("O modelo pediu uma ferramenta indisponível.") }
            if provider == .gemini {
                contents.append(["role": "model", "parts": (turnText.isEmpty ? [] : [["text": turnText]]) + calls])
                var responses: [[String: Any]] = []
                for part in calls {
                    let call = part["functionCall"] as? [String: Any] ?? [:]
                    let name = call["name"] as? String ?? ""
                    let result = await run(name, call["args"] as? [String: Any] ?? [:])
                    responses.append(["functionResponse": ["name": name, "response": result]])
                }
                contents.append(["role": "user", "parts": responses])
            } else {
                let ordered = openAICalls.keys.sorted().compactMap { openAICalls[$0] }
                contents.append(["role": "assistant", "content": turnText, "tool_calls": ordered.map { ["id": $0.id, "type": "function", "function": ["name": $0.name, "arguments": $0.arguments]] }])
                for call in ordered {
                    let args = (try? JSONSerialization.jsonObject(with: Data(call.arguments.utf8))) as? [String: Any] ?? [:]
                    let result = await run(call.name, args)
                    let resultData = try JSONSerialization.data(withJSONObject: result)
                    contents.append(["role": "tool", "tool_call_id": call.id, "content": String(decoding: resultData, as: UTF8.self)])
                }
            }
            // Prevent fallback after a tool has executed, even if no visible text was emitted.
            onText("")
        }
        throw AppError.message("Limite de etapas atingido. Peça para continuar.")
    }
    func models(provider: Provider, key: String) async throws -> [String] {
        var output: [String] = [], page: String?
        repeat {
            var components = URLComponents(string: provider == .gemini ? "https://generativelanguage.googleapis.com/v1beta/models" : "https://api.groq.com/openai/v1/models")!
            if let page { components.queryItems = [URLQueryItem(name: "pageToken", value: page)] }
            var request = URLRequest(url: components.url!)
            request.setValue(provider == .gemini ? key : "Bearer \(key)", forHTTPHeaderField: provider == .gemini ? "x-goog-api-key" : "Authorization")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw AppError.message("Não foi possível consultar os modelos.") }
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
            for model in json[provider == .gemini ? "models" : "data"] as? [[String: Any]] ?? [] {
                if provider == .gemini {
                    let methods = model["supportedGenerationMethods"] as? [String] ?? []
                    if methods.contains("bidiGenerateContent"), let name = model["name"] as? String, !name.contains("transcribe"), !name.contains("translate") { output.append(name.replacingOccurrences(of: "models/", with: "")) }
                } else if let id = model["id"] as? String { output.append(id) }
            }
            page = json["nextPageToken"] as? String
        } while page != nil
        return output.sorted()
    }
}
