package com.osone.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Captura transitória: um quadro JPEG por segundo, nenhum quadro salvo em disco. */
class ScreenCaptureController(
    private val context: Context,
    private val onFrame: (String, Long) -> Unit,
    private val onCapture: () -> Unit,
    private val onEnd: () -> Unit
) {
    private val thread = HandlerThread("osone-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastFrame = 0L
    private var capturedFrames = 0
    private var captureWidth = 0
    private var captureHeight = 0
    @Volatile private var closed = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { handler.post { stop(); Handler(Looper.getMainLooper()).post(onEnd) } }
        override fun onCapturedContentResize(width: Int, height: Int) {
            handler.post { if (!closed && width > 0 && height > 0 &&
                (width != captureWidth || height != captureHeight)) configure(width, height, resize = true) }
        }
    }

    fun start(resultData: Intent) {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val captured = manager.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
            ?: throw IllegalStateException("Permissão da tela não concedida")
        projection = captured
        captured.registerCallback(callback, handler)
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val fallback = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(fallback)
        val width = if (bounds.width() > 2) bounds.width() else fallback.x
        val height = if (bounds.height() > 2) bounds.height() else fallback.y
        require(width > 2 && height > 2) { "Android não informou as dimensões da tela" }
        // O VirtualDisplay usa o tamanho real; só a imagem enviada é reduzida.
        configure(width, height, resize = false)
        handler.postDelayed({
            if (!closed && capturedFrames == 0 && display != null)
                configure(captureWidth, captureHeight, resize = true)
        }, 4000)
    }

    private fun configure(width: Int, height: Int, resize: Boolean) {
        if (closed) return
        val w = width
        val h = height
        captureWidth = width
        captureHeight = height
        val oldReader = reader
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (!closed && now - lastFrame >= 1000) {
                    lastFrame = now
                    capturedFrames++
                    onCapture()
                    val plane = image.planes[0]
                    val paddedWidth = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                    try {
                        // A última linha pode não conter padding no ByteBuffer da imagem.
                        val bytes = ByteBuffer.allocate(plane.rowStride * image.height)
                        bytes.put(plane.buffer)
                        bytes.rewind()
                        padded.copyPixelsFromBuffer(bytes)
                        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        try {
                            val scale = minOf(1.0, 1600.0 / maxOf(image.width, image.height))
                            val frame = if (scale < 1.0) Bitmap.createScaledBitmap(cropped,
                                maxOf(2, (image.width * scale).toInt()),
                                maxOf(2, (image.height * scale).toInt()), true) else cropped
                            try {
                                ByteArrayOutputStream().use { stream ->
                                    frame.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                                    if (stream.size() > 90_000) {
                                        stream.reset()
                                        frame.compress(Bitmap.CompressFormat.JPEG, 32, stream)
                                    }
                                    if (stream.size() > 0 && System.currentTimeMillis() - now < 1500)
                                        onFrame(Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP), now)
                                }
                            } finally { if (frame !== cropped) frame.recycle() }
                        } finally { cropped.recycle() }
                    } finally { padded.recycle() }
                }
            } catch (failure: Exception) {
                AppDiagnostics.get(context).record("Tela", "Falha ao processar quadro (${failure.javaClass.simpleName}).")
            } finally { image.close() }
        }, handler)
        reader = newReader
        val density = context.resources.displayMetrics.densityDpi
        if (resize && display != null) {
            display?.resize(w, h, density)
            display?.surface = newReader.surface
        } else display = requireNotNull(projection?.createVirtualDisplay("OSONE-tela", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, handler)) {
            "A projeção não criou a superfície de captura"
        }
        oldReader?.close()
    }

    fun stop() {
        if (closed) return
        closed = true
        display?.release(); display = null
        reader?.close(); reader = null
        val active = projection; projection = null
        active?.unregisterCallback(callback)
        active?.stop()
        thread.quitSafely()
    }
}
