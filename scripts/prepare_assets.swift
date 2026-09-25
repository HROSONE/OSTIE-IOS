import CoreGraphics
import Foundation
import ImageIO

// Build-time icon conversion preserves the Android artwork; no network or design changes.
let arguments = CommandLine.arguments
guard arguments.count == 3 else { fatalError("Usage: prepare_assets.swift source.png output.png") }
let source = URL(fileURLWithPath: arguments[1]), output = URL(fileURLWithPath: arguments[2])
guard let input = CGImageSourceCreateWithURL(source as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(input, 0, nil) else {
    fatalError("Cannot decode original orb at \(source.path)")
}
guard let context = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8,
                              bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
    fatalError("Cannot create icon drawing context")
}
let canvas = CGRect(x: 0, y: 0, width: 1024, height: 1024)
context.setFillColor(red: 0.015, green: 0.025, blue: 0.055, alpha: 1)
context.fill(canvas)
context.draw(image, in: canvas)
guard let icon = context.makeImage() else { fatalError("Cannot render icon") }
try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
guard let destination = CGImageDestinationCreateWithURL(output as CFURL, "public.png" as CFString, 1, nil) else {
    fatalError("Cannot create icon output at \(output.path)")
}
CGImageDestinationAddImage(destination, icon, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Cannot write icon at \(output.path)") }
