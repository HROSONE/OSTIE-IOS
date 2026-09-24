package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiHttpException(val status: Int) : Exception("O serviço respondeu HTTP $status. Confira o acesso ao modelo e a cota.") {
    val allowsFallback get() = status == 404 || status == 429 || status in 500..599
}

/** Resposta por SSE: o primeiro trecho aparece no chat sem aguardar o texto inteiro. */
class GeminiClient {
    /**
     * [functions] liga as ferramentas do app: quando o modelo pede uma, [runTool] a executa (bloqueante,
     * fora da thread principal) e a resposta volta ao modelo, até [MAX_TOOL_ROUNDS] rodadas.
     */
    fun streamAnswer(key: String, model: ChatModel, history: List<ChatMessage>, mode: ThinkingMode,
        attachment: JSONObject? = null, systemPrompt: String = DEFAULT_SYSTEM, readTimeoutMs: Int = 45_000,
        googleSearch: Boolean = false, functions: JSONArray? = null,
        runTool: ((String, JSONObject) -> JSONObject)? = null,
        onPartial: (String) -> Unit): String {
        val contents = JSONArray()
        history.takeLast(12).forEachIndexed { index, message ->
            val parts = JSONArray().put(JSONObject().put("text", message.text))
            if (attachment != null && index == history.takeLast(12).lastIndex && message.role == "user")
                parts.put(attachment)
            contents.put(JSONObject().put("role", message.role).put("parts", parts))
        }
        val request = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contents)
        val thinking = if (model == ChatModel.GEMINI_25) JSONObject().put("thinkingBudget", when (mode) {
            ThinkingMode.FAST -> 0
            ThinkingMode.BALANCED -> 1024
            ThinkingMode.DEEP -> 4096
        }) else JSONObject().put("thinkingLevel", mode.value)
        request.put("generationConfig", JSONObject().put("thinkingConfig", thinking))
        val tools = JSONArray()
        if (functions != null && functions.length() > 0 && runTool != null)
            tools.put(JSONObject().put("functionDeclarations", functions))
        // Grounding com a Pesquisa Google: o modelo decide quando buscar; usa a mesma chave Gemini.
        if (googleSearch) tools.put(JSONObject().put("google_search", JSONObject()))
        if (tools.length() > 0) request.put("tools", tools)

        val answer = StringBuilder()
        val sources = LinkedHashMap<String, String>()
        var round = 0
        while (true) {
            val prefix = answer.toString()
            val turn = try {
                stream(key, model, request, readTimeoutMs, sources) { text ->
                    onPartial(if (prefix.isEmpty()) text else "$prefix\n\n$text")
                }
            } catch (failure: GeminiHttpException) {
                // Depois de executar ferramentas, não repete a conversa noutro modelo (a ação seria refeita).
                if (round == 0) throw failure
                throw IllegalStateException("O modelo parou depois de usar uma ferramenta (HTTP ${failure.status}).")
            }
            if (turn.text.isNotBlank()) {
                if (answer.isNotEmpty()) answer.append("\n\n")
                answer.append(turn.text.trim())
            }
            if (turn.calls.isEmpty() || runTool == null || round >= MAX_TOOL_ROUNDS) break
            round++
            // A resposta do modelo volta intacta (inclui thoughtSignature, exigida pelos modelos 3.x).
            contents.put(JSONObject().put("role", "model").put("parts", turn.parts))
            val responses = JSONArray()
            for (call in turn.calls) {
                val name = call.optString("name")
                val result = try { runTool(name, call.optJSONObject("args") ?: JSONObject()) }
                    catch (failure: Exception) { JSONObject().put("erro", failure.message?.take(160) ?: "Falha em $name.") }
                responses.put(JSONObject().put("functionResponse", JSONObject().put("name", name)
                    .put("response", JSONObject().put("result", result)).apply { call.optString("id").takeIf { it.isNotBlank() }?.let { put("id", it) } }))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        val text = answer.toString().trim().ifEmpty { throw IllegalStateException("O modelo não enviou resposta em texto.") }
        return if (sources.isEmpty()) text else (text + "\n\nFontes:\n" + sources.entries.take(5)
            .joinToString("\n") { (uri, title) -> "• $title — $uri" })
            .also(onPartial)
    }

    private class Turn(val text: String, val parts: JSONArray, val calls: List<JSONObject>)

    private fun stream(key: String, model: ChatModel, request: JSONObject, readTimeoutMs: Int,
        sources: MutableMap<String, String>, onText: (String) -> Unit): Turn {
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/${model.id}:streamGenerateContent?alt=sse")
            .openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw GeminiHttpException(status)

            val text = StringBuilder()
            val parts = JSONArray()
            val calls = ArrayList<JSONObject>()
            fun processEvent(data: String) {
                if (data.isBlank()) return
                val candidate = JSONObject(data).optJSONArray("candidates")?.optJSONObject(0)
                candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
                    for (i in 0 until chunks.length()) {
                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                        val uri = web.optString("uri")
                        if (uri.isNotBlank()) sources.putIfAbsent(uri, web.optString("title").ifBlank { uri })
                    }
                }
                val chunkParts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: return
                for (i in 0 until chunkParts.length()) {
                    val part = chunkParts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought")) continue
                    parts.put(part)
                    part.optJSONObject("functionCall")?.let { calls.add(it) }
                    val piece = part.optString("text")
                    if (piece.isNotEmpty()) { text.append(piece); onText(text.toString()) }
                }
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val event = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        processEvent(event.toString())
                        event.setLength(0)
                    } else if (line.startsWith("data:")) event.append(line.substring(5).trimStart())
                }
                processEvent(event.toString())
            }
            Turn(text.toString(), parts, calls)
        } finally { connection.disconnect() }
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 5
        const val DEFAULT_SYSTEM = "Você é OSTIE, assistente pessoal do usuário no Android. Responda naturalmente no idioma do usuário. Dê respostas claras, específicas e úteis; use o contexto da conversa, e apresente passos práticos quando necessários. Evite texto genérico e repetição. Seja honesto sobre incertezas. Não diga que abriu aplicativos, acessou arquivos ou usou ferramentas se não fez isso."
    }
}
