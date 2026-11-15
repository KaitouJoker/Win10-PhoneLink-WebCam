package com.win10phonelink.webcam

import com.win10phonelink.webcam.camera.CameraStreamEngine
import com.win10phonelink.webcam.codec.VideoEncoderCatalog
import com.win10phonelink.webcam.model.BitratePreset
import com.win10phonelink.webcam.model.EncoderOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighBitratePresetAndClampingTest {

    private val hangulRegex = Regex("[\\uAC00-\\uD7AF\\u3130-\\u318F]")

    @Test
    fun bitratePresets_coverUpTo200MbpsStrictlyAscending() {
        val presets = BitratePreset.values().toList()
        val expectedMbps = listOf(4, 6, 8, 12, 16, 20, 25, 35, 50, 60, 80, 100, 120, 150, 200)
        assertEquals(expectedMbps.map { it * 1_000_000 }, presets.map { it.bps })
        assertEquals(BitratePreset.MBPS_200, presets.last())

        for (i in 1 until presets.size) {
            assertTrue(
                "Preset ${presets[i]} must exceed ${presets[i - 1]}",
                presets[i].bps > presets[i - 1].bps
            )
        }
        for (preset in presets) {
            assertTrue(preset.bps > 0)
            assertFalse(
                "BitratePreset.${preset.name} label must be ASCII/English: ${preset.label}",
                hangulRegex.containsMatchIn(preset.label)
            )
        }
    }

    @Test
    fun encoderOption_defaultsMaxBitrateToZeroAndPreservesDisplayName() {
        val defaultEnc = EncoderOption(
            codecName = "c2.qti.avc.encoder",
            mimeType = "video/avc",
            wireCodec = "h264",
            isHardware = true,
            vendorLabel = "Qualcomm",
            supportsCbr = true,
            supportsVbr = true,
            displayName = "",
            supports1080p60 = true
        )
        assertEquals(0, defaultEnc.maxBitrateBps)
        assertEquals("[HW Accel] H264 • c2.qti.avc.encoder (Qualcomm, 1080p60)", defaultEnc.formatDisplayName(false))

        val cappedEnc = defaultEnc.copy(maxBitrateBps = 120_000_000)
        assertEquals(120_000_000, cappedEnc.maxBitrateBps)
        assertEquals("[HW 가속] H264 • c2.qti.avc.encoder (Qualcomm, 1080p60)", cappedEnc.formatDisplayName(true))
    }

    @Test
    fun clampBitrateForRange_clampsAboveBelowNullAndInvalidBoundsSafely() {
        assertEquals(
            100_000_000,
            CameraStreamEngine.clampBitrateForRange(200_000_000, 100_000, 100_000_000)
        )
        assertEquals(
            60_000_000,
            CameraStreamEngine.clampBitrateForRange(60_000_000, 100_000, 100_000_000)
        )
        assertEquals(
            100_000,
            CameraStreamEngine.clampBitrateForRange(10_000, 100_000, 100_000_000)
        )
        assertEquals(
            150_000_000,
            CameraStreamEngine.clampBitrateForRange(150_000_000, null, null)
        )
        assertEquals(
            80_000_000,
            CameraStreamEngine.clampBitrateForRange(120_000_000, null, 80_000_000)
        )
        assertEquals(
            120_000_000,
            CameraStreamEngine.clampBitrateForRange(120_000_000, 200_000_000, 100_000_000)
        )
        assertEquals(
            120_000_000,
            CameraStreamEngine.clampBitrateForRange(120_000_000, 0, -1)
        )
        assertEquals(
            1,
            CameraStreamEngine.clampBitrateForRange(0, null, null)
        )
    }

    @Test
    fun sanitizeMaxBitrateBps_filtersSentinelsAndSubMbpsBounds() {
        assertEquals(0, VideoEncoderCatalog.sanitizeMaxBitrateBps(null))
        assertEquals(0, VideoEncoderCatalog.sanitizeMaxBitrateBps(0))
        assertEquals(0, VideoEncoderCatalog.sanitizeMaxBitrateBps(-1))
        assertEquals(0, VideoEncoderCatalog.sanitizeMaxBitrateBps(500_000))
        assertEquals(120_000_000, VideoEncoderCatalog.sanitizeMaxBitrateBps(120_000_000))
        assertEquals(200_000_000, VideoEncoderCatalog.sanitizeMaxBitrateBps(200_000_000))
        assertEquals(0, VideoEncoderCatalog.sanitizeMaxBitrateBps(1_000_000_000))
        assertEquals(0, VideoEncoderCatalog.sanitizeMaxBitrateBps(Int.MAX_VALUE))
    }
}
