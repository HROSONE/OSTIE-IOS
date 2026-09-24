package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun removesCodeLinksSourcesAndMarkdown() {
        val answer = "## Resumo\n**Pronto!** Veja o [site](https://exemplo.com) e o código:\n" +
            "```kotlin\nfun main() {}\n```\n- item um\n\nFontes:\n• Exemplo — https://exemplo.com"
        val spoken = SpeechText.clean(answer)
        assertFalse(spoken.contains("fun main"))
        assertFalse(spoken.contains("https://"))
        assertFalse(spoken.contains("Fontes"))
        assertFalse(spoken.contains("*") || spoken.contains("#"))
        assertTrue(spoken.contains("Pronto!"))
        assertTrue(spoken.contains("site"))
        assertTrue(spoken.contains("O código está na mensagem."))
        assertTrue(spoken.contains("item um"))
    }

    @Test fun firstChunkIsShortAndCutsAtSentences() {
        val text = (1..30).joinToString(" ") { "Esta é a frase número $it do texto." }
        val parts = SpeechText.chunks(text)
        assertTrue(parts.first().length <= 220)
        assertTrue(parts.all { it.length <= 600 })
        assertTrue(parts.dropLast(1).all { it.endsWith(".") })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test fun shortTextIsOneChunkAndEmptyIsNone() {
        assertEquals(listOf("Olá!"), SpeechText.chunks("Olá!"))
        assertTrue(SpeechText.chunks("   ").isEmpty())
    }
}
