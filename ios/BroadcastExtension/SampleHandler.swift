import ReplayKit
import UIKit
import ImageIO

final class SampleHandler: RPBroadcastSampleHandler {
    private var lastFrame: TimeInterval = 0
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var watchdog: DispatchSourceTimer?
    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        guard SharedContainer.screenAllowed else { stopWithMessage(); return }
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 2, repeating: 2)
        timer.setEventHandler { [weak self] in if !SharedContainer.screenAllowed { self?.stopWithMessage() } }
        timer.resume(); watchdog = timer
    }
    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .video else { return } // Audio belongs to the app's live microphone.
        guard SharedContainer.screenAllowed else { stopWithMessage(); return }
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastFrame >= 1, let pixel = CMSampleBufferGetImageBuffer(sampleBuffer), let url = SharedContainer.frameURL else { return }
        lastFrame = now
        autoreleasepool {
            var image = CIImage(cvPixelBuffer: pixel)
            if let value = CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber,
               let orientation = CGImagePropertyOrientation(rawValue: value.uint32Value) { image = image.oriented(orientation) }
            let ratio = min(1, 768 / max(image.extent.width, image.extent.height))
            image = image.transformed(by: CGAffineTransform(scaleX: ratio, y: ratio))
            guard let cg = context.createCGImage(image, from: image.extent), let data = UIImage(cgImage: cg).jpegData(compressionQuality: 0.55) else { return }
            // Single ephemeral slot, atomic replacement; no frame history and no credentials in the extension.
            do { try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
            catch { stopWithMessage() }
        }
    }
    override func broadcastPaused() { if let url = SharedContainer.frameURL { try? FileManager.default.removeItem(at: url) } }
    override func broadcastFinished() { watchdog?.cancel(); watchdog = nil; try? SharedContainer.allowScreen(false) }
    private func stopWithMessage() {
        watchdog?.cancel(); watchdog = nil
        if let url = SharedContainer.frameURL { try? FileManager.default.removeItem(at: url) }
        finishBroadcastWithError(NSError(domain: "OSTIE", code: 1, userInfo: [NSLocalizedDescriptionKey: "Compartilhamento encerrado. Abra o Live e autorize novamente para mostrar a tela."]))
    }
}
