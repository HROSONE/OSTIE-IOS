package com.osone.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Resultado da sessão do PackageInstaller: abre a confirmação quando o Android exigir. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val diagnostics = AppDiagnostics.get(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                try { confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity) }
                catch (_: Exception) { diagnostics.record("Atualização", "Não consegui abrir a confirmação de instalação. Toque em Atualizar novamente com o app aberto.") }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // O processo é substituído pela versão nova.
            PackageInstaller.STATUS_FAILURE_ABORTED -> diagnostics.record("Atualização", "Instalação cancelada.")
            else -> diagnostics.record("Atualização", "Instalação recusada pelo Android (código $status): " +
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120))
        }
    }
}

/** Verificação diária em segundo plano; só avisa, nunca instala sem o app aberto. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences("ostie_updates", 0)
        if (!preferences.getBoolean("auto_update", true)) return Result.success()
        val release = try { withContext(Dispatchers.IO) { UpdateFeed.fetch(UpdateFeed.address(applicationContext)) } }
            catch (_: Exception) { return Result.success() } // Sem rede ou canal vazio: tenta no próximo ciclo.
        @Suppress("DEPRECATION")
        val installed = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
        if (release.versionCode <= installed || preferences.getLong("notified_code", 0L) == release.versionCode)
            return Result.success()
        notify(applicationContext, release)
        preferences.edit().putLong("notified_code", release.versionCode).apply()
        return Result.success()
    }

    companion object {
        private const val WORK = "ostie_update_check"
        private const val CHANNEL = "ostie_updates"
        const val EXTRA_INSTALL = "ostie_install_update"

        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) { manager.cancelUniqueWork(WORK); return }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun notify(context: Context, release: OstieUpdate) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Atualizações do OSTIE",
                NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_INSTALL, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(202, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ostie_notification)
                .setContentTitle("OSTIE ${release.versionName} disponível")
                .setContentText(release.notes.ifBlank { "Toque para atualizar." })
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
