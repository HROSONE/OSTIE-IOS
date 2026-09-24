package com.osone.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    private fun result(vararg words: Pair<String, Double>) = """{"result":[""" +
        words.joinToString(",") { (word, conf) -> """{"word":"$word","conf":$conf}""" } + """],"text":"x"}"""

    @Test fun greetingFollowedByNameWakes() {
        assertTrue(WakePhrase.matches(result("ei" to 0.9, "ostie" to 0.7)))
        assertTrue(WakePhrase.matches(result("oi" to 0.8, "hóstia" to 0.65)))
    }

    @Test fun nameAloneNeedsHighConfidence() {
        assertFalse(WakePhrase.matches(result("ostie" to 0.7)))
        assertTrue(WakePhrase.matches(result("ostie" to 0.9)))
    }

    @Test fun lowConfidenceOrOtherWordsDoNotWake() {
        assertFalse(WakePhrase.matches(result("ei" to 0.9, "ostie" to 0.4)))
        assertFalse(WakePhrase.matches(result("[unk]" to 1.0)))
        assertFalse(WakePhrase.matches("""{"text":""}"""))
        assertFalse(WakePhrase.matches("não é json"))
    }

    @Test fun grammarKeepsUnknownBucket() {
        val phrases = JSONArray(WakePhrase.grammar)
        assertEquals("[unk]", phrases.getString(phrases.length() - 1))
    }
}
