package com.osone.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: OsoneViewModel by viewModels()
    private val live by lazy { LiveSession.get(application) }
    private val writing by lazy { WritingWorkspace.get(application) }
    private val codeAuthor by lazy { CodeAuthor.get(application) }
    private val memory by lazy { MemoryStore.get(application) }
    private var notificationsAccess by mutableStateOf(false)
    private var contactsGranted by mutableStateOf(false)
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsGranted = granted
    }
    private var calendarGranted by mutableStateOf(false)
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarGranted = granted
    }
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        memory.refresh()
    }
    private val updater by lazy { AppUpdater(this) }
    private var speech: TextToSpeech? = null
    private val chatVoice by lazy { ChatVoice.get(application) }
    private var showLive by mutableStateOf(false)
    private var showWriting by mutableStateOf(false)
    private var showRoutines by mutableStateOf(false)
    private val routines by lazy { RoutineStore.get(application) }
    private var permissionError by mutableStateOf(false)
    private var darkMode by mutableStateOf(false)
    private var bubblePermission by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayRequested = false
    /** Toque na notificação de atualização: abre Ajustes e instala. */
    private var installRequested by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val diagnostics by lazy { AppDiagnostics.get(applicationContext) }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openLive() else { permissionError = true; diagnostics.record("Permissão", "Acesso ao microfone negado.") }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && live.active != null) LiveSessionService.command(this, LiveSessionService.CAMERA_START)
        else if (!granted) diagnostics.record("Permissão", "Acesso à câmera negado.")
    }
    private val screenPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null)
            LiveSessionService.command(this, LiveSessionService.SCREEN_START) {
                putExtra(LiveSessionService.SCREEN_RESULT, result.resultCode)
                putExtra(LiveSessionService.SCREEN_DATA, result.data)
            }
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(uri)
    }
    private val pickUpdateApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { updater.chooseApk(uri) }
    }

    private fun requestLive() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            openLive()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private val wakeMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) WakeWord.enable(this) else WakeWord.status = "Sem o microfone, a escuta ativa não funciona."
    }

    /** Chave "Ouvir Ei, Ostie" em Ajustes: pede o microfone antes de ligar. */
    private fun setWakeWord(enabled: Boolean) {
        when {
            !enabled -> WakeWord.disable(this)
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                WakeWord.enable(this)
            else -> wakeMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Pasta de memória: "Acesso a todos os arquivos" no Android 11+, permissão comum antes disso. */
    private fun requestMemoryFolder() {
        val intent = memory.accessIntent()
        if (intent != null) try { startActivity(intent) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        else legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** Atalhos, bloco dos Ajustes rápidos, assistente e "Compartilhar com OSTIE". */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_LIVE, Intent.ACTION_ASSIST -> { showWriting = false; requestLive() }
            ACTION_WRITING -> { showLive = false; showRoutines = false; showWriting = true }
            Intent.ACTION_SEND -> {
                showLive = false; showWriting = false; showRoutines = false
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::receiveShared)
                @Suppress("DEPRECATION")
                val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_STREAM)
                stream?.let(viewModel::attach)
            }
        }
    }

    private fun openLive() {
        permissionError = false
        chatVoice.stop(); speech?.stop() // O Live fala por conta própria.
        showLive = true
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (live.active == null) LiveSessionService.command(this, LiveSessionService.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics.installCrashHandler()
        showLive = live.active != null
        bubblePermission = Settings.canDrawOverlays(this)
        accessibilityEnabled = OsoneAccessibilityService.active != null
        volumeControlStream = AudioManager.STREAM_MUSIC
        val preferences = getSharedPreferences("osone_config", 0)
        // Sem escolha salva, segue o tema do sistema.
        darkMode = if (preferences.contains("dark_mode")) preferences.getBoolean("dark_mode", false)
            else (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applySystemBars()
        speech = TextToSpeech(this, this)
        installRequested = intent?.getBooleanExtra(UpdateCheckWorker.EXTRA_INSTALL, false) == true
        UpdateCheckWorker.schedule(this, updater.autoUpdate)
        WakeWord.load(this)
        MemoryOrganizer.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean("asked_notifications", false)) {
            preferences.edit().putBoolean("asked_notifications", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!installRequested) lifecycleScope.launch { updater.checkOnLaunch() }
        if (savedInstanceState == null) handleIntent(intent)
        RoutineScheduler.scheduleAll(this)
        setContent {
            var showSettings by remember { mutableStateOf(false) }
            var readAloud by remember { mutableStateOf(false) }
            var showDiagnostics by remember { mutableStateOf(false) }
            // Código pedido por voz aparece sendo escrito na Aba de Escrita, mesmo sem perguntar.
            LaunchedEffect(codeAuthor.writingWith) {
                if (codeAuthor.writingWith != null) { showWriting = true; showLive = false; showSettings = false; showRoutines = false }
            }
            LaunchedEffect(LiveTranscriptInbox.revision) { viewModel.collectLiveTranscript() }
            LaunchedEffect(installRequested) {
                if (installRequested) {
                    installRequested = false
                    showSettings = true; showLive = false; showWriting = false
                    updater.checkAndInstall()
                }
            }
            OstieTheme(darkMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (showLive) LiveScreen(live, codeAuthor, diagnostics, bubblePermission, accessibilityEnabled,
                        phone = PhoneAccess(notificationsAccess, contactsGranted, memory.persistent, calendarGranted,
                            onNotifications = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            onContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                            onMemory = ::requestMemoryFolder,
                            onCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }),
                        onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onWriting = { showWriting = true; showLive = false },
                        onOverlay = {
                            if (Settings.canDrawOverlays(this)) LiveSessionService.command(this, LiveSessionService.OVERLAY_ON)
                            else {
                                overlayRequested = true
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            }
                        },
                        onShareScreen = {
                            val manager = getSystemService(MediaProjectionManager::class.java)
                            screenPermission.launch(manager.createScreenCaptureIntent())
                        },
                        onCameraToggle = {
                            if (live.cameraSharing) LiveSessionService.command(this, LiveSessionService.CAMERA_STOP)
                            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                                LiveSessionService.command(this, LiveSessionService.CAMERA_START)
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        onCameraSwitch = { LiveSessionService.command(this, LiveSessionService.CAMERA_SWITCH) },
                        onStopScreen = { LiveSessionService.command(this, LiveSessionService.SCREEN_STOP) },
                        onEnd = { LiveSessionService.command(this, LiveSessionService.STOP); showLive = false;
                            volumeControlStream = AudioManager.STREAM_MUSIC },
                        onDiagnostics = { showDiagnostics = true }, onBack = { showLive = false;
                            volumeControlStream = AudioManager.STREAM_MUSIC })
                    else if (showSettings) SettingsScreen(viewModel, live, codeAuthor, updater, memory, darkMode,
                        onMemoryFolder = ::requestMemoryFolder,
                        onDarkMode = { enabled ->
                            darkMode = enabled
                            applySystemBars()
                            getSharedPreferences("osone_config", 0).edit().putBoolean("dark_mode", enabled).apply()
                        }, onBack = { showSettings = false },
                        onPickUpdate = { pickUpdateApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        diagnostics = diagnostics, onDiagnostics = { showDiagnostics = true },
                        onWakeWord = ::setWakeWord, overlayAllowed = bubblePermission,
                        onOverlay = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
                    else if (showRoutines) RoutinesScreen(routines, diagnostics,
                        onDiagnostics = { showDiagnostics = true }, onBack = { showRoutines = false },
                        onRunNow = { RoutineScheduler.runNow(this, it) },
                        onNeedNotifications = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }, calendarAccess = calendarGranted,
                        onCalendar = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) })
                    else if (showWriting) WritingScreen(writing, live, codeAuthor, diagnostics,
                        onDiagnostics = { showDiagnostics = true },
                        onBack = { showWriting = false },
                        onLive = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        })
                    else ChatScreen(viewModel, onAttach = { pickFile.launch(arrayOf("*/*")) }, onMic = {
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                openLive()
                            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, permissionError = permissionError, onSettings = { showSettings = true },
                        onWriting = { showWriting = true }, onRoutines = { showRoutines = true },
                        diagnostics = diagnostics, liveActive = live.active != null,
                        onOpenCode = { code, language ->
                            writing.publish(JSONObject().put("titulo", "Código do chat")
                                .put("conteudo", code).put("formato", language))
                            showWriting = true
                        },
                        onDiagnostics = { showDiagnostics = true }, readAloud = readAloud,
                        onReadAloud = {
                            readAloud = !readAloud
                            if (!readAloud) { chatVoice.stop(); speech?.stop() }
                        }, onAnswer = { answer ->
                            // Voz do Gemini TTS; a do Android lê quando ela está escolhida ou quando o Gemini falha.
                            if (readAloud) chatVoice.speak(answer) { text ->
                                speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "osone_resposta")
                            }
                        })
                    if (showDiagnostics) DiagnosticsDialog(diagnostics, onClose = { showDiagnostics = false })
                    updater.prompt?.let { release ->
                        UpdatePromptDialog(release, onUpdate = {
                            showSettings = true; showLive = false; showWriting = false
                            lifecycleScope.launch { updater.checkAndInstall() }
                        }, onLater = updater::dismissPrompt)
                    }
                    ConfirmGate.pending?.let { item ->
                        AlertDialog(onDismissRequest = ConfirmGate::cancel,
                            title = { Text(item.title) }, text = { Text(item.detail) },
                            confirmButton = { Button(onClick = ConfirmGate::confirm) { Text(item.confirmLabel) } },
                            dismissButton = { TextButton(onClick = ConfirmGate::cancel) { Text("Cancelar") } })
                    }
                    codeAuthor.pending?.let { request ->
                        CodeAuthorDialog(request, voiceLabel = if (live.connected) live.active?.label else null,
                            textLabel = codeAuthor.textModelLabel(),
                            onChoose = { useText, remember ->
                                codeAuthor.choose(useText, remember)
                                // O resultado aparece na Aba de Escrita; a voz continua ativa.
                                showWriting = true; showLive = false; showSettings = false; showRoutines = false
                            }, onDismiss = codeAuthor::dismiss)
                    }
                }
            }
        }
    }

    /** Ícones da barra de status acompanham o modo escolhido no app, não só o do sistema. */
    private fun applySystemBars() {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) speech?.language = Locale("pt", "BR")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(UpdateCheckWorker.EXTRA_INSTALL, false)) installRequested = true
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        bubblePermission = Settings.canDrawOverlays(this)
        accessibilityEnabled = OsoneAccessibilityService.active != null
        notificationsAccess = OstieNotificationListener.enabled(this)
        contactsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        calendarGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        memory.refresh() // Volta das Configurações com a permissão da pasta, ou arquivo editado fora do app.
        RoutineStore.get(this).restoreFromFolder()
        WakeWord.resume(this) // Volta a escutar depois de reiniciar o celular ou atualizar o app.
        viewModel.collectRoutineResults()
        viewModel.collectLiveTranscript()
        updater.resumeAfterPermission()
        if (overlayRequested && bubblePermission && live.active != null)
            LiveSessionService.command(this, LiveSessionService.OVERLAY_ON)
        overlayRequested = false
    }
    companion object {
        const val ACTION_LIVE = "com.osone.app.action.LIVE"
        const val ACTION_WRITING = "com.osone.app.action.WRITING"
    }

    override fun onDestroy() { chatVoice.stop(); speech?.stop(); speech?.shutdown(); super.onDestroy() }
}
