package com.win10phonelink.webcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Looper
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.win10phonelink.webcam.camera.CameraStreamEngine
import com.win10phonelink.webcam.codec.VideoEncoderCatalog
import com.win10phonelink.webcam.model.EncoderOption
import com.win10phonelink.webcam.net.PairingPayload
import com.win10phonelink.webcam.net.StreamClient
import org.json.JSONObject
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
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ko-rKR")
class HighBitrateUiAndStreamRoboTest {

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

        val h264Caps = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            MediaCodecInfo.CodecProfileLevel.AVCLevel51
        )
        h264Caps.colorFormats = intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        attachBitrateRange(h264Caps, MediaFormat.MIMETYPE_VIDEO_AVC, "100000-120000000")
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("c2.qti.avc.encoder")
                .setIsEncoder(true)
                .setIsHardwareAccelerated(true)
                .setCapabilities(h264Caps)
                .build()
        )

        val swCaps = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            MediaCodecInfo.CodecProfileLevel.AVCLevel42
        )
        swCaps.colorFormats = intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        attachBitrateRange(swCaps, MediaFormat.MIMETYPE_VIDEO_AVC, "1-50000000", overrideUpper = Int.MAX_VALUE)
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("c2.android.avc.encoder")
                .setIsEncoder(true)
                .setIsHardwareAccelerated(false)
                .setCapabilities(swCaps)
                .build()
        )

        val cm = appCtx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val shadowCm = Shadows.shadowOf(cm) as ShadowCameraManager
        val backChars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowBack = Shadows.shadowOf(backChars)
        shadowBack.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowBack.set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        shadowBack.set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(5.4f))
        shadowCm.addCamera("0", backChars)
    }

    @Test
    fun localizedResources_encoderMaxBitrateStrings() {
        val appCtx = RuntimeEnvironment.getApplication()
        val koRes = LocaleHelper.getLocalizedResources(appCtx, LocaleHelper.LANG_KO)
        val enRes = LocaleHelper.getLocalizedResources(appCtx, LocaleHelper.LANG_EN)

        assertEquals("최대 120 Mbps", koRes.getString(R.string.encoder_detail_max_bitrate, 120))
        val enMax = enRes.getString(R.string.encoder_detail_max_bitrate, 120)
        assertEquals("Max 120 Mbps", enMax)
        assertFalse(hangulRegex.containsMatchIn(enMax))
    }

    @Test
    fun mainActivity_spinnerBitrate_includes60To200MbpsAndDiscoversEncoders() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val spinnerBitrate = activity.findViewById<Spinner>(R.id.spinnerBitrate)
        val items = (0 until spinnerBitrate.adapter.count).map {
            spinnerBitrate.adapter.getItem(it).toString()
        }
        assertEquals(15, items.size)
        for (expected in listOf("60 Mbps", "80 Mbps", "100 Mbps", "120 Mbps", "150 Mbps", "200 Mbps")) {
            assertTrue("Missing bitrate preset $expected in $items", items.contains(expected))
        }
        assertEquals("200 Mbps", items.last())

        val discovered = VideoEncoderCatalog.discoverEncoders(isKorean = false)
        assertTrue(discovered.isNotEmpty())
        val hwEnc = discovered.first { it.codecName == "c2.qti.avc.encoder" }
        val swEnc = discovered.first { it.codecName == "c2.android.avc.encoder" }
        assertEquals(120_000_000, hwEnc.maxBitrateBps)
        assertEquals(0, swEnc.maxBitrateBps)

        val textEncoderDetail = activity.findViewById<TextView>(R.id.textEncoderDetail)
        assertTrue(
            "Expected Korean max bitrate in textEncoderDetail, got: ${textEncoderDetail.text}",
            textEncoderDetail.text.toString().contains("최대 120 Mbps")
        )

        val spinnerEncoder = activity.findViewById<Spinner>(R.id.spinnerEncoder)
        spinnerEncoder.setSelection(1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(
            "Unbounded Int.MAX_VALUE codec must not show Max 2147 Mbps: ${textEncoderDetail.text}",
            textEncoderDetail.text.toString().contains("2147")
        )

        val spinnerLanguage = activity.findViewById<Spinner>(R.id.spinnerLanguage)
        spinnerLanguage.setSelection(1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        spinnerEncoder.setSelection(0)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "Expected English max bitrate in textEncoderDetail, got: ${textEncoderDetail.text}",
            textEncoderDetail.text.toString().contains("Max 120 Mbps")
        )

        activity.findViewById<Button>(R.id.btnSelectHwEncoder).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val hwDlg = ShadowAlertDialog.getLatestDialog() as AlertDialog
        val dlgItem = hwDlg.listView.adapter.getItem(0).toString()
        assertTrue("Expected Max 120 Mbps in HW encoder dialog item: $dlgItem", dlgItem.contains("Max 120 Mbps"))
        hwDlg.dismiss()
    }

    @Test
    fun cameraStreamEngine_buildEncoderFormat_preserves60FpsTiersAndSafeRepeatInterval() {
        val enc = EncoderOption(
            codecName = "c2.qti.avc.encoder",
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            wireCodec = "h264",
            isHardware = true,
            vendorLabel = "Qualcomm",
            supportsCbr = true,
            supportsVbr = true,
            displayName = "",
            supports1080p60 = true,
            maxBitrateBps = 120_000_000
        )

        val full = CameraStreamEngine.buildEncoderFormat(
            enc, 1920, 1080, 60, 100_000_000, CameraStreamEngine.Companion.EncoderFormatTier.FULL
        )
        assertEquals(100_000_000, full.getInteger(MediaFormat.KEY_BIT_RATE))
        assertEquals(60, full.getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertEquals(0, full.getInteger(MediaFormat.KEY_PRIORITY))
        assertEquals(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            full.getInteger(MediaFormat.KEY_BITRATE_MODE)
        )
        assertTrue(
            "Repeat-frame interval must exceed 1 frame period (16667 us) at 60fps",
            full.getLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER) >= 33_333L
        )

        val core60 = CameraStreamEngine.buildEncoderFormat(
            enc, 1920, 1080, 60, 100_000_000, CameraStreamEngine.Companion.EncoderFormatTier.CORE_60FPS
        )
        assertEquals(60, core60.getInteger(MediaFormat.KEY_OPERATING_RATE))
        assertEquals(0, core60.getInteger(MediaFormat.KEY_PRIORITY))
        assertFalse(core60.containsKey(MediaFormat.KEY_LOW_LATENCY))
        assertFalse(core60.containsKey(MediaFormat.KEY_MAX_B_FRAMES))
        assertFalse(core60.containsKey(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER))

        val minimal = CameraStreamEngine.buildEncoderFormat(
            enc, 1920, 1080, 60, 100_000_000, CameraStreamEngine.Companion.EncoderFormatTier.MINIMAL
        )
        assertEquals(100_000_000, minimal.getInteger(MediaFormat.KEY_BIT_RATE))
        assertFalse(minimal.containsKey(MediaFormat.KEY_OPERATING_RATE))
        assertFalse(minimal.containsKey(MediaFormat.KEY_BITRATE_MODE))
    }

    @Test
    fun streamClient_loopbackDeliversHighBitrateBurstWithoutSyncStorm() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
            soTimeout = 5_000
        }
        val receivedFrames = AtomicInteger(0)
        val receivedConfig = AtomicInteger(0)
        val handshakeCodec = AtomicReference<String>("")
        val serverError = AtomicReference<Throwable?>(null)

        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    sock.soTimeout = 5_000
                    val dis = DataInputStream(sock.getInputStream())
                    val magic = ByteArray(4)
                    dis.readFully(magic)
                    assertEquals("PLWC", String(magic, Charsets.US_ASCII))
                    val jsonLen = dis.readInt()
                    val jsonBytes = ByteArray(jsonLen)
                    dis.readFully(jsonBytes)
                    val handshake = JSONObject(String(jsonBytes, Charsets.UTF_8))
                    handshakeCodec.set(handshake.getString("codec"))

                    sock.getOutputStream().apply {
                        write("OK\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }

                    val header = ByteArray(16)
                    repeat(7) {
                        dis.readFully(header)
                        val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                        val type = bb.get()
                        val flags = bb.get().toInt() and 0xFF
                        bb.short
                        val payloadLen = bb.int
                        val ptsUs = bb.long
                        assertTrue("Invalid payloadLen: $payloadLen", payloadLen in 1..16_777_216)
                        assertTrue("Expected positive ptsUs: $ptsUs", ptsUs > 0L)
                        val payload = ByteArray(payloadLen)
                        dis.readFully(payload)
                        assertEquals(StreamClient.PACKET_TYPE_VIDEO, type)
                        if ((flags and StreamClient.FLAG_CODEC_CONFIG) != 0) {
                            receivedConfig.incrementAndGet()
                        } else {
                            receivedFrames.incrementAndGet()
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }
        serverThread.start()

        val connected = CountDownLatch(1)
        val syncRequests = AtomicInteger(0)
        val client = StreamClient(
            pairing = PairingPayload(1, "127.0.0.1", server.localPort, "test-token", "PC"),
            codecWireName = "h264",
            encoderName = "c2.qti.avc.encoder",
            width = 1920,
            height = 1080,
            fps = 60,
            onConnected = { connected.countDown() },
            onDisconnected = {},
            onNeedSyncFrame = { syncRequests.incrementAndGet() },
            onStatsSample = { _, _, _, _ -> }
        )

        try {
            client.start()
            assertTrue("Client failed to connect", connected.await(5, TimeUnit.SECONDS))

            val chunk = ByteArray(64 * 1024) { 0x2A }
            client.sendVideoFrame(isKeyframe = false, isCodecConfig = true, rotationDegrees = 0, ptsUs = 1000L, payload = byteArrayOf(0, 0, 0, 1, 0x67))
            client.sendVideoFrame(isKeyframe = true, isCodecConfig = false, rotationDegrees = 0, ptsUs = 2000L, payload = chunk)
            for (i in 1..5) {
                client.sendVideoFrame(isKeyframe = false, isCodecConfig = false, rotationDegrees = 0, ptsUs = 2000L + i * 16_666L, payload = chunk)
            }

            serverThread.join(5_000)
            assertEquals(null, serverError.get())
            assertEquals("h264", handshakeCodec.get())
            assertEquals(1, receivedConfig.get())
            assertEquals(6, receivedFrames.get())
            assertEquals(0, syncRequests.get())
        } finally {
            client.stop()
            runCatching { server.close() }
        }
    }

    @Test
    fun streamClient_congestionDropsCoalesceSyncAndRecoveryKeyframeUnblocksSubsequentPFrames() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
            receiveBufferSize = 4096
            soTimeout = 5_000
        }
        val handshakeDone = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val receivedPts = CopyOnWriteArrayList<Long>()
        val statsSamples = CopyOnWriteArrayList<Double>()
        val serverDone = CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    val dis = DataInputStream(sock.getInputStream())
                    val magic = ByteArray(4)
                    dis.readFully(magic)
                    val len = dis.readInt()
                    dis.readFully(ByteArray(len))
                    sock.getOutputStream().apply {
                        write("OK\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    handshakeDone.countDown()
                    // Hold socket unread to force client queue saturation, then drain.
                    releaseRead.await(5, TimeUnit.SECONDS)
                    val header = ByteArray(16)
                    while (true) {
                        try {
                            dis.readFully(header)
                            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                            val type = bb.get()
                            bb.get()
                            bb.short
                            val payloadLen = bb.int
                            val ptsUs = bb.long
                            if (payloadLen > 0) dis.readFully(ByteArray(payloadLen))
                            if (type == StreamClient.PACKET_TYPE_VIDEO) {
                                receivedPts.add(ptsUs)
                            }
                        } catch (_: Throwable) {
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                serverDone.countDown()
            }
        }
        serverThread.start()

        val connected = CountDownLatch(1)
        val syncRequests = AtomicInteger(0)
        val client = StreamClient(
            pairing = PairingPayload(1, "127.0.0.1", server.localPort, "test-token", "PC"),
            codecWireName = "h264",
            encoderName = "c2.qti.avc.encoder",
            width = 1920,
            height = 1080,
            fps = 60,
            onConnected = { connected.countDown() },
            onDisconnected = {},
            onNeedSyncFrame = { syncRequests.incrementAndGet() },
            onStatsSample = { fps, _, _, _ -> statsSamples.add(fps) }
        )

        try {
            client.start()
            assertTrue("Handshake failed", handshakeDone.await(5, TimeUnit.SECONDS))
            assertTrue("Client failed to connect", connected.await(5, TimeUnit.SECONDS))

            val payload = ByteArray(128 * 1024) { 0x11 }
            // Burst 400 non-critical P-frames under stall to trigger queue overflow + P-frame drops.
            for (i in 0 until 400) {
                client.sendVideoFrame(false, false, 0, (i + 1) * 16_666L, payload)
            }
            // Send recovery keyframe (carrying prepended config flags) followed by a dependent P-frame.
            val recoveryKeyPts = 999_000_000L
            val postRecoveryPFramePts = 999_016_666L
            client.sendVideoFrame(true, true, 0, recoveryKeyPts, payload)
            client.sendVideoFrame(false, false, 0, postRecoveryPFramePts, payload)

            assertTrue(
                "Expected sync requests to be triggered once/coalesced, got ${syncRequests.get()}",
                syncRequests.get() in 1..2
            )

            releaseRead.countDown()
            val deadline = System.currentTimeMillis() + 4_000L
            while (System.currentTimeMillis() < deadline && !receivedPts.contains(postRecoveryPFramePts)) {
                Thread.sleep(20)
            }
            assertTrue(
                "Recovery keyframe should reach server, received=${receivedPts.size}",
                receivedPts.contains(recoveryKeyPts)
            )
            assertTrue(
                "P-frame immediately following recovery keyframe must not be dropped, receivedPts=$receivedPts",
                receivedPts.contains(postRecoveryPFramePts)
            )
        } finally {
            releaseRead.countDown()
            client.stop()
            runCatching { server.close() }
            serverDone.await(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun streamClient_statsSampleCountsKeyframesWithConfigAndReflectsFlushDuration() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
            soTimeout = 5_000
        }
        val stopServer = AtomicBoolean(false)
        val serverThread = Thread {
            try {
                server.accept().use { sock ->
                    val dis = DataInputStream(sock.getInputStream())
                    dis.readFully(ByteArray(4))
                    val len = dis.readInt()
                    dis.readFully(ByteArray(len))
                    sock.getOutputStream().apply {
                        write("OK\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    val header = ByteArray(16)
                    while (!stopServer.get()) {
                        try {
                            dis.readFully(header)
                            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                            bb.get()
                            bb.get()
                            bb.short
                            val payloadLen = bb.int
                            bb.long
                            if (payloadLen > 0) dis.readFully(ByteArray(payloadLen))
                        } catch (_: Throwable) {
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        serverThread.start()

        val connected = CountDownLatch(1)
        val sampleGot = CountDownLatch(1)
        val sampledFps = AtomicReference(0.0)
        val sampledKbps = AtomicReference(0.0)
        val sampledSent = AtomicReference(0L)
        val client = StreamClient(
            pairing = PairingPayload(1, "127.0.0.1", server.localPort, "test-token", "PC"),
            codecWireName = "h264",
            encoderName = "c2.qti.avc.encoder",
            width = 1920,
            height = 1080,
            fps = 60,
            onConnected = { connected.countDown() },
            onDisconnected = {},
            onNeedSyncFrame = {},
            onStatsSample = { fps, kbps, sentTotal, _ ->
                if (sentTotal >= 4L) {
                    sampledFps.set(fps)
                    sampledKbps.set(kbps)
                    sampledSent.set(sentTotal)
                    sampleGot.countDown()
                }
            }
        )

        try {
            client.start()
            assertTrue("Client failed to connect", connected.await(5, TimeUnit.SECONDS))

            // 1 standalone config + 2 keyframes with prepended config + 2 P-frames = 4 video frames, 5 packets.
            val frameBytes = ByteArray(32 * 1024) { 0x33 }
            client.sendVideoFrame(false, true, 0, 100L, byteArrayOf(0, 0, 0, 1, 0x67))
            client.sendVideoFrame(true, true, 0, 1000L, frameBytes)
            client.sendVideoFrame(false, false, 0, 2000L, frameBytes)
            Thread.sleep(1100)
            client.sendVideoFrame(true, true, 0, 3000L, frameBytes)
            client.sendVideoFrame(false, false, 0, 4000L, frameBytes)

            assertTrue("Expected onStatsSample callback", sampleGot.await(5, TimeUnit.SECONDS))
            assertTrue("Expected positive measured fps including keyframes, got ${sampledFps.get()}", sampledFps.get() > 0.5)
            assertTrue("Expected positive measured kbps, got ${sampledKbps.get()}", sampledKbps.get() > 100.0)
            assertTrue("Expected >= 4 packets sent, got ${sampledSent.get()}", sampledSent.get() >= 4L)
        } finally {
            stopServer.set(true)
            client.stop()
            runCatching { server.close() }
            serverThread.join(2_000)
        }
    }

    private fun attachBitrateRange(
        caps: MediaCodecInfo.CodecCapabilities,
        mime: String,
        bitrateRangeSpec: String,
        overrideUpper: Int? = null
    ) {
        val format = MediaFormat.createVideoFormat(mime, 1920, 1080).apply {
            setString("bitrate-range", bitrateRangeSpec)
            setString("size-range", "176x144-1920x1080")
            setString("frame-rate-range", "1-60")
        }
        val videoCapsCls = MediaCodecInfo.VideoCapabilities::class.java
        val createMethod = videoCapsCls.getDeclaredMethod(
            "create",
            MediaFormat::class.java,
            MediaCodecInfo.CodecCapabilities::class.java
        )
        createMethod.isAccessible = true
        val videoCaps = createMethod.invoke(null, format, caps)
        if (overrideUpper != null) {
            val rangeField = videoCapsCls.getDeclaredField("mBitrateRange")
            rangeField.isAccessible = true
            rangeField.set(videoCaps, android.util.Range(1, overrideUpper))
        }
        val field = MediaCodecInfo.CodecCapabilities::class.java.getDeclaredField("mVideoCaps")
        field.isAccessible = true
        field.set(caps, videoCaps)
    }
}
