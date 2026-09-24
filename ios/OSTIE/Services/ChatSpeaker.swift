import AVFoundation

@MainActor
final class ChatSpeaker: NSObject, AVAudioPlayerDelegate {
    private let native = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private var task: Task<Void, Never>?
    private var continuation: CheckedContinuation<Void, Error>?
    var onError: ((String) -> Void)?
    func speak(_ text: String, settings: Settings, key: String?) {
        stop()
        if settings.ttsModel == "iphone" {
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = AVSpeechSynthesisVoice(language: "pt-BR"); native.speak(utterance); return
        }
        guard let key else { onError?("Salve a chave Gemini para usar a voz do chat."); return }
        task = Task {
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .spokenAudio, options: []); try session.setActive(true)
                var chunks: [String] = [], current = ""
                for word in text.split(whereSeparator: \.isWhitespace) {
                    if current.count + word.count > 1200 { chunks.append(current); current = "" }
                    current += (current.isEmpty ? "" : " ") + word
                }
                if !current.isEmpty { chunks.append(current) }
                for chunk in chunks {
                    try Task.checkCancellation()
                    let data = try await generate(chunk, model: settings.ttsModel, voice: settings.voice, key: key)
                    try Task.checkCancellation(); try await play(data)
                }
            } catch is CancellationError { }
            catch { onError?("Não foi possível ler a resposta com o Gemini TTS. Você pode escolher a voz do iPhone nos Ajustes.") }
        }
    }
    func stop() {
        task?.cancel(); task = nil; native.stopSpeaking(at: .immediate); player?.stop(); player = nil
        let pending = continuation; continuation = nil; pending?.resume(throwing: CancellationError())
    }
    private func generate(_ text: String, model: String, voice: String, key: String) async throws -> Data {
        guard ["gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts"].contains(model) else { throw AppError.message("Modelo TTS inválido.") }
        var request = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent")!)
        request.httpMethod = "POST"; request.timeoutInterval = 90
        request.setValue(key, forHTTPHeaderField: "x-goog-api-key"); request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["contents": [["parts": [["text": "Leia naturalmente em português do Brasil, sem narrar marcações: \(text)"]]]], "generationConfig": ["responseModalities": ["AUDIO"], "speechConfig": ["voiceConfig": ["prebuiltVoiceConfig": ["voiceName": voice]]]]])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw AppError.message("TTS indisponível.") }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        let candidate = (json["candidates"] as? [[String: Any]])?.first ?? [:]
        let content = candidate["content"] as? [String: Any] ?? [:]
        var pcm = Data()
        for part in content["parts"] as? [[String: Any]] ?? [] {
            if let inline = part["inlineData"] as? [String: Any], let encoded = inline["data"] as? String, let bytes = Data(base64Encoded: encoded) { pcm.append(bytes) }
        }
        guard !pcm.isEmpty, pcm.count < 24_000 * 2 * 300 else { throw AppError.message("Áudio TTS inválido.") }
        return Self.wav(pcm)
    }
    private func play(_ data: Data) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (pending: CheckedContinuation<Void, Error>) in
                do {
                    if Task.isCancelled { pending.resume(throwing: CancellationError()); return }
                    let audio = try AVAudioPlayer(data: data); audio.delegate = self
                    continuation = pending; player = audio
                    guard audio.play() else { continuation = nil; throw AppError.message("Falha ao reproduzir áudio.") }
                } catch { pending.resume(throwing: error) }
            }
        } onCancel: { Task { @MainActor [weak self] in self?.stop() } }
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            guard self.player === player else { return }
            let pending = continuation; continuation = nil; self.player = nil
            if flag { pending?.resume() } else { pending?.resume(throwing: AppError.message("Reprodução interrompida.")) }
        }
    }
    static func wav(_ pcm: Data) -> Data {
        var result = Data("RIFF".utf8)
        func append32(_ value: UInt32) { var v = value.littleEndian; withUnsafeBytes(of: &v) { result.append(contentsOf: $0) } }
        func append16(_ value: UInt16) { var v = value.littleEndian; withUnsafeBytes(of: &v) { result.append(contentsOf: $0) } }
        append32(UInt32(pcm.count) + 36); result.append(Data("WAVEfmt ".utf8)); append32(16)
        append16(1); append16(1); append32(24_000); append32(48_000); append16(2); append16(16)
        result.append(Data("data".utf8)); append32(UInt32(pcm.count)); result.append(pcm); return result
    }
}
