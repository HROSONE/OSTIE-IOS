package com.osone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView

/** A chamada pertence ao serviço: sair da Activity não libera o microfone. */
class LiveSessionService : Service() {
    private val live by lazy { LiveSession.get(application) }
    private val diagnostics by lazy { AppDiagnostics.get(this) }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var bubble: View? = null
    private var projection: ScreenCaptureController? = null
    private var camera: LiveCameraController? = null
    private var foreground = false
    private var overlayExpanded = false
    private val monitor = Handler(Looper.getMainLooper())
    private val monitorSession = object : Runnable {
        override fun run() {
            if (!foreground) return
            if (live.active == null) stopSelf() else monitor.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Conversa Live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Conversa de voz e compartilhamento de tela em andamento"
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START && intent?.action != STOP && !foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            when (intent?.action) {
                START -> {
                    showForeground(false)
                    if (live.active == null) live.start()
                    if (Settings.canDrawOverlays(this)) showBubble()
                    monitor.removeCallbacks(monitorSession)
                    monitor.postDelayed(monitorSession, 3000)
                }
                STOP -> { stopSelf(); return START_NOT_STICKY }
                MUTE -> if (live.active != null) live.toggleMute()
                OVERLAY_ON -> if (foreground && Settings.canDrawOverlays(this)) showBubble()
                OVERLAY_OFF -> removeBubble()
                CAMERA_START -> startCamera(false)
                CAMERA_SWITCH -> startCamera(!live.cameraFront)
                CAMERA_STOP -> stopCamera()
                SCREEN_START -> {
                    val data = readScreenResult(intent)
                    if (data != null && intent.getIntExtra(SCREEN_RESULT, 0) == android.app.Activity.RESULT_OK && foreground) {
                        stopCamera()
                        stopProjection()
                        showForeground(true)
                        // O primeiro quadro pode chegar antes de start() retornar.
                        live.screenSharing = true
                        projection = ScreenCaptureController(this,
                            onFrame = live::sendScreenFrame,
                            onCapture = live::screenFrameCaptured,
                            onEnd = { projection = null; live.screenSharing = false; if (foreground) showForeground(false) })
                        projection?.start(data)
                    }
                }
                SCREEN_STOP -> stopProjection()
            }
        } catch (failure: Exception) {
            diagnostics.record("Serviço Live", "${failure.javaClass.simpleName} em ${intent?.action?.substringAfterLast('.') ?: "comando"}.")
            if (intent?.action == SCREEN_START) stopProjection()
            if (intent?.action == CAMERA_START || intent?.action == CAMERA_SWITCH) stopCamera()
            if (!foreground) stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readScreenResult(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra(SCREEN_DATA, Intent::class.java)
    else intent.getParcelableExtra(SCREEN_DATA)

    private fun showForeground(sharing: Boolean, showingCamera: Boolean = false) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            (if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0) or
            (if (showingCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(sharing, showingCamera), type)
        else startForeground(NOTIFICATION, notification(sharing, showingCamera))
        foreground = true
    }

    private fun notification(sharing: Boolean, showingCamera: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 2, Intent(this, LiveSessionService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val mute = PendingIntent.getService(this, 3, Intent(this, LiveSessionService::class.java).setAction(MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ostie_notification)
            .setContentTitle(if (showingCamera) "OSTIE · voz e câmera ativas"
                else if (sharing) "OSTIE · voz e tela ativas" else "OSTIE · voz ativa")
            .setContentText("Toque para voltar à conversa. Encerrar desliga o microfone.")
            .setColor(0xFF2F6BFF.toInt())
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Silenciar / ativar", mute).build())
            .addAction(Notification.Action.Builder(null, "Encerrar", close).build()).build()
    }

    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        overlayExpanded = false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val orb = ImageView(this).apply {
            setImageResource(R.drawable.ostie_orb)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "OSTIE: toque para mostrar ações; arraste para mover"
        }
        root.addView(orb, LinearLayout.LayoutParams(dp(56), dp(56)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 27, 42)); cornerRadius = dp(18).toFloat()
            }
        }
        fun action(title: String, command: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = title; textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener { command() }
            })
        }
        action("Voltar ao OSTIE") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            overlayExpanded = false; actions.visibility = View.GONE; updateBubbleSize(root)
        }
        action("Silenciar / ativar") { live.toggleMute() }
        action("Encerrar conversa") { stopSelf() }
        root.addView(actions)
        val params = WindowManager.LayoutParams(dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(180) }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        orb.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (originX + event.rawX - downX).toInt().coerceAtLeast(0)
                    params.y = (originY + event.rawY - downY).toInt().coerceAtLeast(0)
                    windows.updateViewLayout(root, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - downX) < dp(8) && kotlin.math.abs(event.rawY - downY) < dp(8)) {
                        overlayExpanded = !overlayExpanded
                        actions.visibility = if (overlayExpanded) View.VISIBLE else View.GONE
                        params.width = if (overlayExpanded) dp(190) else dp(56)
                        params.height = if (overlayExpanded) WindowManager.LayoutParams.WRAP_CONTENT else dp(56)
                        windows.updateViewLayout(root, params)
                    }
                    true
                }
                else -> false
            }
        }
        windows.addView(root, params)
        bubble = root
    }

    private fun updateBubbleSize(root: View) {
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        params.width = dp(56); params.height = dp(56); windows.updateViewLayout(root, params)
    }

    private fun removeBubble() {
        bubble?.let { try { windows.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun stopProjection() {
        val old = projection
        projection = null
        old?.stop()
        live.screenSharing = false
        if (foreground) showForeground(false)
    }

    private fun startCamera(front: Boolean) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            diagnostics.record("Câmera Live", "A permissão da câmera precisa ser concedida no app.")
            return
        }
        stopProjection()
        stopCamera()
        showForeground(false, true)
        live.cameraSharing = true
        live.cameraFront = front
        live.resetCameraCounters()
        lateinit var controller: LiveCameraController
        controller = LiveCameraController(this, front, onFrame = live::sendCameraFrame,
            onError = { detail -> monitor.post {
                if (camera === controller) {
                    diagnostics.record("Câmera Live", detail)
                    stopCamera()
                }
            } })
        camera = controller
        controller.start()
    }

    private fun stopCamera() {
        val old = camera
        camera = null
        old?.stop()
        live.cameraSharing = false
        live.clearCameraPreview()
        if (foreground) showForeground(projection != null)
    }

    override fun onDestroy() {
        foreground = false
        monitor.removeCallbacks(monitorSession)
        projection?.stop(); projection = null
        live.screenSharing = false
        stopCamera()
        removeBubble()
        live.stop()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "osone_live"
        private const val NOTIFICATION = 101
        const val START = "com.osone.app.START"
        const val STOP = "com.osone.app.STOP"
        const val MUTE = "com.osone.app.MUTE"
        const val OVERLAY_ON = "com.osone.app.OVERLAY_ON"
        const val OVERLAY_OFF = "com.osone.app.OVERLAY_OFF"
        const val SCREEN_START = "com.osone.app.SCREEN_START"
        const val SCREEN_STOP = "com.osone.app.SCREEN_STOP"
        const val CAMERA_START = "com.osone.app.CAMERA_START"
        const val CAMERA_STOP = "com.osone.app.CAMERA_STOP"
        const val CAMERA_SWITCH = "com.osone.app.CAMERA_SWITCH"
        const val SCREEN_DATA = "screen_data"
        const val SCREEN_RESULT = "screen_result"
        fun command(context: Context, action: String, configure: (Intent.() -> Unit)? = null) {
            val intent = Intent(context, LiveSessionService::class.java).setAction(action)
            configure?.invoke(intent)
            if (action == START) androidx.core.content.ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }
    }
}

/** Uma sessão compartilhada entre Activity e serviço, sem vínculo com a vida da tela. */
object LiveSession {
    @Volatile private var instance: LiveVoiceViewModel? = null
    fun get(context: Context): LiveVoiceViewModel = instance ?: synchronized(this) {
        instance ?: LiveVoiceViewModel(context.applicationContext as android.app.Application).also { instance = it }
    }
}
