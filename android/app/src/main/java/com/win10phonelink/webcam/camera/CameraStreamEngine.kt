package com.win10phonelink.webcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import com.win10phonelink.webcam.model.CameraLensOption
import com.win10phonelink.webcam.model.EncoderOption
import com.win10phonelink.webcam.model.RotationMode
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamEngine(private val context: Context) {

    interface Callback {
        fun onEncodedPacket(
            isKeyframe: Boolean,
            isCodecConfig: Boolean,
            rotationDegrees: Int,
            ptsUs: Long,
            payload: ByteArray
        )

        fun onRotationChanged(rotationDegrees: Int, cameraWireName: String)
        fun onError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val cameraOpenCloseLock = Semaphore(1)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var bgExecutor: Executor? = null

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var drainThread: Thread? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var currentLens: CameraLensOption? = null

    @Volatile
    private var currentRotationMode: RotationMode = RotationMode.AUTO

    @Volatile
    private var deviceOrientationDeg: Int = 0

    @Volatile
    private var currentRotationDegrees: Int = 0

    @Volatile
    private var cachedCodecConfig: ByteArray? = null

    @Volatile
    private var activePhysicalCameraId: String? = null

    private var targetWidth: Int = 1280
    private var targetHeight: Int = 720
    private var targetFps: Int = 60
    private var callback: Callback? = null
    private var orientationListener: OrientationEventListener? = null

    fun getCurrentRotationDegrees(): Int = currentRotationDegrees

    fun getCurrentCameraWireName(): String = currentLens?.wireName ?: "back"

    fun getCachedCodecConfig(): ByteArray? = cachedCodecConfig?.copyOf()

    fun start(
        lens: CameraLensOption,
        encoder: EncoderOption,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        rotationMode: RotationMode,
        initialPreviewSurface: Surface?,
        cb: Callback
    ) {
        if (!running.compareAndSet(false, true)) {
            stop()
            running.set(true)
        }

        this.callback = cb
        this.currentLens = lens
        this.currentRotationMode = rotationMode
        this.previewSurface = initialPreviewSurface
        this.targetWidth = width
        this.targetHeight = height
        this.targetFps = fps
        this.activePhysicalCameraId = null
        this.cachedCodecConfig = null

        val thread = HandlerThread("CameraStreamBg").apply { start() }
        val handler = Handler(thread.looper)
        bgThread = thread
        bgHandler = handler
        bgExecutor = Executor { command -> handler.post(command) }

        currentRotationDegrees = computeRotationDegrees(lens, deviceOrientationDeg, rotationMode)
        setupOrientationListener()

        try {
            setupEncoder(encoder, width, height, fps, bitrateBps)
        } catch (t: Throwable) {
            stop()
            cb.onError("Failed to configure encoder ${encoder.codecName}: ${t.message}")
            return
        }

        startDrainLoop()
        handler.post { openCameraInternal(lens, allowPhysicalRetry = true) }
    }

    fun attachPreviewSurface(surface: Surface?) {
        previewSurface = surface
        val handler = bgHandler ?: return
        if (surface == null) {
            val done = Semaphore(0)
            handler.post {
                if (running.get()) {
                    val cam = cameraDevice
                    val lens = currentLens
                    if (cam != null && lens != null) {
                        reconfigureSession(cam, lens, usePhysicalId = lens.physicalCameraId != null)
                    }
                }
                done.release()
            }
            try {
                done.tryAcquire(350L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        } else {
            handler.post {
                if (!running.get()) return@post
                val cam = cameraDevice ?: return@post
                val lens = currentLens ?: return@post
                reconfigureSession(cam, lens, usePhysicalId = lens.physicalCameraId != null)
            }
        }
    }

    fun switchLens(newLens: CameraLensOption) {
        if (!running.get()) return
        currentLens = newLens
        updateComputedRotation(notify = true)
        val handler = bgHandler ?: return
        handler.post {
            if (!running.get()) return@post
            closeCameraOnly()
            openCameraInternal(newLens, allowPhysicalRetry = true)
            handler.postDelayed({ requestSyncFrame() }, 200L)
        }
    }

    fun setRotationMode(mode: RotationMode) {
        currentRotationMode = mode
        updateComputedRotation(notify = true)
    }

    fun requestSyncFrame() {
        val codec = mediaCodec ?: return
        if (!running.get()) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        orientationListener?.disable()
        orientationListener = null

        val thread = bgThread
        val handler = bgHandler
        if (handler != null && thread != null && thread.isAlive) {
            val done = Semaphore(0)
            handler.post {
                closeCameraOnly()
                done.release()
            }
            try {
                done.tryAcquire(1500L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        } else {
            closeCameraOnly()
        }

        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (_: Exception) {
        }

        drainThread?.let { t ->
            try {
                t.join(1000L)
            } catch (_: InterruptedException) {
            }
        }
        drainThread = null

        try {
            mediaCodec?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Exception) {
        }
        mediaCodec = null

        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null

        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        bgExecutor = null
        callback = null
    }

    private fun setupOrientationListener() {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val snapped = ((orientation + 45) / 90 * 90) % 360
                if (snapped != deviceOrientationDeg) {
                    deviceOrientationDeg = snapped
                    updateComputedRotation(notify = true)
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            orientationListener = listener
        }
    }

    private fun updateComputedRotation(notify: Boolean) {
        val lens = currentLens ?: return
        val nextRot = computeRotationDegrees(lens, deviceOrientationDeg, currentRotationMode)
        if (nextRot != currentRotationDegrees) {
            currentRotationDegrees = nextRot
            if (notify) {
                callback?.onRotationChanged(nextRot, lens.wireName)
            }
        }
    }

    private fun computeRotationDegrees(
        lens: CameraLensOption,
        deviceDeg: Int,
        mode: RotationMode
    ): Int {
        mode.fixedDegrees?.let { return it }
        return if (lens.isFrontFacing) {
            (lens.sensorOrientation - deviceDeg + 360) % 360
        } else {
            (lens.sensorOrientation + deviceDeg) % 360
        }
    }

    private fun setupEncoder(
        encoder: EncoderOption,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int
    ) {
        val codec = MediaCodec.createByCodecName(encoder.codecName)
        mediaCodec = codec

        val format = MediaFormat.createVideoFormat(encoder.mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps.coerceAtLeast(1))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (fps >= 60 && encoder.supportsVbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            } else if (encoder.supportsCbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            } else if (encoder.supportsVbr) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            if (encoder.isHardware) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, maxOf(fps, 60))
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            val minimal = MediaFormat.createVideoFormat(encoder.mimeType, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(minimal, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        encoderSurface = codec.createInputSurface()
        codec.start()
    }

    private fun startDrainLoop() {
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                val codec = mediaCodec ?: break
                val outIdx = try {
                    codec.dequeueOutputBuffer(info, 10_000L)
                } catch (_: IllegalStateException) {
                    break
                }

                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChanged(codec.outputFormat)
                    continue
                } else if (outIdx < 0) {
                    continue
                }

                val outBuf = try {
                    codec.getOutputBuffer(outIdx)
                } catch (_: Exception) {
                    null
                }

                val bytes = if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    ByteArray(info.size).also { outBuf.get(it) }
                } else {
                    ByteArray(0)
                }

                try {
                    codec.releaseOutputBuffer(outIdx, false)
                } catch (_: Exception) {
                }

                if (bytes.isNotEmpty()) {
                    processEncodedOutput(info, bytes)
                }

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }, "MediaCodecDrain").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleOutputFormatChanged(format: MediaFormat) {
        if (cachedCodecConfig != null) return
        val csd0 = format.getByteBuffer("csd-0") ?: return
        val csd1 = format.getByteBuffer("csd-1")
        val combined = concatByteBuffers(csd0, csd1)
        if (combined.isNotEmpty()) {
            val annexB = ensureAnnexB(combined)
            cachedCodecConfig = annexB
            callback?.onEncodedPacket(
                isKeyframe = false,
                isCodecConfig = true,
                rotationDegrees = currentRotationDegrees,
                ptsUs = 0L,
                payload = annexB
            )
        }
    }

    private fun processEncodedOutput(info: MediaCodec.BufferInfo, rawBytes: ByteArray) {
        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        val isKeyframe = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val annexB = ensureAnnexB(rawBytes)

        if (isConfig) {
            cachedCodecConfig = annexB
            callback?.onEncodedPacket(
                isKeyframe = false,
                isCodecConfig = true,
                rotationDegrees = currentRotationDegrees,
                ptsUs = info.presentationTimeUs,
                payload = annexB
            )
            return
        }

        var payload = annexB
        var hasConfig = false
        if (isKeyframe) {
            val cfg = cachedCodecConfig
            if (cfg != null && !startsWithBytes(payload, cfg)) {
                payload = cfg + payload
                hasConfig = true
            }
        }

        callback?.onEncodedPacket(
            isKeyframe = isKeyframe,
            isCodecConfig = hasConfig,
            rotationDegrees = currentRotationDegrees,
            ptsUs = info.presentationTimeUs,
            payload = payload
        )
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(lens: CameraLensOption, allowPhysicalRetry: Boolean) {
        if (!running.get()) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val handler = bgHandler ?: return

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500L, TimeUnit.MILLISECONDS)) {
                callback?.onError("Timed out acquiring camera lock")
                return
            }
        } catch (_: InterruptedException) {
            return
        }

        try {
            manager.openCamera(
                lens.logicalCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        if (!running.get()) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        reconfigureSession(
                            camera = camera,
                            lens = lens,
                            usePhysicalId = lens.physicalCameraId != null && allowPhysicalRetry
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        if (running.get()) {
                            callback?.onError("Camera2 error ($error) on lens ${lens.label}")
                        }
                    }
                },
                handler
            )
        } catch (e: SecurityException) {
            cameraOpenCloseLock.release()
            callback?.onError("Missing CAMERA permission: ${e.message}")
        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            callback?.onError("Camera access failed: ${e.message}")
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            callback?.onError("Failed to open camera: ${e.message}")
        }
    }

    private fun reconfigureSession(
        camera: CameraDevice,
        lens: CameraLensOption,
        usePhysicalId: Boolean
    ) {
        val encSurface = encoderSurface ?: return
        val executor = bgExecutor ?: return
        val handler = bgHandler ?: return

        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val canUsePhys = usePhysicalId &&
            lens.physicalCameraId != null &&
            supportsPhysical60Fps(manager, lens.physicalCameraId, targetWidth, targetHeight, targetFps)
        val physId = if (canUsePhys) lens.physicalCameraId else null
        activePhysicalCameraId = physId
        val outputs = mutableListOf<OutputConfiguration>()

        outputs.add(
            OutputConfiguration(encSurface).apply {
                if (physId != null) setPhysicalCameraId(physId)
            }
        )

        val activePreview = previewSurface?.takeIf { it.isValid }
        if (activePreview != null) {
            outputs.add(
                OutputConfiguration(activePreview).apply {
                    if (physId != null) setPhysicalCameraId(physId)
                }
            )
        }

        try {
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get() || cameraDevice !== camera) {
                            try {
                                session.close()
                            } catch (_: Exception) {
                            }
                            return
                        }
                        captureSession = session
                        startRepeatingCapture(camera, session, lens, encSurface, activePreview, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (!running.get()) return
                        if (usePhysicalId && lens.physicalCameraId != null) {
                            // Some Samsung HAL builds reject dual-surface physical routing; fallback to logical
                            // camera (with 0.6x zoom ratio if ultra-wide on API 30+).
                            reconfigureSession(camera, lens, usePhysicalId = false)
                        } else {
                            callback?.onError("Camera2 CaptureSession configuration failed for ${lens.label}")
                        }
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            callback?.onError("Failed to create CaptureSession: ${e.message}")
        }
    }

    private fun supportsPhysical60Fps(
        manager: CameraManager,
        physicalId: String,
        width: Int,
        height: Int,
        desiredFps: Int
    ): Boolean {
        if (desiredFps < 60) return true
        return try {
            val chars = manager.getCameraCharacteristics(physicalId)
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val hasHighFpsRange = ranges?.any { it.upper >= desiredFps } == true
            if (!hasHighFpsRange) return false

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
            val minDurationNs = map.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(width, height))
            minDurationNs in 1L..18_000_000L
        } catch (_: Exception) {
            false
        }
    }

    private fun startRepeatingCapture(
        camera: CameraDevice,
        session: CameraCaptureSession,
        lens: CameraLensOption,
        encSurface: Surface,
        activePreview: Surface?,
        handler: Handler
    ) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val activeCamId = activePhysicalCameraId ?: lens.logicalCameraId
            val chars = try {
                manager.getCameraCharacteristics(activeCamId)
            } catch (_: Exception) {
                manager.getCameraCharacteristics(lens.logicalCameraId)
            }
            val fpsRange = pickBestFpsRange(chars, targetFps)
            val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(encSurface)
                if (activePreview != null && activePreview.isValid) {
                    addTarget(activePreview)
                }
                set(
                    CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                )
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                if (fpsRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }
                if (targetFps >= 60) {
                    set(
                        CaptureRequest.SENSOR_FRAME_DURATION,
                        1_000_000_000L / targetFps.coerceAtLeast(1)
                    )
                }
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                // On many Android devices, EIS crop/processing in TEMPLATE_RECORD can cap indoor FPS below 60.
                if (targetFps <= 30 && stabModes != null &&
                    stabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                ) {
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    )
                } else if (stabModes != null &&
                    stabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                ) {
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (activePhysicalCameraId == null || lens.zoomRatio != null)
                ) {
                    val zoom = lens.zoomRatio ?: if (lens.wireName == "ultrawide") {
                        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        if (range != null && range.lower <= 0.65f) maxOf(range.lower, 0.6f) else null
                    } else null
                    if (zoom != null) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    }
                }
            }

            session.setRepeatingRequest(builder.build(), null, handler)
            handler.postDelayed({ requestSyncFrame() }, 150L)
        } catch (e: Exception) {
            callback?.onError("Failed to start repeating capture: ${e.message}")
        }
    }

    private fun closeCameraOnly() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
    }

    private fun pickBestFpsRange(chars: CameraCharacteristics, desiredFps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }
        ranges.filter { it.upper == desiredFps }.maxByOrNull { it.lower }?.let { return it }
        return ranges.minByOrNull { kotlin.math.abs(it.upper - desiredFps) }
    }

    private fun concatByteBuffers(first: ByteBuffer, second: ByteBuffer?): ByteArray {
        val fDup = first.duplicate()
        val fBytes = ByteArray(fDup.remaining()).also { fDup.get(it) }
        if (second == null) return fBytes
        val sDup = second.duplicate()
        val sBytes = ByteArray(sDup.remaining()).also { sDup.get(it) }
        return fBytes + sBytes
    }

    private fun ensureAnnexB(data: ByteArray): ByteArray {
        val has4ByteStart = data.size >= 4 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        val has3ByteStart = data.size >= 3 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()
        if (has4ByteStart || has3ByteStart) return data
        return byteArrayOf(0, 0, 0, 1) + data
    }

    private fun startsWithBytes(haystack: ByteArray, prefix: ByteArray): Boolean {
        if (haystack.size < prefix.size) return false
        for (i in prefix.indices) {
            if (haystack[i] != prefix[i]) return false
        }
        return true
    }
}
