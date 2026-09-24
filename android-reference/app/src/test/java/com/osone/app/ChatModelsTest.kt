package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {
    @Test fun fallbackNeverEscalatesAboveChosenModel() {
        assertEquals(listOf(ChatModel.GEMINI_36, ChatModel.GEMINI_35, ChatModel.GEMINI_25),
            ChatModel.candidates(ChatModel.GEMINI_36, true))
        assertEquals(listOf(ChatModel.GEMINI_37), ChatModel.candidates(ChatModel.GEMINI_37, false))
        assertEquals(listOf(ChatModel.GEMINI_25), ChatModel.candidates(ChatModel.GEMINI_25, true))
    }

    @Test fun authFailureStopsCascadeButAvailabilityMayContinue() {
        assertFalse(GeminiHttpException(401).allowsFallback)
        assertFalse(GeminiHttpException(403).allowsFallback)
        assertFalse(GeminiHttpException(400).allowsFallback)
        assertTrue(GeminiHttpException(404).allowsFallback)
        assertTrue(GeminiHttpException(429).allowsFallback)
        assertTrue(GeminiHttpException(503).allowsFallback)
    }

    @Test fun liveOffersThirtyDistinctNamedVoicesAndKeepsExistingGeminiDefault() {
        assertEquals(30, LiveVoices.names.size)
        assertEquals(30, LiveVoices.names.toSet().size)
        assertTrue(LiveVoices.names.contains("Kore"))
        assertEquals("Puck", LiveVoices.fromName(null))
        assertEquals(ChatProvider.GEMINI, ChatProvider.fromValue(null))
    }
}
