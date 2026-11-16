package com.win10phonelink.webcam

import android.util.Range
import com.win10phonelink.webcam.camera.CameraStreamEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraFpsSelectionTest {

    @Test
    fun pickBestFpsRange_prefersLocked60ThenVariable60ThenBracketed120() {
        val mixed = arrayOf(Range(15, 30), Range(30, 30), Range(15, 60), Range(30, 60), Range(60, 60))
        assertEquals(Range(60, 60), CameraStreamEngine.pickBestFpsRange(mixed, 60))

        val var60Only = arrayOf(Range(15, 30), Range(30, 30), Range(15, 60), Range(30, 60))
        assertEquals(Range(30, 60), CameraStreamEngine.pickBestFpsRange(var60Only, 60))

        val bracketed120 = arrayOf(Range(15, 30), Range(30, 30), Range(60, 120))
        assertEquals(Range(60, 120), CameraStreamEngine.pickBestFpsRange(bracketed120, 60))

        val capped30 = arrayOf(Range(10, 30), Range(15, 30), Range(30, 30))
        assertEquals(Range(30, 30), CameraStreamEngine.pickBestFpsRange(capped30, 60))
        assertEquals(Range(30, 30), CameraStreamEngine.pickBestFpsRange(mixed, 30))
        assertNull(CameraStreamEngine.pickBestFpsRange(emptyArray(), 60))
    }

    @Test
    fun pickBestHighSpeedFpsRange_picksLocked60AndIgnoresSlowMotion120And240() {
        val hsRanges = arrayOf(Range(30, 60), Range(60, 60), Range(30, 120), Range(120, 120), Range(240, 240))
        assertEquals(Range(60, 60), CameraStreamEngine.pickBestHighSpeedFpsRange(hsRanges, 60))

        val varHsOnly = arrayOf(Range(30, 60), Range(30, 120), Range(120, 120))
        assertEquals(Range(30, 60), CameraStreamEngine.pickBestHighSpeedFpsRange(varHsOnly, 60))

        val slowMoOnly = arrayOf(Range(120, 120), Range(240, 240))
        assertNull(CameraStreamEngine.pickBestHighSpeedFpsRange(slowMoOnly, 60))
    }

    @Test
    fun computeGainMatchedManualParams_clampsExposureAndScalesIsoWithinSensorRange() {
        val res = CameraStreamEngine.computeGainMatchedManualParams(
            lastAeExpNs = 33_333_333L,
            lastAeIso = 400,
            expLowerNs = 100_000L,
            expUpperNs = 500_000_000L,
            isoLower = 50,
            isoUpper = 3200,
            targetFps = 60
        )
        assertEquals(16_666_666L, res.frameDurationNs)
        assertTrue("Exposure ${res.exposureNs} must fit within 16ms budget", res.exposureNs <= 16_000_000L)
        assertTrue("Compensated ISO ${res.iso} should approximately double (~851)", res.iso in 800..900)

        // Bright scene where exposure is already <16ms should not drop ISO unnecessarily
        val bright = CameraStreamEngine.computeGainMatchedManualParams(
            lastAeExpNs = 4_000_000L,
            lastAeIso = 100,
            expLowerNs = 100_000L,
            expUpperNs = 500_000_000L,
            isoLower = 50,
            isoUpper = 3200,
            targetFps = 60
        )
        assertEquals(4_000_000L, bright.exposureNs)
        assertEquals(100, bright.iso)

        // Dim scene clamps ISO cleanly at hardware ceiling
        val dim = CameraStreamEngine.computeGainMatchedManualParams(
            lastAeExpNs = 100_000_000L,
            lastAeIso = 1600,
            expLowerNs = 100_000L,
            expUpperNs = 500_000_000L,
            isoLower = 50,
            isoUpper = 3200,
            targetFps = 60
        )
        assertEquals(3200, dim.iso)
    }

    @Test
    fun selectFastestControlMode_picksHighestPriorityAvailableMode() {
        val modes = intArrayOf(2, 1)
        assertEquals(1, CameraStreamEngine.selectFastestControlMode(modes, 0, 1))
        assertNull(CameraStreamEngine.selectFastestControlMode(modes, 3, 4))
        assertNull(CameraStreamEngine.selectFastestControlMode(null, 0, 1))
    }
}
