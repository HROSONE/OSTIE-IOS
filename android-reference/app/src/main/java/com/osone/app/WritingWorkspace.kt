package com.osone.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Um documento compartilhado entre a tela de escrita e o serviço Live, salvo apenas no aparelho. */
class WritingWorkspace private constructor(context: Context) {
    companion object {
        @Volatile private var instance: WritingWorkspace? = null
        fun get(context: Context): WritingWorkspace = instance ?: synchronized(this) {
            instance ?: WritingWorkspace(context.applicationContext).also { instance = it }
        }
    }

    private val storage = context.getSharedPreferences("ostie_writing", Context.MODE_PRIVATE)
    var title by mutableStateOf(storage.getString("title", "Novo documento").orEmpty())
        private set
    var content by mutableStateOf(storage.getString("content", "").orEmpty())
        private set
    var format by mutableStateOf(storage.getString("format", "text").orEmpty())
        private set
    /** Aumenta a cada documento recebido do OSTIE; a tela abre o preview de HTML/SVG novo. */
    var revision by mutableIntStateOf(0)
        private set
    /** HTML/SVG recebido que ainda não foi exibido, mesmo se a aba estava fechada. */
    var previewPending by mutableStateOf(false)
        private set

    fun consumePreview() { previewPending = false }

    fun updateContent(value: String) {
        content = value
        storage.edit().putString("content", value).apply()
    }

    fun updateFormat(value: String) {
        format = if (value.equals("html", true)) "html" else "text"
        storage.edit().putString("format", format).apply()
    }

    fun publish(args: JSONObject): JSONObject {
        val value = DocumentPreview.stripFence(args.optString("conteudo"))
        if (value.isBlank()) return JSONObject().put("erro", "Conteúdo vazio; não foi alterado.")
        if (value.length > 160_000) return JSONObject().put("erro", "Texto grande demais para a aba de escrita.")
        val proposedTitle = args.optString("titulo").trim().take(80).ifEmpty { "Novo documento" }
        // Aceita html, svg, xml… e também reconhece marcação quando o modelo informa outro formato.
        val proposedFormat = DocumentPreview.detectFormat(args.optString("formato"), value)
        val append = args.optString("operacao").equals("adicionar", true)
        val newValue = if (append && content.isNotBlank()) "$content\n\n$value" else value
        if (newValue.length > 160_000) return JSONObject().put("erro", "Documento excede o limite local.")
        title = proposedTitle
        content = newValue
        format = proposedFormat
        storage.edit().putString("title", title).putString("content", content)
            .putString("format", format).apply()
        previewPending = format == "html"
        revision++
        return JSONObject().put("resultado", "Documento salvo na Aba de Escrita")
            .put("titulo", title).put("formato", format).put("caracteres", content.length)
    }

    fun clear() {
        previewPending = false
        title = "Novo documento"
        content = ""
        format = "text"
        storage.edit().clear().apply()
    }
}
