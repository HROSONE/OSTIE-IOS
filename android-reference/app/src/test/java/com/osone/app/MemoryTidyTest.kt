package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTidyTest {
    private val original = buildString {
        append("# Memória do OSTIE\n\n> Anotações.\n\n## Sobre o usuário\n- Prefere ser chamado de Rafa (01/09/2026)\n")
        append("\n## Preferências\n")
        repeat(10) { append("- Gosta de café sem açúcar (0${it % 9 + 1}/09/2026)\n") }
        append("- Torce para o Bahia (02/09/2026)\n")
    }

    @Test fun acceptsMergedVersionAndStripsCodeFence() {
        val organized = "```markdown\n# Memória do OSTIE\n\n> Anotações.\n\n## Sobre o usuário\n" +
            "- Prefere ser chamado de Rafa (01/09/2026)\n\n## Preferências\n- Gosta de café sem açúcar (09/09/2026)\n" +
            "- Torce para o Bahia (02/09/2026)\n- Prefere respostas curtas (05/09/2026)\n- Mora em Salvador (03/09/2026)\n" +
            "- Trabalha à noite (04/09/2026)\n```"
        val accepted = MemoryTidy.accept(original, organized)
        assertNotNull(accepted)
        assertTrue(accepted!!.startsWith("# Memória do OSTIE"))
        assertTrue(!accepted.contains("```"))
    }

    @Test fun rejectsVersionThatLostTooMuch() {
        val organized = "# Memória do OSTIE\n\n## Sobre o usuário\n- Prefere ser chamado de Rafa\n"
        assertNull(MemoryTidy.accept(original, organized))
    }

    @Test fun rejectsVersionWithoutPreferredName() {
        val organized = original.replace("Prefere ser chamado de Rafa", "Usuário do app")
        assertNull(MemoryTidy.accept(original, organized))
    }

    @Test fun rejectsTextWithoutSections() {
        assertNull(MemoryTidy.accept(original, "Não consegui reorganizar."))
        assertEquals(12, MemoryTidy.notes(original))
    }
}
