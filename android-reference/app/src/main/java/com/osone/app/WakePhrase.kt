package com.osone.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Reconhece "Ei, Ostie" no resultado do reconhecedor offline (Vosk). "Ostie" não existe em português,
 * então a gramática também aceita palavras de som parecido; palavras fora do vocabulário do modelo são
 * ignoradas por ele. Sem Android: testável na JVM.
 */
object WakePhrase {
    private val names = setOf("ostie", "osti", "ostia", "hostia", "hostie", "ostin")
    private val greetings = setOf("ei", "hei", "hey", "oi", "e", "o", "ola")

    /** Frases que o reconhecedor pode escolher; "[unk]" absorve todo o resto da fala. */
    val grammar: String = JSONArray(listOf("ei ostie", "ei osti", "ei hóstia", "ei óstia", "hey ostie",
        "oi ostie", "oi hóstia", "ostie", "hóstia", "[unk]")).toString()

    /**
     * [result] é o JSON final do Vosk com palavras e confiança (setWords(true)). Vale quando o nome vem
     * depois de um chamado ("ei", "oi") com confiança razoável, ou sozinho com confiança alta.
     */
    fun matches(result: String, minConfidence: Double = 0.6): Boolean {
        val words = try { JSONObject(result).optJSONArray("result") } catch (_: Exception) { null } ?: return false
        for (i in 0 until words.length()) {
            val item = words.optJSONObject(i) ?: continue
            if (normalize(item.optString("word")) !in names) continue
            val confidence = item.optDouble("conf", 0.0)
            val greeted = i > 0 && normalize(words.optJSONObject(i - 1)?.optString("word").orEmpty()) in greetings
            if (greeted && confidence >= minConfidence) return true
            if (confidence >= maxOf(minConfidence, 0.85)) return true
        }
        return false
    }

    private fun normalize(word: String) = Normalizer.normalize(word.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
