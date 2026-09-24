package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoCalibrationTest {
    @Test fun falseInterruptionsRaiseTheBarrierUpToTheLimit() {
        val calibration = EchoCalibration()
        val before = calibration.threshold(0.1f)
        calibration.falseBarge()
        assertTrue(calibration.threshold(0.1f) > before)
        repeat(30) { calibration.falseBarge() }
        assertEquals(EchoCalibration.MAX_RATIO, calibration.ratio, 0.001f)
        assertTrue(calibration.threshold(1f) <= EchoCalibration.MAX_THRESHOLD)
    }

    @Test fun confirmedInterruptionsLowerTheBarrierSlowly() {
        val calibration = EchoCalibration()
        calibration.realBarge()
        assertTrue(calibration.ratio < EchoCalibration.DEFAULT_RATIO)
        repeat(100) { calibration.realBarge() }
        assertEquals(EchoCalibration.MIN_RATIO, calibration.ratio, 0.001f)
        // Silêncio nunca vira interrupção.
        assertEquals(EchoCalibration.MIN_LEVEL, calibration.threshold(0f), 0.001f)
    }

    @Test fun survivesSaveAndLoadAndRelaxesTowardsDefault() {
        val calibration = EchoCalibration()
        repeat(5) { calibration.falseBarge() }
        calibration.learnEcho(0.2f)
        val loaded = EchoCalibration.fromJson(calibration.toJson())
        assertEquals(calibration.ratio, loaded.ratio, 0.001f)
        assertEquals(calibration.echoFloor, loaded.echoFloor, 0.001f)
        assertEquals(5, loaded.falseBarges)
        val raised = loaded.ratio
        loaded.relax()
        assertTrue(loaded.ratio < raised && loaded.ratio > EchoCalibration.DEFAULT_RATIO)
    }

    @Test fun invalidStoredValueFallsBackToDefault() {
        val calibration = EchoCalibration.fromJson("{quebrado")
        assertEquals(EchoCalibration.DEFAULT_RATIO, calibration.ratio, 0.001f)
        assertFalse(calibration.adjusted)
        calibration.falseBarge(); calibration.reset()
        assertFalse(calibration.adjusted)
    }
}
