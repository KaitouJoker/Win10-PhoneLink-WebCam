package com.win10phonelink.webcam

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Looper
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.win10phonelink.webcam.camera.CameraLensCatalog
import com.win10phonelink.webcam.model.StreamConnectionState
import com.win10phonelink.webcam.net.PairingPayload
import com.win10phonelink.webcam.service.CameraStreamService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.shadows.ShadowMediaCodecList
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ko-rKR")
class MainActivityLocalizationRoboTest {

    private val hangulRegex = Regex("[\\uAC00-\\uD7AF\\u3130-\\u318F]")

    @Before
    fun setUp() {
        Locale.setDefault(Locale.KOREAN)
        val appCtx = RuntimeEnvironment.getApplication()
        appCtx.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("hw_accel_prompted", true)
            .commit()

        registerFakeEncoders()
        registerFakeCameras(appCtx)
    }

    private fun registerFakeEncoders() {
        val h264Caps = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            MediaCodecInfo.CodecProfileLevel.AVCLevel42
        )
        h264Caps.colorFormats = intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        val hevcCaps = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41
        )
        hevcCaps.colorFormats = intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("c2.qti.avc.encoder")
                .setIsEncoder(true)
                .setIsHardwareAccelerated(true)
                .setCapabilities(h264Caps)
                .build()
        )
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("c2.android.avc.encoder")
                .setIsEncoder(true)
                .setIsHardwareAccelerated(false)
                .setCapabilities(h264Caps)
                .build()
        )
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("c2.qti.hevc.encoder")
                .setIsEncoder(true)
                .setIsHardwareAccelerated(true)
                .setCapabilities(hevcCaps)
                .build()
        )
    }

    private fun registerFakeCameras(context: Context) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val shadowCm = Shadows.shadowOf(cm) as ShadowCameraManager
        val backChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowBack = Shadows.shadowOf(backChars)
        shadowBack.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowBack.set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        shadowBack.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(5.4f))
        shadowCm.addCamera("0", backChars)

        val frontChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowFront = Shadows.shadowOf(frontChars)
        shadowFront.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_FRONT)
        shadowFront.set(CameraCharacteristics.SENSOR_ORIENTATION, 270)
        shadowFront.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(3.2f))
        shadowCm.addCamera("1", frontChars)
    }

    @Test
    fun localizedResources_switchFromKoreanOsToEnglish() {
        val appCtx = RuntimeEnvironment.getApplication()
        val koRes = LocaleHelper.getLocalizedResources(appCtx, LocaleHelper.LANG_KO)
        val enRes = LocaleHelper.getLocalizedResources(appCtx, LocaleHelper.LANG_EN)

        assertEquals("인코더 장치 설정 (Hardware Encoder)", koRes.getString(R.string.label_encoder))
        assertEquals("Hardware Encoder", enRes.getString(R.string.label_encoder))
        assertFalse(hangulRegex.containsMatchIn(enRes.getString(R.string.encoder_detail_hw, "Qualcomm")))
        assertFalse(hangulRegex.containsMatchIn(enRes.getString(R.string.encoder_detail_count, 1, 2)))
    }

    @Test
    fun mainActivity_switchLanguageToEnglish_updatesAllSpinnersAndEncoderItems() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val labelEncoder = activity.findViewById<TextView>(R.id.labelEncoder)
        val spinnerEncoder = activity.findViewById<Spinner>(R.id.spinnerEncoder)
        val spinnerLens = activity.findViewById<Spinner>(R.id.spinnerLens)
        val spinnerCodec = activity.findViewById<Spinner>(R.id.spinnerCodec)
        val spinnerRotation = activity.findViewById<Spinner>(R.id.spinnerRotation)
        val spinnerLanguage = activity.findViewById<Spinner>(R.id.spinnerLanguage)
        val textEncoderDetail = activity.findViewById<TextView>(R.id.textEncoderDetail)
        val btnSelectHwEncoder = activity.findViewById<Button>(R.id.btnSelectHwEncoder)

        assertTrue("Expected >= 2 encoders, got ${spinnerEncoder.adapter.count}", spinnerEncoder.adapter.count >= 2)
        assertTrue(
            "Expected Korean HW encoder label initially, got: ${spinnerEncoder.adapter.getItem(0)}",
            spinnerEncoder.adapter.getItem(0).toString().contains("[HW 가속]")
        )
        assertTrue(
            "Expected Korean SW encoder label initially, got: ${spinnerEncoder.adapter.getItem(1)}",
            spinnerEncoder.adapter.getItem(1).toString().contains("[SW 폴백]")
        )
        assertEquals("인코더 장치 설정 (Hardware Encoder)", labelEncoder.text.toString())

        // Select non-zero encoder before switching language
        spinnerEncoder.setSelection(1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Switch language to English via spinner
        spinnerLanguage.setSelection(1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.getLanguage(activity))
        assertEquals("Hardware Encoder", labelEncoder.text.toString())
        assertEquals("Inspect / Select HW Encoders", btnSelectHwEncoder.text.toString())
        assertEquals(1, spinnerEncoder.selectedItemPosition)
        assertFalse(
            "textEncoderDetail must not contain Hangul after switching to English: ${textEncoderDetail.text}",
            hangulRegex.containsMatchIn(textEncoderDetail.text.toString())
        )
        for (i in 0 until spinnerLens.adapter.count) {
            val item = spinnerLens.adapter.getItem(i).toString()
            assertFalse("Lens item $i still has Hangul: $item", hangulRegex.containsMatchIn(item))
        }
        for (i in 0 until spinnerCodec.adapter.count) {
            val item = spinnerCodec.adapter.getItem(i).toString()
            assertFalse("Codec item $i still has Hangul: $item", hangulRegex.containsMatchIn(item))
        }
        for (i in 0 until spinnerEncoder.adapter.count) {
            val item = spinnerEncoder.adapter.getItem(i).toString()
            assertFalse("Encoder item $i still has Hangul: $item", hangulRegex.containsMatchIn(item))
        }
        for (i in 0 until spinnerRotation.adapter.count) {
            val item = spinnerRotation.adapter.getItem(i).toString()
            assertFalse("Rotation item $i still has Hangul: $item", hangulRegex.containsMatchIn(item))
        }

        // Open HW Encoder dialog in English and check items
        btnSelectHwEncoder.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val hwDlg = ShadowAlertDialog.getLatestDialog() as AlertDialog
        val listView = hwDlg.listView
        assertTrue("Expected HW dialog items", listView.adapter.count > 0)
        for (i in 0 until listView.adapter.count) {
            val item = listView.adapter.getItem(i).toString()
            assertFalse("HW dialog item $i still has Hangul: $item", hangulRegex.containsMatchIn(item))
        }
        hwDlg.dismiss()

        // Switch codec to HEVC and back to H264 while in English
        spinnerCodec.setSelection(1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        for (i in 0 until spinnerEncoder.adapter.count) {
            val item = spinnerEncoder.adapter.getItem(i).toString()
            assertFalse("HEVC encoder item $i still has Hangul: $item", hangulRegex.containsMatchIn(item))
        }
        assertFalse(
            "HEVC textEncoderDetail still has Hangul: ${textEncoderDetail.text}",
            hangulRegex.containsMatchIn(textEncoderDetail.text.toString())
        )

        // Prove spinner listener is active again (suppressSpinnerEvents cleared) after switching to English
        spinnerCodec.setSelection(0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        spinnerEncoder.setSelection(0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "c2.qti.avc.encoder",
            activity.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE)
                .getString("encoder_name", null)
        )

        // Switch language back to Korean and verify all items restore Korean labels
        spinnerLanguage.setSelection(0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LocaleHelper.LANG_KO, LocaleHelper.getLanguage(activity))
        assertEquals("인코더 장치 설정 (Hardware Encoder)", labelEncoder.text.toString())
        assertTrue(
            "Expected Korean HW badge after switching back, got: ${spinnerEncoder.adapter.getItem(0)}",
            spinnerEncoder.adapter.getItem(0).toString().contains("[HW 가속]")
        )
        assertTrue(
            "Expected Korean codec label after switching back, got: ${spinnerCodec.adapter.getItem(0)}",
            spinnerCodec.adapter.getItem(0).toString().contains("저지연 권장")
        )
        assertTrue(
            "Expected Korean rotation label after switching back, got: ${spinnerRotation.adapter.getItem(0)}",
            spinnerRotation.adapter.getItem(0).toString().contains("자동")
        )
    }

    @Test
    fun cameraLensCatalog_discoversBilingualLabelsWithoutHangulInEnglish() {
        val appCtx = RuntimeEnvironment.getApplication()
        val lenses = CameraLensCatalog.discoverLenses(appCtx)
        assertEquals(2, lenses.size)

        val enLabels = lenses.map { it.formatLabel(isKorean = false) }
        val koLabels = lenses.map { it.formatLabel(isKorean = true) }

        assertTrue("Expected English Back lens label, got $enLabels", enLabels.any { it.contains("Back") })
        assertTrue("Expected English Front lens label, got $enLabels", enLabels.any { it.contains("Front") })
        assertTrue("Expected Korean Back lens label, got $koLabels", koLabels.any { it.contains("후면") })
        assertTrue("Expected Korean Front lens label, got $koLabels", koLabels.any { it.contains("전면") })
        for (label in enLabels) {
            assertFalse("English lens label must not contain Hangul: $label", hangulRegex.containsMatchIn(label))
        }
    }

    @Test
    fun cameraStreamService_refreshLocalization_updatesTelemetryAndNotification() {
        val appCtx = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(appCtx).grantPermissions(Manifest.permission.CAMERA)
        LocaleHelper.setLanguage(appCtx, LocaleHelper.LANG_KO)

        val controller = Robolectric.buildService(CameraStreamService::class.java)
        val service = controller.create().get()
        registerFakeCameras(service)

        try {
            val pairing = PairingPayload(version = 1, ip = "127.0.0.1", port = 1, token = "0123456789abcdef", name = "TestPC")
            val startIntent = Intent(appCtx, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_START_STREAM
                putExtra(CameraStreamService.EXTRA_PAIRING_JSON, pairing.toJsonString())
                putExtra(CameraStreamService.EXTRA_LENS_ID, "logical:0")
                putExtra(CameraStreamService.EXTRA_ENCODER_NAME, "c2.qti.avc.encoder")
            }

            service.onStartCommand(startIntent, 0, 1)
            val koTelemetry = service.telemetry.value
            assertTrue(
                "Expected Korean activeLensLabel initially, got: ${koTelemetry.activeLensLabel}",
                koTelemetry.activeLensLabel.contains("후면")
            )
            assertTrue(
                "Expected Korean statusDetail initially, got: ${koTelemetry.statusDetail}",
                hangulRegex.containsMatchIn(koTelemetry.statusDetail)
            )

            val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val shadowNm = Shadows.shadowOf(nm)
            val koNotif = Shadows.shadowOf(service).lastForegroundNotification
                ?: shadowNm.getNotification(4001)
            assertTrue(
                "Expected Korean notification title initially",
                hangulRegex.containsMatchIn(koNotif.extras.getCharSequence("android.title").toString())
            )

            // Switch language to English and refresh bound service localization
            LocaleHelper.setLanguage(service, LocaleHelper.LANG_EN)
            service.refreshLocalization()

            val enTelemetry = service.telemetry.value
            assertFalse(
                "English telemetry activeLensLabel must not contain Hangul: ${enTelemetry.activeLensLabel}",
                hangulRegex.containsMatchIn(enTelemetry.activeLensLabel)
            )
            assertTrue(
                "Expected English Back lens label, got: ${enTelemetry.activeLensLabel}",
                enTelemetry.activeLensLabel.contains("Back")
            )
            assertFalse(
                "English telemetry statusDetail must not contain Hangul: ${enTelemetry.statusDetail}",
                hangulRegex.containsMatchIn(enTelemetry.statusDetail)
            )

            val enNotif = shadowNm.getNotification(4001)
            val enTitle = enNotif.extras.getCharSequence("android.title")?.toString().orEmpty()
            val enText = enNotif.extras.getCharSequence("android.text")?.toString().orEmpty()
            val enAction = enNotif.actions?.firstOrNull()?.title?.toString().orEmpty()
            assertEquals("Streaming Webcam to Windows 10", enTitle)
            assertFalse("Notification text must not contain Hangul: $enText", hangulRegex.containsMatchIn(enText))
            assertEquals("Stop", enAction)

            // Verify stopping in English leaves localized English IDLE status
            service.stopStreaming()
            assertEquals(StreamConnectionState.IDLE, service.telemetry.value.state)
            assertEquals("Ready (Not Connected)", service.telemetry.value.statusDetail)
        } finally {
            service.stopStreaming()
            controller.destroy()
        }
    }

    @Test
    fun mainActivity_settingsDialogLanguageSwitch_updatesUiAndSpinner() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val btnOpenSettings = activity.findViewById<Button>(R.id.btnOpenSettings)
        val spinnerLanguage = activity.findViewById<Spinner>(R.id.spinnerLanguage)
        val labelEncoder = activity.findViewById<TextView>(R.id.labelEncoder)
        val spinnerEncoder = activity.findViewById<Spinner>(R.id.spinnerEncoder)

        assertEquals(0, spinnerLanguage.selectedItemPosition)
        btnOpenSettings.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val settingsDlg = ShadowAlertDialog.getLatestDialog() as AlertDialog
        settingsDlg.listView.performItemClick(
            settingsDlg.listView.getChildAt(1),
            1,
            settingsDlg.listView.adapter.getItemId(1)
        )
        settingsDlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.getLanguage(activity))
        assertEquals(1, spinnerLanguage.selectedItemPosition)
        assertEquals("Hardware Encoder", labelEncoder.text.toString())
        for (i in 0 until spinnerEncoder.adapter.count) {
            val item = spinnerEncoder.adapter.getItem(i).toString()
            assertFalse("Encoder item $i still has Hangul after Settings switch: $item", hangulRegex.containsMatchIn(item))
        }
    }
}
