package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCloseReasonTest {
    @Test fun earlyInternalErrorIsTreatedAsRejectedSetup() {
        assertTrue(LiveCloseReason.isSetupRejection("WebSocket 1011 (erro interno do serviço)", false, false, 0))
        assertTrue(LiveCloseReason.isSetupRejection("WebSocket 1011 (erro interno do serviço)", true, false, 3_000))
        assertTrue(LiveCloseReason.isSetupRejection("WebSocket 1007 (configuração ou modelo recusado)", false, false, 0))
        assertTrue(LiveCloseReason.isSetupRejection("HTTP 400", false, false, 0))
    }

    @Test fun midConversationDropsAndQuotaAreNotDowngraded() {
        assertFalse(LiveCloseReason.isSetupRejection("WebSocket 1011 (erro interno do serviço)", true, true, 3_000))
        assertFalse(LiveCloseReason.isSetupRejection("WebSocket 1011 (erro interno do serviço)", true, false, 60_000))
        assertFalse(LiveCloseReason.isSetupRejection("WebSocket 1011 (cota esgotada) · quota", false, false, 0))
        assertFalse(LiveCloseReason.isSetupRejection("WebSocket 1006", false, false, 0))
        assertFalse(LiveCloseReason.isSetupRejection("WebSocket 10110", false, false, 0))
    }

    @Test fun excerptHidesKeys() {
        assertEquals(" · bad request key=*** from ***",
            LiveCloseReason.excerpt("bad   request key=abc123 from AIzaSyA1234567890abcdef"))
        assertEquals("", LiveCloseReason.excerpt("  "))
    }

    @Test fun searchQuotaAtStartDropsOnlyTheSearch() {
        val quota = "WebSocket 1011 (cota esgotada) · You exceeded your current quota"
        assertTrue(LiveCloseReason.isSearchQuota(quota, false, false, 0, searchOn = true))
        assertFalse(LiveCloseReason.isSearchQuota(quota, false, false, 0, searchOn = false))
        assertFalse(LiveCloseReason.isSearchQuota(quota, true, true, 5_000, searchOn = true))
        assertFalse(LiveCloseReason.isSearchQuota("WebSocket 1011 (erro interno do serviço)", false, false, 0, searchOn = true))
    }
}
