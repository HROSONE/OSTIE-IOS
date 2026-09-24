import AVFoundation

// All engine mutations use one serial queue; callbacks hand immutable PCM to the main actor.
final class LiveAudio {
    private let queue = DispatchQueue(label: "ostie.audio", qos: .userInteractive)
    private var engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    private var inputConverter: AVAudioConverter?
    private var running = false
    private var muted = false
    private var scheduledFrames: AVAudioFrameCount = 0
    private var playbackGeneration = UUID()
    private let outputFormat = AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1)!
    var onPCM: ((Data, Float) -> Void)?
    var onFailure: (() -> Void)?

    func start() async throws {
        let granted = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
        guard granted else { throw AppError.message("Permita o microfone nos Ajustes do iPhone.") }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    self.stopLocked()
                    let session = AVAudioSession.sharedInstance()
                    try session.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetooth])
                    try session.setPreferredIOBufferDuration(0.02)
                    try session.setActive(true)
                    self.engine = AVAudioEngine(); self.player = AVAudioPlayerNode()
                    try self.engine.inputNode.setVoiceProcessingEnabled(true)
                    self.engine.attach(self.player)
                    self.engine.connect(self.player, to: self.engine.mainMixerNode, format: self.outputFormat)
                    let inputFormat = self.engine.inputNode.outputFormat(forBus: 0)
                    guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
                          let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true),
                          let converter = AVAudioConverter(from: inputFormat, to: pcmFormat) else { throw AppError.message("Formato do microfone indisponível.") }
                    self.inputConverter = converter
                    self.engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
                        guard let self, let pcm = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * 16_000 / inputFormat.sampleRate + 32)) else { return }
                        var supplied = false
                        var error: NSError?
                        let status = converter.convert(to: pcm, error: &error) { _, state in
                            if supplied { state.pointee = .noDataNow; return nil }
                            supplied = true; state.pointee = .haveData; return buffer
                        }
                        guard status != .error, error == nil, pcm.frameLength > 0,
                              let pointer = pcm.int16ChannelData?[0] else { return }
                        let data = Data(bytes: pointer, count: Int(pcm.frameLength) * 2)
                        var energy: Float = 0
                        for i in 0..<Int(pcm.frameLength) { let v = Float(pointer[i]) / 32768; energy += v * v }
                        let level = sqrt(energy / Float(pcm.frameLength))
                        self.queue.async { if self.running { self.onPCM?(self.muted ? Data(count: data.count) : data, self.muted ? 0 : level) } }
                    }
                    self.engine.prepare(); try self.engine.start()
                    self.running = true; continuation.resume()
                } catch { self.stopLocked(); continuation.resume(throwing: error) }
            }
        }
    }
    func setMuted(_ value: Bool) { queue.async { self.muted = value } }
    func enqueue(_ data: Data) {
        queue.async {
            guard self.running, data.count >= 2 else { return }
            let count = data.count / 2
            // Bound playback backlog to avoid minutes of stale audio after a network burst.
            guard self.scheduledFrames < 24_000 * 20,
                  let buffer = AVAudioPCMBuffer(pcmFormat: self.outputFormat, frameCapacity: AVAudioFrameCount(count)),
                  let samples = buffer.floatChannelData?[0] else { self.onFailure?(); return }
            buffer.frameLength = AVAudioFrameCount(count)
            data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                for i in 0..<count {
                    let low = UInt16(raw[i * 2]), high = UInt16(raw[i * 2 + 1]) << 8
                    samples[i] = Float(Int16(bitPattern: low | high)) / 32768
                }
            }
            let generation = self.playbackGeneration
            self.scheduledFrames += buffer.frameLength
            self.player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                guard let self else { return }
                self.queue.async {
                    guard generation == self.playbackGeneration else { return }
                    self.scheduledFrames = self.scheduledFrames >= buffer.frameLength ? self.scheduledFrames - buffer.frameLength : 0
                }
            }
            if !self.player.isPlaying && self.scheduledFrames >= 4320 { self.player.play() }
        }
    }
    func finishTurn() { queue.async { if self.running && self.scheduledFrames > 0 && !self.player.isPlaying { self.player.play() } } }
    func interrupt() { queue.async { self.player.stop(); self.scheduledFrames = 0; self.playbackGeneration = UUID() } }
    func stop() { queue.async { self.stopLocked() } }
    private func stopLocked() {
        if running { engine.inputNode.removeTap(onBus: 0) }
        running = false; player.stop(); engine.stop(); inputConverter = nil; scheduledFrames = 0; playbackGeneration = UUID()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
