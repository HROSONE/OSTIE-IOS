package com.osone.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** IDs devolvidos pela própria conta Groq; nunca armazena ou registra a chave. */
class GroqCatalog {
    fun available(key: String): List<String> {
        val connection = URL("https://api.groq.com/openai/v1/models").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $key")
            val status = connection.responseCode
            if (status !in 200..299) throw ChatProviderHttpException(ChatProvider.GROQ, status)
            val models = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data")
                ?: return emptyList()
            (0 until models.length()).mapNotNull { index ->
                models.optJSONObject(index)?.optString("id")?.takeIf { id ->
                    id.isNotBlank() && !id.contains("whisper") && !id.contains("orpheus") &&
                        !id.contains("playai") && !id.contains("prompt-guard") && !id.contains("safeguard")
                }
            }.distinct().sortedWith(compareBy<String> { id ->
                GroqModel.entries.indexOfFirst { model -> model.id == id }.let { if (it < 0) 100 else it }
            }.thenBy { it })
        } finally { connection.disconnect() }
    }
}
