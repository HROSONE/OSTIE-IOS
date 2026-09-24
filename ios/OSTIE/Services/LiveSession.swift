import Foundation
import Combine
import AVFoundation

@MainActor
final class LiveSession: ObservableObject {
    @Published var active = false
    @Published var connected = false
    @Published var state = "Pronto para conversar"
    @Published var muted = false { didSet { audio.setMuted(muted) } }
    @Published var level: Float = 0
    @Published var inputCaption = ""
    @Published var outputCaption = ""
    @Published var framesSent = 0
    var onError: ((String) -> Void)?
    var onTranscript: ((String, String) -> Void)?
    var runTool: TextClient.ToolRunner?
    private let audio = LiveAudio()
    private var socket: URLSessionWebSocketTask?
    private var connectionTask: Task<Void, Never>?
    private var audioSending = false
    private var videoSending = false
    private var sessionHandle: String?
    private var observers: [NSObjectProtocol] = []
    private var cancelledCalls = Set<String>()
    private var toolTasks: [String: Task<Void, Never>] = [:]
    private var generation = UUID()

    init() {
        audio.onPCM = { [weak self] data, level in Task { @MainActor in self?.sendAudio(data, level: level) } }
        audio.onFailure = { [weak self] in Task { @MainActor in self?.fail("A reprodução de áudio ficou sobrecarregada. Reconecte o Live.") } }
        observers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            if type == .began { Task { @MainActor in self?.stop(); self?.state = "Áudio interrompido pelo iPhone. Toque para reconectar." } }
        })
        observers.append(NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in guard let self, self.connected else { return }; self.stop(); self.state = "Saída de áudio mudou. Toque para reconectar." }
        })
    }
    func start(settings: Settings, key: String, system: String, declarations: [[String: Any]]) {
        stop(); active = true; state = "Conectando…"; sessionHandle = nil
        let token = UUID(); generation = token
        connectionTask = Task {
            let candidates = ModelCatalog.candidates(settings.liveModel, defaults: ModelCatalog.live, fallback: settings.fallback)
            var modelIndex = 0, retries = 0
            while !Task.isCancelled && active && generation == token {
                let model = candidates[modelIndex]
                var ready = false
                do {
                    var request = URLRequest(url: URL(string: "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")!)
                    request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
                    let ws = URLSession.shared.webSocketTask(with: request); socket = ws; ws.resume()
                    var setup: [String: Any] = ["model": "models/\(model)",
                        "generationConfig": ["responseModalities": ["AUDIO"], "speechConfig": ["voiceConfig": ["prebuiltVoiceConfig": ["voiceName": settings.voice]]]],
                        "systemInstruction": ["parts": [["text": system]]],
                        "tools": [["functionDeclarations": declarations]],
                        "inputAudioTranscription": [:], "outputAudioTranscription": [:],
                        "sessionResumption": sessionHandle.map { ["handle": $0] } ?? [:],
                        "contextWindowCompression": ["slidingWindow": [:]]]
                    if declarations.isEmpty { setup.removeValue(forKey: "tools") }
                    try await send(["setup": setup], to: ws)
                    let timeout = Task { try? await Task.sleep(nanoseconds: 20_000_000_000); if !Task.isCancelled && !ready { ws.cancel(with: .goingAway, reason: nil) } }
                    defer { timeout.cancel() }
                    while !Task.isCancelled && generation == token {
                        let message = try await ws.receive()
                        let data: Data
                        switch message { case .data(let value): data = value; case .string(let value): data = Data(value.utf8); @unknown default: continue }
                        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                        if json["error"] != nil { throw AppError.message("O modelo Live recusou a sessão.") }
                        if json["setupComplete"] != nil {
                            ready = true; timeout.cancel()
                            try await audio.start()
                            guard active && generation == token && !Task.isCancelled else { audio.stop(); return }
                            connected = true; state = "Conectado · \(model)"; inputCaption = ""; outputCaption = ""
                        }
                        if let resume = json["sessionResumptionUpdate"] as? [String: Any], resume["resumable"] as? Bool == true { sessionHandle = resume["newHandle"] as? String }
                        if json["goAway"] != nil { ws.cancel(with: .goingAway, reason: nil); break }
                        if let cancelled = json["toolCallCancellation"] as? [String: Any] {
                            for id in cancelled["ids"] as? [String] ?? [] { cancelledCalls.insert(id); toolTasks[id]?.cancel(); toolTasks.removeValue(forKey: id) }
                        }
                        if let tool = json["toolCall"] as? [String: Any] {
                            for call in tool["functionCalls"] as? [[String: Any]] ?? [] { handleTool(call, socket: ws, token: token) }
                        }
                        if let content = json["serverContent"] as? [String: Any] {
                            if content["interrupted"] as? Bool == true { audio.interrupt(); level = 0 }
                            if let input = content["inputTranscription"] as? [String: Any], let text = input["text"] as? String { inputCaption += text }
                            if let output = content["outputTranscription"] as? [String: Any], let text = output["text"] as? String { outputCaption += text }
                            let turn = content["modelTurn"] as? [String: Any] ?? [:]
                            for part in turn["parts"] as? [[String: Any]] ?? [] {
                                if let inline = part["inlineData"] as? [String: Any], let mime = inline["mimeType"] as? String, mime.hasPrefix("audio/pcm"),
                                   let encoded = inline["data"] as? String, let pcm = Data(base64Encoded: encoded) { audio.enqueue(pcm); level = max(level, 0.12) }
                            }
                            if content["turnComplete"] as? Bool == true {
                                audio.finishTurn(); onTranscript?(inputCaption, outputCaption); inputCaption = ""; outputCaption = ""
                            }
                        }
                    }
                } catch {
                    guard active && generation == token && !Task.isCancelled else { return }
                    let code = socket?.closeCode.rawValue ?? 0
                    // Authentication failures must not churn through models or repeat calls.
                    if code == 1008 { fail("Live recusado: confira a chave, a cota e o acesso ao modelo."); return }
                    if !ready && modelIndex + 1 < candidates.count { modelIndex += 1; sessionHandle = nil }
                    else { retries += 1 }
                }
                connected = false; audio.stop(); socket?.cancel(with: .goingAway, reason: nil)
                for task in toolTasks.values { task.cancel() }; toolTasks.removeAll()
                retries += 1
                guard retries <= 4 else { fail("Não foi possível manter o Live conectado. Verifique a rede e tente novamente."); return }
                state = "Reconectando… \(retries)/4"
                try? await Task.sleep(nanoseconds: UInt64(min(retries, 4)) * 1_000_000_000)
            }
        }
    }
    private func handleTool(_ call: [String: Any], socket: URLSessionWebSocketTask, token: UUID) {
        guard let id = call["id"] as? String, let name = call["name"] as? String, toolTasks[id] == nil else { return }
        let args = call["args"] as? [String: Any] ?? [:]
        toolTasks[id] = Task {
            let result = await runTool?(name, args) ?? ["error": "Ferramenta indisponível."]
            guard !Task.isCancelled, generation == token, !cancelledCalls.contains(id), self.socket === socket else { return }
            do { try await send(["toolResponse": ["functionResponses": [["id": id, "name": name, "response": result]]]], to: socket) }
            catch { onError?("Não foi possível devolver o resultado da ferramenta ao Live.") }
            toolTasks.removeValue(forKey: id)
        }
    }
    private func sendAudio(_ data: Data, level: Float) {
        self.level = level
        guard connected, !audioSending, let socket else { return }
        audioSending = true
        Task {
            defer { audioSending = false }
            do { try await send(["realtimeInput": ["audio": ["mimeType": "audio/pcm;rate=16000", "data": data.base64EncodedString()]]], to: socket) }
            catch { socket.cancel(with: .goingAway, reason: nil) }
        }
    }
    func sendFrame(_ data: Data) {
        guard connected, !videoSending, let socket else { return }
        videoSending = true
        Task {
            defer { videoSending = false }
            do { try await send(["realtimeInput": ["video": ["mimeType": "image/jpeg", "data": data.base64EncodedString()]]], to: socket); framesSent += 1 }
            catch { onError?("Um quadro não pôde ser enviado; a conversa continua.") }
        }
    }
    private func send(_ json: [String: Any], to socket: URLSessionWebSocketTask) async throws {
        let data = try JSONSerialization.data(withJSONObject: json)
        try await socket.send(.string(String(decoding: data, as: UTF8.self)))
    }
    func stop() {
        generation = UUID(); active = false; connected = false; level = 0
        connectionTask?.cancel(); connectionTask = nil
        socket?.cancel(with: .normalClosure, reason: nil); socket = nil; audio.stop()
        for task in toolTasks.values { task.cancel() }; toolTasks.removeAll(); cancelledCalls.removeAll()
        audioSending = false; videoSending = false; state = "Pronto para conversar"
        try? SharedContainer.allowScreen(false)
    }
    private func fail(_ reason: String) { stop(); state = reason; onError?(reason) }
}
