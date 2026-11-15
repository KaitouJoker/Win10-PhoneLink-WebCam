package com.win10phonelink.webcam

import com.win10phonelink.webcam.model.CameraLensOption
import com.win10phonelink.webcam.model.CodecFamily
import com.win10phonelink.webcam.model.EncoderOption
import com.win10phonelink.webcam.model.RotationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocalizationModelsTest {

    private val hangulRegex = Regex("[\\uAC00-\\uD7AF\\u3130-\\u318F]")

    @Test
    fun encoderDisplayName_switchesBetweenEnglishAndKorean() {
        val hwEncoder = EncoderOption(
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
        val swEncoder = EncoderOption(
            codecName = "c2.android.avc.encoder",
            mimeType = "video/avc",
            wireCodec = "h264",
            isHardware = false,
            vendorLabel = "Software",
            supportsCbr = false,
            supportsVbr = true,
            displayName = "",
            supports1080p60 = false
        )

        val hwEn = hwEncoder.formatDisplayName(isKorean = false)
        val hwKo = hwEncoder.formatDisplayName(isKorean = true)
        val swEn = swEncoder.formatDisplayName(isKorean = false)
        val swKo = swEncoder.formatDisplayName(isKorean = true)

        assertEquals("[HW Accel] H264 • c2.qti.avc.encoder (Qualcomm, 1080p60)", hwEn)
        assertEquals("[HW 가속] H264 • c2.qti.avc.encoder (Qualcomm, 1080p60)", hwKo)
        assertEquals("[SW Fallback] H264 • c2.android.avc.encoder", swEn)
        assertEquals("[SW 폴백] H264 • c2.android.avc.encoder", swKo)

        assertFalse("English HW encoder text must not contain Hangul", hangulRegex.containsMatchIn(hwEn))
        assertFalse("English SW encoder text must not contain Hangul", hangulRegex.containsMatchIn(swEn))
    }

    @Test
    fun cameraLensLabel_switchesBetweenEnglishAndKorean() {
        val backLens = CameraLensOption(
            id = "logical:0",
            logicalCameraId = "0",
            physicalCameraId = null,
            label = "Back Wide (ID 0, 5.4mm)",
            wireName = "back",
            isFrontFacing = false,
            sensorOrientation = 90,
            focalLengthMm = 5.4f,
            koLabel = "후면 광각 (ID 0, 5.4mm)"
        )

        val en = backLens.formatLabel(isKorean = false)
        val ko = backLens.formatLabel(isKorean = true)

        assertEquals("Back Wide (ID 0, 5.4mm)", en)
        assertEquals("후면 광각 (ID 0, 5.4mm)", ko)
        assertFalse("English lens label must not contain Hangul", hangulRegex.containsMatchIn(en))
        assertTrue(hangulRegex.containsMatchIn(ko))
    }

    @Test
    fun enumDefaultLabels_containNoKorean() {
        for (codec in CodecFamily.values()) {
            assertFalse(
                "CodecFamily.${codec.name} default label must be English",
                hangulRegex.containsMatchIn(codec.label)
            )
        }
        for (mode in RotationMode.values()) {
            assertFalse(
                "RotationMode.${mode.name} default label must be English",
                hangulRegex.containsMatchIn(mode.label)
            )
        }
    }

    @Test
    fun localeHelper_normalizesAndMapsLocales() {
        assertEquals(LocaleHelper.LANG_KO, LocaleHelper.normalizeLanguage("ko"))
        assertEquals(LocaleHelper.LANG_KO, LocaleHelper.normalizeLanguage("ko-KR"))
        assertEquals(LocaleHelper.LANG_KO, LocaleHelper.normalizeLanguage(" KO "))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage("en"))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage("en-US"))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage("ja"))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage(null))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage(""))

        assertEquals(Locale.KOREAN, LocaleHelper.getLocale(LocaleHelper.LANG_KO))
        assertEquals(Locale.ENGLISH, LocaleHelper.getLocale(LocaleHelper.LANG_EN))
    }
}
