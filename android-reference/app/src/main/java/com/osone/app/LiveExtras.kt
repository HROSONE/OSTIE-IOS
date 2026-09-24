package com.osone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pesquisa na web para o Live quando o modelo de voz não pode usar a Pesquisa Google embutida
 * (ex.: Live 3.x no nível gratuito). O modelo de texto Gemini pesquisa e devolve um resumo com fontes.
 */
object WebSearch {
    const val NAME = "web_search"
    private const val SYSTEM = "Você pesquisa na web para um assistente de voz. Responda em português do Brasil, " +
        "em no máximo 6 frases objetivas, com números, datas e nomes exatos encontrados. Se não encontrar, diga isso."

    fun declaration(): JSONObject = JSONObject()
        .put("name", NAME)
        .put("description", "Pesquise na web (Google) informações atuais ou que você não sabe com certeza: notícias, " +
            "preços, placares, clima, horários, lançamentos, fatos recentes. Retorna um resumo com as fontes.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("consulta", JSONObject().put("type", "STRING")
                .put("description", "O que pesquisar, com contexto (lugar, data, nomes)")))
            .put("required", JSONArray().put("consulta")))

    /** Roda fora da thread principal; [respond] recebe o resultado da ferramenta. */
    fun run(context: Context, args: JSONObject, respond: (JSONObject) -> Unit) {
        val query = args.optString("consulta").trim().take(500)
        if (query.isEmpty()) { respond(JSONObject().put("erro", "Informe o que pesquisar.")); return }
        Thread({
            val result = try {
                val key = SecureKeyStore(context).read() ?: error("Chave Gemini não configurada.")
                val preferences = context.getSharedPreferences("osone_config", 0)
                val choices = ChatModel.candidates(ChatModel.fromId(preferences.getString("model", null)), true)
                var answer: String? = null
                var lastError: Exception? = null
                for (choice in choices) {
                    try {
                        answer = GeminiClient().streamAnswer(key, choice, listOf(ChatMessage("user", "Pesquise na web: $query")),
                            ThinkingMode.FAST, null, SYSTEM, 60_000, googleSearch = true) { }
                        break
                    } catch (failure: GeminiHttpException) {
                        // 400 = modelo sem pesquisa; 404/429/5xx = indisponível ou sem cota: tenta o próximo.
                        lastError = failure
                        if (!failure.allowsFallback && failure.status != 400) throw failure
                    }
                }
                JSONObject().put("resultado", (answer ?: throw (lastError ?: IllegalStateException("sem resposta"))).take(3_000))
            } catch (failure: Exception) {
                AppDiagnostics.get(context).record("Pesquisa (Live)", failure.message?.take(120) ?: failure.javaClass.simpleName)
                JSONObject().put("erro", "Pesquisa indisponível agora. Diga ao usuário que não conseguiu pesquisar.")
            }
            respond(result)
        }, "ostie-web-search").start()
    }
}

/**
 * Conversa de voz transcrita que vai para o histórico do chat escrito.
 * O Live grava aqui; o chat consome quando a revisão muda (tela aberta) ou ao voltar ao app.
 */
object LiveTranscriptInbox {
    private val main = Handler(Looper.getMainLooper())
    var revision by mutableIntStateOf(0)
        private set

    fun push(context: Context, user: String, model: String) {
        if (user.isBlank() && model.isBlank()) return
        val preferences = context.getSharedPreferences("osone_live_transcript", 0)
        synchronized(this) {
            val list = try { JSONArray(preferences.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
            list.put(JSONObject().put("user", user.trim().take(4_000)).put("model", model.trim().take(8_000)))
            while (list.length() > 60) list.remove(0)
            preferences.edit().putString("pending", list.toString()).apply()
        }
        main.post { revision++ }
    }

    fun drain(context: Context): List<Pair<String, String>> = synchronized(this) {
        val preferences = context.getSharedPreferences("osone_live_transcript", 0)
        val list = try { JSONArray(preferences.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
        preferences.edit().remove("pending").apply()
        (0 until list.length()).map { list.getJSONObject(it).let { item -> item.optString("user") to item.optString("model") } }
    }
}
