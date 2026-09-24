package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ChatProviderHttpException(val provider: ChatProvider, val status: Int) :
    Exception("${provider.label} respondeu HTTP $status. Confira a chave, o modelo e a cota.")

/** Chat Completions compatível com OpenAI, com streaming SSE para OpenRouter e Groq. */
class ChatCompletionClient {
    fun streamAnswer(provider: ChatProvider, key: String, model: String, history: List<ChatMessage>,
        systemPrompt: String = DEFAULT_SYSTEM, readTimeoutMs: Int = 45_000,
        onPartial: (String) -> Unit): String {
        require(provider != ChatProvider.GEMINI)
        val endpoint = when (provider) {
            ChatProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
            ChatProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
            ChatProvider.GEMINI -> error("Use GeminiClient para Gemini")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(12).forEach { message ->
            messages.put(JSONObject().put("role", if (message.role == "model") "assistant" else "user")
                .put("content", message.text))
        }
        val payload = JSONObject().put("model", model).put("stream", true).put("messages", messages)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw ChatProviderHttpException(provider, status)
            val answer = StringBuilder()
            fun process(data: String) {
                if (data.isBlank() || data == "[DONE]") return
                val message = JSONObject(data)
                if (message.has("error")) throw IllegalStateException("${provider.label} interrompeu a resposta. Tente outro modelo.")
                val chunk = message.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.opt("content") as? String ?: ""
                if (chunk.isNotEmpty()) { answer.append(chunk); onPartial(answer.toString()) }
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val event = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        process(event.toString()); event.setLength(0)
                    } else if (line.startsWith("data:")) event.append(line.substring(5).trimStart())
                }
                process(event.toString())
            }
            answer.toString().trim().ifEmpty { throw IllegalStateException("${provider.label} não enviou texto. Confira o modelo escolhido.") }
        } finally { connection.disconnect() }
    }

    companion object {
        const val DEFAULT_SYSTEM = "Você é OSTIE, assistente pessoal do usuário. Responda no idioma do usuário com clareza, precisão e passos práticos quando relevantes. Considere o contexto anterior. Não invente ações externas."
    }
}
