import AVFoundation
import UIKit
import Combine

final class CameraController: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    @Published private(set) var active = false
    @Published private(set) var preview: UIImage?
    let session = AVCaptureSession()
    var onFrame: ((Data) -> Void)?
    var onError: ((String) -> Void)?
    private let queue = DispatchQueue(label: "ostie.camera")
    private var position: AVCaptureDevice.Position = .back
    private var lastFrame: TimeInterval = 0
    private var requested = false
    func start() {
        queue.async { self.requested = true }
        AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
            guard let self else { return }
            self.queue.async {
                guard self.requested else { return }
                if allowed { self.configure() }
                else { self.onError?("Permita a câmera nos Ajustes do iPhone.") }
            }
        }
    }
    func stop() {
        queue.async {
            self.requested = false; self.session.stopRunning()
            DispatchQueue.main.async { self.active = false; self.preview = nil }
        }
    }
    func flip() { queue.async { self.position = self.position == .back ? .front : .back; if self.requested { self.configure() } } }
    private func configure() {
        session.beginConfiguration()
        defer { session.commitConfiguration(); if requested { session.startRunning() } }
        session.sessionPreset = .vga640x480
        for input in session.inputs { session.removeInput(input) }
        for output in session.outputs { session.removeOutput(output) }
        do {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else { throw AppError.message("Câmera indisponível.") }
            let input = try AVCaptureDeviceInput(device: device)
            guard session.canAddInput(input) else { throw AppError.message("Não foi possível abrir a câmera.") }
            session.addInput(input)
            let output = AVCaptureVideoDataOutput(); output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            output.setSampleBufferDelegate(self, queue: queue)
            guard session.canAddOutput(output) else { throw AppError.message("Não foi possível capturar a imagem.") }
            session.addOutput(output)
            if let connection = output.connection(with: .video), connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
            DispatchQueue.main.async { self.active = true }
        } catch { requested = false; onError?("A câmera falhou. A conversa de voz continua.") }
    }
    private let context = CIContext(options: [.cacheIntermediates: false])
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = ProcessInfo.processInfo.systemUptime
        guard requested, now - lastFrame >= 1, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastFrame = now
        let ci = CIImage(cvPixelBuffer: buffer)
        guard let cg = context.createCGImage(ci, from: ci.extent) else { return }
        let image = UIImage(cgImage: cg)
        guard let data = image.jpegData(compressionQuality: 0.6) else { return }
        DispatchQueue.main.async { self.preview = image }; onFrame?(data)
    }
}
