package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveModelsTest {
    /** Lista real devolvida por v1beta/models para uma chave gratuita (setembro de 2026). */
    private val catalog = """
        {"models": [
          {"name": "models/gemini-2.5-flash", "displayName": "Gemini 2.5 Flash", "supportedGenerationMethods": ["generateContent"]},
          {"name": "models/gemini-3.5-transcribe-live", "displayName": "Gemini 3.5 Transcribe Live", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-2.5-flash-native-audio-latest", "displayName": "Gemini 2.5 Flash Native Audio Latest", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-2.5-flash-native-audio-preview-12-2025", "displayName": "Gemini 2.5 Flash Native Audio Preview 12-2025", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-3.1-flash-live-preview", "displayName": "Gemini 3.1 Flash Live Preview", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-3.8-live", "displayName": "Gemini 3.8 Live", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-3.8-live-extended-thinking", "displayName": "Gemini 3.8 Live Extended Thinking", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-robotics-er-2-streaming-preview", "displayName": "Gemini Robotics-ER 2 Streaming Preview", "supportedGenerationMethods": ["bidiGenerateContent"]},
          {"name": "models/gemini-3.5-live-translate-preview", "displayName": "Gemini 3.5 Live Translate Preview", "supportedGenerationMethods": ["bidiGenerateContent"]}
        ]}
    """.trimIndent()

    @Test fun onlyVoiceConversationModelsAreListed() {
        val models = LiveModel.parseCatalog(catalog)
        assertEquals(listOf("gemini-3.8-live-extended-thinking", "gemini-3.8-live", "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-2.5-flash-native-audio-latest"), models.map { it.id })
        assertEquals(models, LiveModel.fromJson(LiveModel.toJson(models)))
    }

    @Test fun fallbackAddsNativeAudioAndAnotherFamilyWithoutThinkingVariants() {
        val known = LiveModel.parseCatalog(catalog)
        val chosen = known.first { it.id == "gemini-3.8-live" }
        assertEquals(listOf("gemini-3.8-live", "gemini-2.5-flash-native-audio-preview-12-2025", "gemini-3.1-flash-live-preview"),
            LiveModel.candidates(chosen, true, known).map { it.id })
        assertEquals(listOf(chosen), LiveModel.candidates(chosen, false, known))
        assertEquals("gemini-9-live", LiveModel.fromId("gemini-9-live", known).id)
    }
}
