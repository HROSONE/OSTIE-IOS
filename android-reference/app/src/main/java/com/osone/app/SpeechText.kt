package com.osone.app

/** Prepara a resposta do chat para ser falada: sem código, links, fontes e símbolos de Markdown. Sem Android. */
object SpeechText {
    private val codeBlock = Regex("```[\\s\\S]*?(```|$)")
    private val link = Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)")
    private val url = Regex("https?://\\S+")
    private val sources = Regex("\\n\\s*Fontes:\\s*\\n[\\s\\S]*$")
    private val tableRule = Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$")

    fun clean(text: String): String = text
        .replace(sources, "")
        .replace(codeBlock, " O código está na mensagem. ")
        .replace(link, "$1")
        .replace(url, "")
        .replace(tableRule, "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("(?m)^\\s*>\\s?"), "")
        .replace(Regex("[*_`~|]+"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .trim()

    /**
     * Partes de até [max] caracteres, cortadas em fim de frase (ou de linha, ou de palavra), para a
     * primeira começar a tocar logo enquanto as seguintes são geradas.
     */
    fun chunks(text: String, max: Int = 600, first: Int = 220): List<String> {
        val parts = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            val limit = if (parts.isEmpty()) first else max
            if (rest.length <= limit) { parts += rest; break }
            val window = rest.substring(0, limit)
            val cut = listOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "),
                window.lastIndexOf('\n')).maxOrNull()?.takeIf { it >= limit / 3 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it >= limit / 3 }
                ?: limit
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        return parts.filter { it.isNotBlank() }
    }
}
