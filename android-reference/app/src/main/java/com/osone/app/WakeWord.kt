package com.osone.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Escuta ativa: um reconhecedor offline (Vosk, modelo pequeno em português, baixado uma vez) ouve só
 * "Ei, Ostie" e abre o Live. O áudio nunca sai do aparelho; o Live continua sendo aberto normalmente.
 */
object WakeWord {
    private const val PREF = "wake_word"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
    private val main = Handler(Looper.getMainLooper())

    /** Mensagem para Ajustes: download, erro ou estado da escuta. */
    var status by mutableStateOf<String?>(null)
    var downloading by mutableStateOf(false)
        private set
    /** Estado da chave em Ajustes (espelha a preferência salva). */
    var on by mutableStateOf(false)
        private set

    fun load(context: Context) { on = enabled(context) }

    fun enabled(context: Context) = context.getSharedPreferences("osone_config", 0).getBoolean(PREF, false)

    fun modelDir(context: Context) = File(context.filesDir, "vosk-pt")

    fun modelReady(context: Context) = File(modelDir(context), "am/final.mdl").isFile &&
        File(modelDir(context), "conf/model.conf").isFile

    private fun canListen(context: Context) = ContextCompat.checkSelfPermission(context,
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Liga a escuta (com o app aberto): baixa o modelo na primeira vez e inicia o serviço. */
    fun enable(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences("osone_config", 0).edit().putBoolean(PREF, true).apply()
        on = true
        if (modelReady(app)) { start(app); return }
        if (downloading) return
        downloading = true
        status = "Baixando o reconhecedor de voz (cerca de 30 MB, só uma vez)…"
        Thread({
            val error = try { download(app); null } catch (failure: Exception) {
                AppDiagnostics.get(app).record("Escuta ativa", "Download do modelo falhou: ${failure.message?.take(100)}")
                failure.message ?: failure.javaClass.simpleName
            }
            main.post {
                downloading = false
                if (error != null) {
                    status = "Não consegui baixar o reconhecedor ($error). Tente de novo com internet."
                    app.getSharedPreferences("osone_config", 0).edit().putBoolean(PREF, false).apply()
                    on = false
                } else if (enabled(app)) start(app)
            }
        }, "ostie-wake-download").start()
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences("osone_config", 0).edit().putBoolean(PREF, false).apply()
        on = false
        app.stopService(Intent(app, WakeWordService::class.java))
        status = null
    }

    /** Ao abrir o app (ex.: depois de reiniciar o celular), volta a escutar se estava ligada. */
    fun resume(context: Context) {
        if (enabled(context) && modelReady(context) && canListen(context)) start(context.applicationContext)
    }

    private fun start(context: Context) {
        if (!canListen(context)) { status = "Permita o microfone para usar a escuta ativa."; return }
        try {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            status = null
        } catch (failure: Exception) {
            status = "O Android não deixou iniciar a escuta agora. Abra o app e tente de novo."
            AppDiagnostics.get(context).record("Escuta ativa", "Serviço não iniciou (${failure.javaClass.simpleName}).")
        }
    }

    /** Baixa e extrai o modelo, sem a pasta raiz do zip, com proteção contra caminhos fora da pasta. */
    private fun download(context: Context) {
        val zip = File(context.cacheDir, "vosk-pt.zip")
        val connection = UpdateFeed.openHttps(MODEL_URL)
        try {
            connection.inputStream.use { input -> zip.outputStream().use { input.copyTo(it) } }
        } finally { connection.disconnect() }
        val target = modelDir(context)
        val staging = File(context.filesDir, "vosk-pt.tmp").apply { deleteRecursively(); mkdirs() }
        val root = staging.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val file = File(staging, relative)
                require(file.canonicalPath.startsWith(root)) { "Arquivo inválido no modelo." }
                if (entry.isDirectory) file.mkdirs()
                else { file.parentFile?.mkdirs(); file.outputStream().use { entries.copyTo(it) } }
            }
        }
        zip.delete()
        require(File(staging, "am/final.mdl").isFile) { "Modelo incompleto." }
        target.deleteRecursively()
        require(staging.renameTo(target)) { "Não consegui salvar o modelo." }
    }
}

/** Serviço em primeiro plano que ouve "Ei, Ostie"; pausa o microfone enquanto o Live está aberto. */
class WakeWordService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var lastWake = 0L
    private val main = Handler(Looper.getMainLooper())
    /** Criado na thread principal (em onStartCommand), lido pela thread de escuta. */
    private lateinit var live: LiveVoiceViewModel

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { WakeWord.disable(this); stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification())
        } catch (failure: Exception) {
            // Android 14+: microfone em primeiro plano só pode começar com o app aberto.
            AppDiagnostics.get(this).record("Escuta ativa", "Pausada até abrir o app (${failure.javaClass.simpleName}).")
            stopSelf()
            return START_NOT_STICKY
        }
        live = LiveSession.get(application)
        if (worker?.isAlive != true) {
            running = true
            worker = Thread(::listen, "ostie-wake").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun listen() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val model = try { org.vosk.Model(WakeWord.modelDir(this).absolutePath) } catch (failure: Throwable) {
            AppDiagnostics.get(this).record("Escuta ativa", "Modelo de voz não carregou (${failure.javaClass.simpleName}).")
            main.post { WakeWord.status = "O reconhecedor não carregou. Desligue e ligue a escuta ativa de novo."; stopSelf() }
            return
        }
        var recognizer: org.vosk.Recognizer? = null
        var recorder: AudioRecord? = null
        val buffer = ByteArray(3200) // 100 ms a 16 kHz, 16 bits.
        try {
            recognizer = org.vosk.Recognizer(model, 16000f, WakePhrase.grammar).apply { setWords(true) }
            while (running) {
                // O Live usa o microfone: a escuta solta o dela e espera a conversa acabar.
                if (live.active != null) {
                    recorder?.let { it.stop(); it.release() }
                    recorder = null
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    continue
                }
                if (recorder == null) {
                    recorder = openRecorder()
                    if (recorder == null) {
                        try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                        continue
                    }
                    recognizer.reset()
                }
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) { recorder.release(); recorder = null; continue }
                if (recognizer.acceptWaveForm(buffer, count) && WakePhrase.matches(recognizer.result)) wake()
            }
        } catch (failure: Throwable) {
            if (running) AppDiagnostics.get(this).record("Escuta ativa", "Parou (${failure.javaClass.simpleName}).")
        } finally {
            recorder?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
            recognizer?.close()
            model.close()
        }
    }

    private fun openRecorder(): AudioRecord? = try {
        val size = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, size).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?.apply { startRecording() }
    } catch (_: Exception) { null }

    /** Abre o Live por cima do que estiver na tela (com "Mostrar sobre outros apps"), ou avisa por notificação. */
    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 5000) return
        lastWake = now
        main.post {
            val open = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LIVE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val started = Settings.canDrawOverlays(this) && try { startActivity(open); true } catch (_: Exception) { false }
            if (!started) callNotification(open)
        }
    }

    private fun callNotification(open: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CALL_CHANNEL, "Chamado do OSTIE",
            NotificationManager.IMPORTANCE_HIGH).apply { description = "Aviso quando você diz \"Ei, Ostie\"" })
        val tap = PendingIntent.getActivity(this, 31, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(CALL_NOTIFICATION, Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_ostie_notification)
            .setContentTitle("Ouvi você")
            .setContentText("Toque para falar com o OSTIE.")
            .setContentIntent(tap).setFullScreenIntent(tap, true)
            .setAutoCancel(true).setTimeoutAfter(30_000).build())
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Escuta ativa", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Ouvindo \"Ei, Ostie\" no aparelho, sem enviar áudio"
            })
        val off = PendingIntent.getService(this, 32, Intent(this, WakeWordService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 33, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LIVE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ostie_notification)
            .setContentTitle("Diga \"Ei, Ostie\"")
            .setContentText("Escuta ativa no aparelho; nenhum áudio sai do celular.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Desligar", off).build())
            .build()
    }

    companion object {
        const val STOP = "com.osone.app.wake.STOP"
        private const val CHANNEL = "ostie_wake"
        private const val CALL_CHANNEL = "ostie_wake_call"
        private const val NOTIFICATION = 41
        private const val CALL_NOTIFICATION = 42
    }
}
