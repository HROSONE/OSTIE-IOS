import Foundation

actor AttachmentService {
    struct Prepared {
        var parts: [[String: Any]]
        var remoteNames: [String]
    }
    func prepare(_ attachments: [Attachment], key: String) async throws -> Prepared {
        var parts: [[String: Any]] = [], names: [String] = []
        do {
            for attachment in attachments {
                try Task.checkCancellation()
                let ext = (attachment.name as NSString).pathExtension.lowercased()
                if ["zip", "jar", "apk", "docx", "xlsx", "pptx", "odt", "ods", "odp"].contains(ext) {
                    let text = try ArchiveReader.describe(attachment.data, office: !["zip", "jar", "apk"].contains(ext))
                    parts.append(["text": "Conteúdo de \(attachment.name):\n\(text)"])
                } else if attachment.mime.hasPrefix("text/") || ["json", "js", "swift", "kt", "py", "md", "html", "svg", "ts", "tsx", "jsx", "css", "yaml", "yml", "xml", "csv", "sql"].contains(ext) {
                    guard attachment.data.count <= 2_000_000, let text = String(data: attachment.data, encoding: .utf8) else { throw AppError.message("Texto precisa ser UTF-8 e ter até 2 MB.") }
                    parts.append(["text": "Arquivo \(attachment.name):\n\(text)"])
                } else if attachment.data.count <= 8_000_000 {
                    parts.append(["inlineData": ["mimeType": attachment.mime, "data": attachment.data.base64EncodedString()]])
                } else {
                    let file = try await upload(attachment, key: key)
                    names.append(file.name)
                    parts.append(["fileData": ["mimeType": attachment.mime, "fileUri": file.uri]])
                }
            }
            return Prepared(parts: parts, remoteNames: names)
        } catch { await remove(names, key: key); throw error }
    }
    private func upload(_ attachment: Attachment, key: String) async throws -> (name: String, uri: String) {
        var request = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/upload/v1beta/files")!)
        request.httpMethod = "POST"; request.timeoutInterval = 30
        request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        request.setValue("resumable", forHTTPHeaderField: "X-Goog-Upload-Protocol")
        request.setValue("start", forHTTPHeaderField: "X-Goog-Upload-Command")
        request.setValue(String(attachment.data.count), forHTTPHeaderField: "X-Goog-Upload-Header-Content-Length")
        request.setValue(attachment.mime, forHTTPHeaderField: "X-Goog-Upload-Header-Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["file": ["display_name": attachment.name]])
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppError.message("Resposta de upload inválida.") }
        guard 200..<300 ~= http.statusCode else { throw AppError.http(http.statusCode) }
        guard let value = http.value(forHTTPHeaderField: "X-Goog-Upload-URL"), let url = URL(string: value), url.scheme == "https", url.host == "generativelanguage.googleapis.com" else { throw AppError.message("Destino de upload inválido.") }
        var upload = URLRequest(url: url); upload.httpMethod = "POST"; upload.timeoutInterval = 120
        upload.setValue("0", forHTTPHeaderField: "X-Goog-Upload-Offset")
        upload.setValue("upload, finalize", forHTTPHeaderField: "X-Goog-Upload-Command")
        upload.setValue(attachment.mime, forHTTPHeaderField: "Content-Type")
        let (data, uploaded) = try await URLSession.shared.upload(for: upload, from: attachment.data)
        guard let status = uploaded as? HTTPURLResponse, 200..<300 ~= status.statusCode else { throw AppError.message("Falha ao enviar o arquivo.") }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        var file = json["file"] as? [String: Any] ?? [:]
        guard let name = file["name"] as? String, valid(name) else { throw AppError.message("Identificador de arquivo inválido.") }
        do {
            for _ in 0..<30 {
                try Task.checkCancellation()
                if file["state"] as? String == "ACTIVE", let uri = file["uri"] as? String { return (name, uri) }
                if file["state"] as? String == "FAILED" { throw AppError.message("O Gemini não conseguiu processar o arquivo.") }
                try await Task.sleep(nanoseconds: 2_000_000_000)
                var check = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/v1beta/\(name)")!)
                check.setValue(key, forHTTPHeaderField: "x-goog-api-key")
                let (metadata, response) = try await URLSession.shared.data(for: check)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw AppError.message("Não foi possível conferir o arquivo enviado.") }
                file = try JSONSerialization.jsonObject(with: metadata) as? [String: Any] ?? [:]
            }
            throw AppError.message("O processamento do arquivo demorou demais.")
        } catch { await remove([name], key: key); throw error }
    }
    func remove(_ names: [String], key: String) async {
        // Detached cleanup survives cancellation of the user's generation task.
        await Task.detached {
            for name in names where name.range(of: "^files/[A-Za-z0-9_-]+$", options: .regularExpression) != nil {
                var request = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/v1beta/\(name)")!)
                request.httpMethod = "DELETE"; request.timeoutInterval = 10
                request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
                _ = try? await URLSession.shared.data(for: request)
            }
        }.value
    }
    private func valid(_ name: String) -> Bool { name.range(of: "^files/[A-Za-z0-9_-]+$", options: .regularExpression) != nil }
}
