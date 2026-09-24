package com.osone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticEvent(val timestamp: Long, val area: String, val detail: String)

/** Registra falhas observadas pelo app, sem áudio, mensagens da conversa ou valores de chaves. */
class AppDiagnostics private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("osone_diagnostics", 0)
    private val main = Handler(Looper.getMainLooper())
    var events by mutableStateOf(readSaved())
        private set
    var unread by mutableIntStateOf(preferences.getInt("unread", 0))
        private set
    private var crashHandlerInstalled = false

    fun record(area: String, detail: String) {
        val event = DiagnosticEvent(System.currentTimeMillis(), clean(area, 36), clean(detail, 180))
        if (Looper.myLooper() == Looper.getMainLooper()) append(event)
        else main.post { append(event) }
    }

    fun markRead() {
        unread = 0
        preferences.edit().putInt("unread", 0).apply()
    }

    fun clear() {
        events = emptyList()
        unread = 0
        preferences.edit().remove("events").putInt("unread", 0).apply()
    }

    private fun append(event: DiagnosticEvent) {
        events = (events + event).takeLast(60)
        unread++
        preferences.edit().putString("events", serialize(events)).putInt("unread", unread).apply()
    }

    /** Erros fatais são salvos antes de o Android encerrar o processo. */
    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                val frame = failure.stackTrace.firstOrNull { it.className.startsWith("com.osone.app.") }
                val location = frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "execução"
                val event = DiagnosticEvent(System.currentTimeMillis(), "Falha fatal",
                    clean("${failure.javaClass.simpleName} em $location", 180))
                synchronized(this) {
                    val updated = (readSaved() + event).takeLast(60)
                    preferences.edit().putString("events", serialize(updated))
                        .putInt("unread", preferences.getInt("unread", 0) + 1).commit()
                }
            } catch (_: Exception) { /* Preserva o tratamento normal do Android. */ }
            previous?.uncaughtException(thread, failure)
        }
    }

    private fun readSaved(): List<DiagnosticEvent> = try {
        val json = JSONArray(preferences.getString("events", "[]"))
        (0 until json.length()).mapNotNull { index ->
            json.optJSONObject(index)?.let { DiagnosticEvent(it.optLong("at"), it.optString("area"), it.optString("detail")) }
        }
    } catch (_: Exception) { emptyList() }

    private fun serialize(items: List<DiagnosticEvent>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("at", it.timestamp).put("area", it.area).put("detail", it.detail)) }
    }.toString()

    private fun clean(value: String, limit: Int) = value
        .replace(Regex("AIza[0-9A-Za-z_-]{16,}|gsk_[0-9A-Za-z_-]{12,}|sk-or-[0-9A-Za-z_-]{12,}"), "[chave oculta]")
        .replace(Regex("key=[^&\\s]+", RegexOption.IGNORE_CASE), "key=[oculta]")
        .take(limit)

    companion object {
        @Volatile private var instance: AppDiagnostics? = null
        fun get(context: Context): AppDiagnostics = instance ?: synchronized(this) {
            instance ?: AppDiagnostics(context).also { instance = it }
        }
    }
}
