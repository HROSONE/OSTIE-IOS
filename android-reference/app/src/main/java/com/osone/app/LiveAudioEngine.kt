package com.osone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** PCM16 mono: 16 kHz de entrada e 24 kHz de saída, sem reconhecimento de fala ou TTS. */
class LiveAudioEngine(
    private val context: Context,
    private val send: (String) -> Unit,
    private val inputLevel: (Float) -> Unit,
    private val outputLevel: (Float) -> Unit,
    private val onError: () -> Unit,
    private val onDiagnostic: (String) -> Unit
) {
    private data class AudioChunk(val generation: Int, val bytes: ByteArray)
    private val queue = ArrayBlockingQueue<AudioChunk>(96)
    private val queuedBytes = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    private val outputLock = Any()
    @Volatile private var running = false
    @Volatile private var turnEnded = false
    @Volatile var muted = false
    @Volatile var outputGain = 1f
    /** Com o alto-falante do aparelho, evita que a própria voz do OSTIE volte ao microfone. */
    @Volatile var echoGuard = true
    /** Momento até o qual o alto-falante ainda pode estar emitindo a resposta. */
    @Volatile private var speakerBusyUntil = 0L
    @Volatile private var privateOutput = false
    /** Com legendas, a fala transcrita confirma se uma interrupção foi mesmo do usuário. */
    @Volatile var canConfirm = false
    private val calibration = loadCalibration(context)
    @Volatile private var pendingBargeAt = 0L
    @Volatile private var measuredEcho = -1f
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var leftoverByte: Byte? = null
    private var lastOverflowWarning = 0L
    fun start() {
        // Reprodução usa a rota/volume de mídia do aparelho. A entrada continua com
        // VOICE_COMMUNICATION e AEC quando o dispositivo fornecer cancelamento de eco.
        val inputBuffer = maxOf(4096, AudioRecord.getMinBufferSize(16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val outputBuffer = maxOf(32768, AudioTrack.getMinBufferSize(24000,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inputBuffer)
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release(); throw IllegalStateException("Microfone indisponível")
        }
        echoCanceler = try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(input.audioSessionId)?.also { it.enabled = true }
            else null
        } catch (_: Exception) { null }
        noiseSuppressor = try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(input.audioSessionId)?.also { it.enabled = true }
            else null
        } catch (_: Exception) { null }
        if (echoCanceler?.enabled != true)
            onDiagnostic("Cancelamento de eco do Android indisponível; a proteção de eco do OSTIE fica responsável por evitar interrupções.")
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(outputBuffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            releaseEffects()
            input.release(); output.release(); throw IllegalStateException("Áudio indisponível")
        }
        // O pré-buffer é controlado pela fila acima; respostas curtas também precisam tocar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) output.setStartThresholdInFrames(1)
        try { input.startRecording() } catch (error: Exception) {
            releaseEffects()
            input.release(); output.release(); throw error
        }
        recorder = input; player = output; running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val frame = ByteArray(FRAME_BYTES) // 40 ms at 16 kHz, 16-bit mono.
            val silence = Base64.encodeToString(ByteArray(FRAME_BYTES), Base64.NO_WRAP)
            val held = ArrayDeque<String>() // Início da fala do usuário enquanto o OSTIE fala.
            var echoLevel = calibration.echoFloor
            var loudFrames = 0
            var bargeInUntil = 0L
            var lastRouteCheck = 0L
            while (running) {
                val count = try { input.read(frame, 0, frame.size) } catch (_: Exception) { break }
                if (count <= 0) { if (running) onError(); break }
                if (muted) { inputLevel(0f); held.clear(); loudFrames = 0; continue }
                val now = System.currentTimeMillis()
                if (now - lastRouteCheck > 1500) { lastRouteCheck = now; privateOutput = usesPrivateOutput() }
                val level = amplitude(frame, count)
                inputLevel(level)
                val encoded = Base64.encodeToString(frame, 0, count, Base64.NO_WRAP)
                val speakerTalking = echoGuard && !privateOutput && now < speakerBusyUntil
                // Interrupção sem fala transcrita em seguida era o próprio eco: a barreira sobe.
                val pending = pendingBargeAt
                if (pending > 0 && now - pending > CONFIRM_MS) {
                    pendingBargeAt = 0
                    synchronized(calibration) { calibration.falseBarge() }
                }
                if (!speakerTalking || now < bargeInUntil) {
                    // Sem eco possível (ou usuário já interrompeu): áudio real, contínuo.
                    if (speakerTalking && level > threshold(echoLevel)) bargeInUntil = now + BARGE_HOLD_MS
                    held.clear() // Quadros retidos eram eco; não chegam ao serviço.
                    loudFrames = 0
                    if (!speakerTalking) echoLevel = maxOf(0.02f, echoLevel * 0.9f)
                    send(encoded)
                    continue
                }
                // O alto-falante está tocando: o microfone ouve a própria resposta. O Gemini
                // recebe silêncio até a fala do usuário superar claramente o eco medido.
                if (level > threshold(echoLevel)) loudFrames++
                else { loudFrames = 0; echoLevel = maxOf(level, echoLevel * 0.97f).coerceAtMost(0.4f); measuredEcho = echoLevel }
                held.addLast(encoded)
                if (held.size > BARGE_FRAMES) held.removeFirst()
                if (loudFrames >= BARGE_FRAMES) {
                    bargeInUntil = now + BARGE_HOLD_MS
                    if (canConfirm) pendingBargeAt = now
                    held.forEach(send); held.clear()
                    loudFrames = 0
                } else send(silence)
            }
        }, "osone-microphone").start()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var buffering = true
            var targetBytes = 8640 // 180 ms de pré-buffer para a saída PCM do Android.
            var lastUnderruns = output.underrunCount
            var lastWarning = 0L
            var observedGeneration = generation.get()
            var writtenFrames = 0L
            while (running) {
                if (observedGeneration != generation.get()) {
                    observedGeneration = generation.get()
                    writtenFrames = 0L
                    buffering = true
                }
                // turnComplete indica fim da entrada, não que o alto-falante terminou.
                if (turnEnded && queuedBytes.get() == 0 && queue.isEmpty()) {
                    val remaining = writtenFrames - output.playbackHeadPosition.toLong()
                    if (buffering || remaining <= 240L) {
                        synchronized(outputLock) { if (running) { output.pause(); output.flush() } }
                        writtenFrames = 0L
                        turnEnded = false
                        buffering = true
                    } else {
                        try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                    }
                    if (turnEnded || buffering) continue
                }
                if (buffering && queuedBytes.get() < targetBytes && !(turnEnded && queuedBytes.get() > 0)) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    continue
                }
                if (buffering) {
                    try { if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play() }
                    catch (_: Exception) { if (running) onError(); break }
                    buffering = false
                    lastUnderruns = output.underrunCount
                }
                val next = try { queue.poll(25, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (next == null) {
                    if (!turnEnded && output.underrunCount > lastUnderruns) {
                        lastUnderruns = output.underrunCount
                        targetBytes = minOf(targetBytes + 2880, 24000) // Máximo de 500 ms.
                        buffering = true
                        val now = System.currentTimeMillis()
                        if (now - lastWarning > 3000) {
                            lastWarning = now
                            onDiagnostic("Faltou áudio para reprodução; buffer ampliado para ${targetBytes / 48} ms.")
                        }
                    }
                    outputLevel(0f)
                    continue
                }
                queuedBytes.updateAndGet { maxOf(0, it - next.bytes.size) }
                if (next.generation != generation.get()) continue
                val playback = amplify(next.bytes, outputGain)
                var offset = 0
                while (running && next.generation == generation.get() && offset < playback.size) {
                    val count = try { synchronized(outputLock) {
                        if (running && next.generation == generation.get())
                            output.write(playback, offset, minOf(2048, playback.size - offset)) else -1
                    } } catch (_: Exception) { -1 }
                    if (count <= 0) { if (running && next.generation == generation.get()) onDiagnostic("Falha ao escrever no alto-falante."); break }
                    offset += count
                    writtenFrames += count / 2
                    val pendingMs = maxOf(0L, writtenFrames - output.playbackHeadPosition.toLong()) / 24
                    speakerBusyUntil = System.currentTimeMillis() + pendingMs + ECHO_TAIL_MS
                    outputLevel(amplitude(playback, count, offset - count))
                }
            }
        }, "osone-speaker").start()
    }

    @Synchronized fun receive(encoded: String) {
        if (!running) return
        val decoded = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) {
            onDiagnostic("Bloco de áudio inválido recebido do serviço."); return
        }
        if (decoded.isEmpty()) return
        val data = if (leftoverByte != null) byteArrayOf(leftoverByte!!) + decoded else decoded
        leftoverByte = if (data.size % 2 != 0) data.last() else null
        val bytes = if (leftoverByte != null) data.copyOf(data.size - 1) else data
        if (bytes.isEmpty()) return
        queuedBytes.addAndGet(bytes.size)
        if (!queue.offer(AudioChunk(generation.get(), bytes))) {
            queuedBytes.addAndGet(-bytes.size)
            val now = System.currentTimeMillis()
            if (now - lastOverflowWarning > 3000) {
                lastOverflowWarning = now
                onDiagnostic("Fila de reprodução cheia; áudio do serviço chegou mais rápido que o aparelho reproduziu.")
            }
        }
    }

    fun finishTurn() { turnEnded = true }

    /** Volta ao padrão (Recalibrar, no painel do Live). */
    fun resetCalibration() {
        pendingBargeAt = 0; measuredEcho = -1f
        synchronized(calibration) { calibration.reset() }
    }

    /** A transcrição trouxe fala do usuário: a última interrupção era real. */
    fun userSpoke() {
        if (pendingBargeAt == 0L) return
        pendingBargeAt = 0
        synchronized(calibration) { calibration.realBarge() }
    }

    @Synchronized fun interrupt() {
        generation.incrementAndGet()
        queue.clear()
        queuedBytes.set(0)
        leftoverByte = null
        turnEnded = false
        synchronized(outputLock) {
            player?.let { if (running) { it.pause(); it.flush() } }
        }
        // O som já emitido ainda ecoa por alguns milissegundos no ambiente.
        speakerBusyUntil = minOf(speakerBusyUntil, System.currentTimeMillis() + 200)
        outputLevel(0f)
    }

    fun stop() {
        val wasRunning = running
        running = false
        if (wasRunning) saveCalibration()
        generation.incrementAndGet()
        queue.clear()
        queuedBytes.set(0)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        releaseEffects()
        synchronized(outputLock) {
            try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
            player?.release(); player = null
        }
        inputLevel(0f); outputLevel(0f)
    }

    private fun releaseEffects() {
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
    }

    private fun threshold(echo: Float) = synchronized(calibration) { calibration.threshold(echo) }

    private fun loadCalibration(context: Context) = EchoCalibration.fromJson(
        context.getSharedPreferences(CALIBRATION_PREFS, 0).getString("calibration", null)).apply { relax() }

    private fun saveCalibration() {
        val json = synchronized(calibration) {
            measuredEcho.takeIf { it > 0f }?.let(calibration::learnEcho)
            calibration.toJson()
        }
        context.getSharedPreferences(CALIBRATION_PREFS, 0).edit().putString("calibration", json).apply()
    }

    /** Fones com fio, Bluetooth, USB ou aparelhos auditivos não realimentam o microfone. */
    private fun usesPrivateOutput(): Boolean = try {
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUTS }
    } catch (_: Exception) { false }

    /** Ganho digital com saturação: evita overflow ao aumentar uma resposta PCM baixa. */
    private fun amplify(input: ByteArray, gain: Float): ByteArray {
        if (gain == 1f) return input
        val result = input.copyOf()
        for (i in 0 until result.size - 1 step 2) {
            val value = (((result[i + 1].toInt() and 255) shl 8) or
                (result[i].toInt() and 255)).toShort().toInt()
            val scaled = (value * gain).toInt().coerceIn(-32768, 32767)
            result[i] = scaled.toByte()
            result[i + 1] = (scaled shr 8).toByte()
        }
        return result
    }

    private fun amplitude(bytes: ByteArray, count: Int, start: Int = 0): Float {
        var sum = 0.0
        val samples = count / 2
        if (samples == 0) return 0f
        for (i in 0 until samples) {
            val at = start + i * 2
            val sample = ((bytes[at + 1].toInt() shl 8) or (bytes[at].toInt() and 255)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return (sqrt(sum / samples) / 10000.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val FRAME_BYTES = 1280
        private const val ECHO_TAIL_MS = 300L
        private const val BARGE_FRAMES = 3 // 120 ms de fala acima do eco para interromper.
        private const val BARGE_HOLD_MS = 1200L
        private const val CONFIRM_MS = 3500L
        const val CALIBRATION_PREFS = "osone_echo"
        private val PRIVATE_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, 23 /* TYPE_HEARING_AID */,
            26 /* TYPE_BLE_HEADSET */
        )
    }
}
