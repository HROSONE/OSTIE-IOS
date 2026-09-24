package com.osone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Quem lê as respostas do chat em voz alta. */
enum class ChatVoiceEngine(val value: String, val label: String, val model: String?) {
    FLASH_LITE("flash_lite", "Gemini 3.8 Flash-Lite TTS · mais rápida", "gemini-3.8-flash-lite-tts"),
    FLASH("flash", "Gemini 3.8 Flash TTS · mais expressiva", "gemini-3.8-flash-tts"),
    ANDROID("android", "Voz do Android · sem internet", null);

    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: FLASH_LITE
    }
}

/**
 * Lê as respostas do chat com as vozes do Gemini TTS: divide o texto em partes, gera a próxima enquanto
 * a atual toca e, se o Gemini falhar (sem chave, cota, rede), passa o resto para a voz do Android.
 */
class ChatVoice private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: ChatVoice? = null
        fun get(context: Context): ChatVoice = instance ?: synchronized(this) {
            instance ?: ChatVoice(context.applicationContext).also { instance = it }
        }
        private const val STYLE = "Leia em português do Brasil, com voz natural, calorosa e ritmo de conversa:\n"
    }

    private val preferences = context.getSharedPreferences("osone_config", 0)
    private val generation = AtomicInteger(0)
    private val pool = Executors.newFixedThreadPool(2)
    @Volatile private var track: AudioTrack? = null

    var engine by mutableStateOf(ChatVoiceEngine.fromValue(preferences.getString("chat_voice_engine", null)))
        private set
    var voice by mutableStateOf(LiveVoices.fromName(preferences.getString("chat_voice_name", "Kore")))
        private set
    /** true enquanto uma resposta está sendo falada. */
    var speaking by mutableStateOf(false)
        private set

    fun selectEngine(value: ChatVoiceEngine) {
        engine = value
        preferences.edit().putString("chat_voice_engine", value.value).apply()
    }

    fun selectVoice(name: String) {
        voice = LiveVoices.fromName(name)
        preferences.edit().putString("chat_voice_name", voice).apply()
    }

    /** Fala [answer]; [fallback] recebe o texto que a voz do Android deve ler (engine Android ou falha). */
    fun speak(answer: String, fallback: (String) -> Unit) {
        stop()
        val text = SpeechText.clean(answer)
        if (text.isBlank()) return
        val model = engine.model
        val key = SecureKeyStore(context).read()
        if (model == null || key == null) { fallback(text); return }
        val turn = generation.incrementAndGet()
        speaking = true
        pool.execute {
            val parts = SpeechText.chunks(text)
            var next: Future<Pcm>? = pool.submit(Callable { synthesize(key, model, parts[0]) })
            try {
                for (index in parts.indices) {
                    val pending = next!!
                    // Se outra fala começou (ou parou), solta a fila sem esperar esta parte terminar.
                    while (!pending.isDone) {
                        if (generation.get() != turn) { pending.cancel(true); return@execute }
                        Thread.sleep(40)
                    }
                    val audio = pending.get()
                    if (generation.get() != turn) return@execute
                    // Gera a parte seguinte enquanto esta toca.
                    next = if (index + 1 < parts.size) pool.submit(Callable { synthesize(key, model, parts[index + 1]) }) else null
                    play(audio, turn)
                }
            } catch (failure: Exception) {
                next?.cancel(true)
                if (generation.get() == turn) {
                    val cause = (failure.cause ?: failure)
                    AppDiagnostics.get(context).record("Voz do chat", "${engine.label.substringBefore(" ·")}: " +
                        (if (cause is GeminiHttpException) "HTTP ${cause.status}" else cause.javaClass.simpleName) +
                        "; lendo com a voz do Android.")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (generation.get() == turn) fallback(text) }
                }
            } finally {
                if (generation.get() == turn) speaking = false
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        track?.let { try { it.pause(); it.flush() } catch (_: Exception) { } }
        speaking = false
    }

    private class Pcm(val bytes: ByteArray, val rate: Int)

    private fun synthesize(key: String, model: String, text: String): Pcm {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", STYLE + text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw GeminiHttpException(connection.responseCode)
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: error("Resposta sem áudio.")
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val mime = inline.optString("mimeType")
                if (!mime.startsWith("audio")) continue
                val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
                return Pcm(Base64.decode(inline.optString("data"), Base64.DEFAULT), rate)
            }
            error("Resposta sem áudio.")
        } finally { connection.disconnect() }
    }

    /** PCM 16 bits mono; bloqueia até terminar ou até outra fala começar. */
    private fun play(audio: Pcm, turn: Int) {
        if (audio.bytes.isEmpty()) return
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(audio.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(audio.rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT), 16_384))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = output
        try {
            output.play()
            var offset = 0
            val length = audio.bytes.size - audio.bytes.size % 2
            while (offset < length && generation.get() == turn) {
                val written = output.write(audio.bytes, offset, minOf(8_192, length - offset))
                if (written <= 0) break
                offset += written
            }
            // Espera o fim do que já foi entregue ao alto-falante.
            val frames = length / 2
            val deadline = System.currentTimeMillis() + frames * 1000L / audio.rate + 2_000
            while (generation.get() == turn && output.playbackHeadPosition < frames &&
                System.currentTimeMillis() < deadline) Thread.sleep(20)
        } finally {
            try { output.stop() } catch (_: Exception) { }
            output.release()
            if (track === output) track = null
        }
    }
}
