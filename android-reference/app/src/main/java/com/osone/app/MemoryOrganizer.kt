package com.osone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Regras puras da reorganização da memória (testáveis sem Android). */
object MemoryTidy {
    /** Abaixo disso não vale gastar uma chamada ao modelo. */
    const val MIN_NOTES = 12

    fun notes(text: String) = text.lines().count { it.startsWith("- ") }

    fun prompt(memory: String, today: String, sections: List<String>): String =
        "Hoje é $today. Reorganize o arquivo de memória abaixo, que um assistente pessoal mantém sobre o usuário.\n" +
        "Regras:\n" +
        "- Mantenha o formato: título \"# Memória do OSTIE\", a linha de citação \"> ...\", seções \"## \" e itens \"- \" com uma frase cada.\n" +
        "- Mantenha as seções existentes (${sections.joinToString()}), mova itens para a seção certa.\n" +
        "- Junte itens repetidos ou parecidos num só, com a data mais recente entre parênteses (dd/mm/aaaa).\n" +
        "- Se dois itens se contradizem, fique com o mais recente.\n" +
        "- Remova só o que claramente expirou (compromissos com data passada, avisos temporários já vencidos).\n" +
        "- Nunca invente informação nem apague preferências, pessoas, fatos ou projetos que continuam válidos.\n" +
        "Responda apenas com o arquivo completo, sem comentários e sem bloco de código.\n\n" + memory

    /** Resultado aceito (já limpo), ou nulo se parecer que o modelo perdeu ou estragou a memória. */
    fun accept(original: String, organized: String): String? {
        var text = DocumentPreview.stripFence(organized).trim().replace("\r\n", "\n")
        if (!text.contains("\n## ")) return null
        if (!text.startsWith("# ")) text = "# Memória do OSTIE\n\n$text"
        val before = notes(original)
        val after = notes(text)
        // Juntar repetições encolhe a lista, mas perder mais de 60% dos itens indica erro.
        if (after == 0 || after < before * 0.4) return null
        if (text.length < original.length * 0.3 || text.length > 60_000) return null
        // O nome preferido do usuário nunca pode sumir.
        Regex("Prefere ser chamado de ([^\\n(]+)").find(original)?.groupValues?.get(1)?.trim()?.let { name ->
            if (name.isNotEmpty() && !text.contains(name)) return null
        }
        return text + "\n"
    }
}

/** Reorganiza a memória no máximo uma vez por semana (ou quando o usuário pede), com cópia de segurança. */
class MemoryOrganizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val preferences = context.getSharedPreferences(MemoryOrganizer.PREFS, 0)
        val manual = inputData.getBoolean("manual", false)
        if (!manual && !preferences.getBoolean("auto", true)) return Result.success()
        val memory = MemoryStore.get(context)
        val original = withContext(Dispatchers.Main) { memory.refresh(); memory.text }
        if (MemoryTidy.notes(original) < MemoryTidy.MIN_NOTES) {
            if (manual) MemoryOrganizer.report(context, "Ainda há poucas anotações; nada a organizar.")
            return Result.success()
        }
        if (!manual && preferences.getInt("organized_hash", 0) == original.hashCode()) return Result.success()
        return try {
            val today = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date())
            val answer = withContext(Dispatchers.IO) {
                TextModel.ask(context, MemoryTidy.prompt(original, today, MemoryStore.SECTIONS),
                    "Você organiza anotações com cuidado, sem perder informação.", readTimeoutMs = 120_000)
            }
            val organized = MemoryTidy.accept(original, answer)
            if (organized == null) {
                MemoryOrganizer.report(context, "O modelo devolveu uma versão incompleta; a memória ficou como estava.")
                return Result.success()
            }
            val saved = withContext(Dispatchers.Main) {
                // Se o OSTIE anotou algo enquanto o modelo trabalhava, tenta de novo depois.
                if (memory.text != original) false
                else { memory.backup(original); memory.save(organized); true }
            }
            if (!saved) return Result.retry()
            preferences.edit().putInt("organized_hash", organized.hashCode()).apply()
            MemoryOrganizer.report(context, "Organizada: ${MemoryTidy.notes(original)} → ${MemoryTidy.notes(organized)} anotações. " +
                "Cópia anterior em ${MemoryStore.BACKUP}.")
            Result.success()
        } catch (failure: Exception) {
            if (!manual && runAttemptCount < 2) return Result.retry()
            MemoryOrganizer.report(context, "Não consegui organizar agora (${failure.message?.take(80) ?: failure.javaClass.simpleName}).")
            Result.success()
        }
    }
}

object MemoryOrganizer {
    const val PREFS = "ostie_memory_organizer"
    private const val WORK = "ostie_memory_organize"
    private val main = Handler(Looper.getMainLooper())

    /** Último resultado, mostrado em Ajustes > Memória. */
    var status by mutableStateOf<String?>(null)
        private set

    fun load(context: Context) {
        if (status == null) status = context.getSharedPreferences(PREFS, 0).getString("status", null)
    }

    fun autoEnabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("auto", true)

    fun schedule(context: Context, enabled: Boolean = autoEnabled(context)) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("auto", enabled).apply()
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(WORK); return }
        val request = PeriodicWorkRequestBuilder<MemoryOrganizeWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true).build())
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        status = "Organizando…"
        val request = OneTimeWorkRequestBuilder<MemoryOrganizeWorker>().setInputData(workDataOf("manual" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("${WORK}_now", ExistingWorkPolicy.REPLACE, request)
    }

    fun report(context: Context, message: String) {
        val stamped = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date()) + " · " + message
        context.getSharedPreferences(PREFS, 0).edit().putString("status", stamped).apply()
        main.post { status = stamped }
    }
}
