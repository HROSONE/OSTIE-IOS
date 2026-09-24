package com.osone.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Modelo Live pelo ID exato da API. A lista real vem da própria chave (ListModels) — nomes fixos
 * no código podem não existir ou não ter cota no projeto do usuário.
 */
data class LiveModel(val id: String, val label: String) {
    companion object {
        val DEFAULTS = listOf(
            LiveModel("gemini-3.8-live", "Gemini 3.8 Live"),
            LiveModel("gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live"),
            LiveModel("gemini-2.5-flash-native-audio-preview-12-2025", "Gemini 2.5 Flash Live"))

        fun fromId(value: String?, known: List<LiveModel>): LiveModel =
            known.firstOrNull { it.id == value } ?: value?.takeIf { it.isNotBlank() }?.let { LiveModel(it, it) }
                ?: known.firstOrNull() ?: DEFAULTS.first()

        /**
         * Escolhido primeiro; com fallback, um modelo de áudio nativo (o mais compatível) e outro Live,
         * de famílias diferentes. Variantes "thinking" só entram se escolhidas.
         */
        fun candidates(selected: LiveModel, fallback: Boolean, known: List<LiveModel>): List<LiveModel> {
            if (!fallback) return listOf(selected)
            val others = known.filterNot { it.id == selected.id || it.id.contains("thinking") }.sortedByDescending { it.id }
            val native = others.firstOrNull { it.id.contains("native-audio") }
            val live = others.firstOrNull { !it.id.contains("native-audio") }
            return listOfNotNull(selected, native, live)
        }

        /** Resposta de v1beta/models: só modelos com bidiGenerateContent (a API Live). */
        fun parseCatalog(json: String): List<LiveModel> {
            val list = JSONObject(json).optJSONArray("models") ?: JSONArray()
            return (0 until list.length()).mapNotNull { index ->
                val model = list.optJSONObject(index) ?: return@mapNotNull null
                val methods = model.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
                if ((0 until methods.length()).none { methods.optString(it) == "bidiGenerateContent" }) return@mapNotNull null
                val id = model.optString("name").removePrefix("models/")
                if (id.isBlank()) null else LiveModel(id, model.optString("displayName").ifBlank { id })
            }.distinctBy { it.id }
                // Só modelos de conversa por voz: tradução, transcrição e outros streams (ex.: robótica) ficam fora.
                .filter { (it.id.contains("live") || it.id.contains("native-audio")) &&
                    !it.id.contains("translate") && !it.id.contains("transcribe") }
                .sortedByDescending { it.id }
        }

        fun toJson(models: List<LiveModel>): String = JSONArray(models.map {
            JSONObject().put("id", it.id).put("label", it.label) }).toString()

        fun fromJson(json: String?): List<LiveModel> = try {
            JSONArray(json ?: "[]").let { list -> (0 until list.length()).map {
                list.getJSONObject(it).let { item -> LiveModel(item.getString("id"), item.getString("label")) } } }
        } catch (_: Exception) { emptyList() }
    }
}

/** Nomes oficiais das vozes predefinidas aceitas pelos modelos Live com áudio nativo. */
object LiveVoices {
    val names = listOf(
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda",
        "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus",
        "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
        "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima",
        "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )
    fun fromName(name: String?) = names.firstOrNull { it == name } ?: "Puck"
}
