package com.osone.app

/** Reconhece HTML/SVG vindos do modelo e monta a página usada pelo preview. Sem dependências Android. */
object DocumentPreview {
    private val fence = Regex("```([\\w+-]*)[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n?```")
    private val markupStart = Regex("^\\s*(<!--[\\s\\S]*?-->\\s*)*<(!doctype|html|head|body|svg|\\?xml|div|section|main|style|canvas|table|h[1-6]|p|ul|ol|form|button|img|script|header|nav|article)\\b",
        RegexOption.IGNORE_CASE)

    /** Remove uma cerca ```lang ... ``` que envolva todo o conteúdo. */
    fun stripFence(content: String): String {
        val trimmed = content.trim()
        val match = fence.matchEntire(trimmed) ?: return content
        return match.groupValues[2]
    }

    /** Todo formato de marcação (html, svg, xml…) ou conteúdo com cara de marcação vira "html". */
    fun detectFormat(requested: String?, content: String): String {
        val value = requested.orEmpty().trim().lowercase()
        if (value.contains("html") || value.contains("svg") || value == "xml" || value == "xhtml") return "html"
        return if (looksLikeMarkup(content)) "html" else "text"
    }

    private val markupAnywhere = Regex("<!doctype html|<html[\\s>]|<svg[\\s>]", RegexOption.IGNORE_CASE)
    private val externalResource = Regex("(src|href)\\s*=\\s*[\"']https?://|@import\\s+url\\(\\s*[\"']?https?://|<script[^>]+src=[\"']//",
        RegexOption.IGNORE_CASE)

    fun looksLikeMarkup(content: String): Boolean =
        markupStart.containsMatchIn(content) || markupAnywhere.containsMatchIn(content)

    /**
     * Separa a marcação de uma resposta com texto em volta: cerca que envolve tudo, bloco ```html/```svg
     * dentro de uma explicação, ou o trecho que começa em <!doctype>, <html> ou <svg>.
     */
    fun extractMarkup(content: String): String {
        val unfenced = stripFence(content).trim()
        if (markupStart.containsMatchIn(unfenced)) return unfenced
        codeBlock(content)?.let { (language, code) ->
            if (language.lowercase() in setOf("html", "svg", "xml", "xhtml") || looksLikeMarkup(code)) return code.trim()
        }
        val start = markupAnywhere.find(unfenced)?.range?.first ?: return unfenced
        val tail = unfenced.substring(start)
        val closing = Regex("</(html|svg)>", RegexOption.IGNORE_CASE).findAll(tail).lastOrNull()
        return (if (closing != null) tail.substring(0, closing.range.last + 1) else tail.substringBefore("```")).trim()
    }

    /** A página tenta carregar CDN, fontes ou imagens da internet (bloqueadas no preview por padrão). */
    fun usesNetwork(content: String): Boolean = externalResource.containsMatchIn(content)

    fun isSvg(content: String): Boolean {
        val start = content.trimStart().take(400).lowercase()
        return start.startsWith("<svg") || (start.startsWith("<?xml") && start.contains("<svg"))
    }

    /** Documento completo para o WebView: SVG e fragmentos recebem página e viewport para caber na tela. */
    fun page(content: String): String {
        val source = extractMarkup(content)
        val lower = source.take(600).lowercase()
        val viewport = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        return when {
            isSvg(source) -> "<!doctype html><html><head><meta charset=\"utf-8\">$viewport<style>" +
                "html,body{margin:0;height:100%;background:#fff}body{display:flex;align-items:center;justify-content:center}" +
                "svg{max-width:100%;max-height:100vh;height:auto}</style></head><body>" +
                source.replace(Regex("^<\\?xml[^>]*>\\s*"), "") + "</body></html>"
            lower.contains("<html") || lower.contains("<!doctype") ->
                if (lower.contains("name=\"viewport\"") || !lower.contains("<head>")) source
                else source.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$viewport")
            else -> "<!doctype html><html><head><meta charset=\"utf-8\">$viewport</head><body>$source</body></html>"
        }
    }

    /** Maior bloco de código de uma resposta do chat, para abrir na Aba de Escrita. */
    fun codeBlock(message: String): Pair<String, String>? = Regex("```([\\w+-]*)[ \\t]*\\r?\\n([\\s\\S]*?)```")
        .findAll(message).map { it.groupValues[1] to it.groupValues[2].trimEnd() }
        .filter { it.second.isNotBlank() }.maxByOrNull { it.second.length }
}
