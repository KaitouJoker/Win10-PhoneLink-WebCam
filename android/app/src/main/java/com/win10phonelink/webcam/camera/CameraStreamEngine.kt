package com.win10phonelink.webcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
        fun onCaptureModeChanged(modeLabel: String)
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

    @Volatile
    private var activeCameraId: String = "0"

    @Volatile
    private var activeSessionIsHighSpeed: Boolean = false

    @Volatile
    private var manual60Active: Boolean = false

    @Volatile
    private var manual60Blocked: Boolean = false

    @Volatile
    private var sessionGeneration: Int = 0

    private var regularSettledFrames: Int = 0
    private var manual60ProbeFrames: Int = 0
    private var lastCaptureTimestampNs: Long = 0L
    private var emaFrameDurationNs: Long = 0L
    private var lastAeExposureNs: Long = 0L
    private var lastAeIso: Int = 0
    private var lastReportedModeLabel: String = ""

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
        resetCaptureFpsState(resetBlocked = true)

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
            resetCaptureFpsState(resetBlocked = true)
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

        val exactFormat = buildEncoderFormat(
            encoder = encoder,
            width = width,
            height = height,
            fps = fps,
            targetBitrateBps = bitrateBps,
            tier = EncoderFormatTier.FULL
        )
        if (!tryConfigureEncoder(codec, exactFormat)) {
            val clampedBitrateBps = clampBitrateForCodec(
                codec = codec,
                mimeType = encoder.mimeType,
                requestedBps = bitrateBps,
                fallbackMaxBps = encoder.maxBitrateBps
            )
            val fallbackWithFullFlags = clampedBitrateBps != bitrateBps &&
                tryConfigureEncoder(
                    codec,
                    buildEncoderFormat(
                        encoder = encoder,
                        width = width,
                        height = height,
                        fps = fps,
                        targetBitrateBps = clampedBitrateBps,
                        tier = EncoderFormatTier.FULL
                    )
                )
            val fallbackCore60Fps = !fallbackWithFullFlags &&
                tryConfigureEncoder(
                    codec,
                    buildEncoderFormat(
                        encoder = encoder,
                        width = width,
                        height = height,
                        fps = fps,
                        targetBitrateBps = clampedBitrateBps,
                        tier = EncoderFormatTier.CORE_60FPS
                    )
                )
            if (!fallbackWithFullFlags && !fallbackCore60Fps) {
                val minimalFormat = buildEncoderFormat(
                    encoder = encoder,
                    width = width,
                    height = height,
                    fps = fps,
                    targetBitrateBps = clampedBitrateBps,
                    tier = EncoderFormatTier.MINIMAL
                )
                codec.configure(minimalFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }

        encoderSurface = codec.createInputSurface()
        codec.start()
    }

    private fun tryConfigureEncoder(codec: MediaCodec, format: MediaFormat): Boolean {
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            true
        } catch (_: Exception) {
            // Reset failed state before retrying with clamped bitrate or reduced optional flags.
            runCatching { codec.reset() }
            false
        }
    }

    private fun clampBitrateForCodec(
        codec: MediaCodec,
        mimeType: String,
        requestedBps: Int,
        fallbackMaxBps: Int = 0
    ): Int {
        val range = runCatching {
            codec.codecInfo.getCapabilitiesForType(mimeType)?.videoCapabilities?.bitrateRange
        }.getOrNull()
        val upper = range?.upper ?: fallbackMaxBps.takeIf { it > 0 }
        return clampBitrateForRange(requestedBps, range?.lower, upper)
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
        usePhysicalId: Boolean,
        allowHighSpeed: Boolean = true
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
        val activeCamId = physId ?: lens.logicalCameraId
        activeCameraId = activeCamId

        val activePreview = previewSurface?.takeIf { it.isValid }
        val chars = runCatching { manager.getCameraCharacteristics(activeCamId) }.getOrNull()
            ?: runCatching { manager.getCameraCharacteristics(lens.logicalCameraId) }.getOrNull()
        val hsRange = if (allowHighSpeed && physId == null && targetFps >= 60 && chars != null) {
            findConstrainedHighSpeedRange(chars, targetWidth, targetHeight, targetFps)
        } else {
            null
        }
        val useHighSpeed = hsRange != null
        activeSessionIsHighSpeed = useHighSpeed
        resetCaptureFpsState(resetBlocked = false)
        val gen = ++sessionGeneration

        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(
            OutputConfiguration(encSurface).apply {
                if (physId != null) setPhysicalCameraId(physId)
            }
        )
        if (activePreview != null) {
            outputs.add(
                OutputConfiguration(activePreview).apply {
                    if (physId != null) setPhysicalCameraId(physId)
                }
            )
        }

        val sessionType = if (useHighSpeed) {
            SessionConfiguration.SESSION_HIGH_SPEED
        } else {
            SessionConfiguration.SESSION_REGULAR
        }

        try {
            val sessionConfig = SessionConfiguration(
                sessionType,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get() || cameraDevice !== camera || gen != sessionGeneration) {
                            runCatching { session.close() }
                            return
                        }
                        captureSession = session
                        val started = startRepeatingCapture(
                            camera = camera,
                            session = session,
                            lens = lens,
                            encSurface = encSurface,
                            activePreview = activePreview,
                            handler = handler,
                            highSpeedRange = hsRange,
                            expectedGen = gen
                        )
                        if (!started && useHighSpeed && running.get() && cameraDevice === camera) {
                            reconfigureSession(camera, lens, usePhysicalId = false, allowHighSpeed = false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (!running.get() || gen != sessionGeneration) return
                        if (useHighSpeed) {
                            reconfigureSession(camera, lens, usePhysicalId = canUsePhys, allowHighSpeed = false)
                        } else if (usePhysicalId && lens.physicalCameraId != null) {
                            // Some Samsung HAL builds reject dual-surface physical routing; fallback to logical
                            // camera (with 0.6x zoom ratio if ultra-wide on API 30+).
                            reconfigureSession(camera, lens, usePhysicalId = false, allowHighSpeed = true)
                        } else {
                            callback?.onError("Camera2 CaptureSession configuration failed for ${lens.label}")
                        }
                    }
                }
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            if (useHighSpeed) {
                reconfigureSession(camera, lens, usePhysicalId = canUsePhys, allowHighSpeed = false)
            } else {
                callback?.onError("Failed to create CaptureSession: ${e.message}")
            }
        }
    }

    private fun resetCaptureFpsState(resetBlocked: Boolean) {
        manual60Active = false
        if (resetBlocked) {
            manual60Blocked = false
        }
        regularSettledFrames = 0
        manual60ProbeFrames = 0
        lastCaptureTimestampNs = 0L
        emaFrameDurationNs = 0L
        lastAeExposureNs = 0L
        lastAeIso = 0
        lastReportedModeLabel = ""
    }

    private fun reportCaptureMode(modeLabel: String) {
        if (modeLabel != lastReportedModeLabel) {
            lastReportedModeLabel = modeLabel
            callback?.onCaptureModeChanged(modeLabel)
        }
    }

    private fun findConstrainedHighSpeedRange(
        chars: CameraCharacteristics,
        width: Int,
        height: Int,
        desiredFps: Int
    ): Range<Int>? {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
            return null
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val targetSize = Size(width, height)
        val hsSizes = map.highSpeedVideoSizes ?: return null
        if (hsSizes.none { it.width == targetSize.width && it.height == targetSize.height }) {
            return null
        }
        return pickBestHighSpeedFpsRange(map.getHighSpeedVideoFpsRangesFor(targetSize), desiredFps)
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
            val targetSize = Size(width, height)
            val codecMin = runCatching {
                map.getOutputMinFrameDuration(MediaCodec::class.java, targetSize)
            }.getOrDefault(0L)
            val texMin = runCatching {
                map.getOutputMinFrameDuration(SurfaceTexture::class.java, targetSize)
            }.getOrDefault(0L)
            val minDurationNs = listOf(codecMin, texMin).filter { it > 0L }.minOrNull() ?: 0L
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
        handler: Handler,
        highSpeedRange: Range<Int>?,
        expectedGen: Int
    ): Boolean {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = try {
                manager.getCameraCharacteristics(activeCameraId)
            } catch (_: Exception) {
                manager.getCameraCharacteristics(lens.logicalCameraId)
            }
            val captureCb = createCaptureMonitorCallback(
                camera = camera,
                session = session,
                chars = chars,
                lens = lens,
                encSurface = encSurface,
                activePreview = activePreview,
                handler = handler,
                highSpeedRange = highSpeedRange,
                expectedGen = expectedGen
            )

            if (highSpeedRange != null && session is CameraConstrainedHighSpeedCaptureSession) {
                val builder = buildHighSpeedRequestBuilder(camera, chars, lens, encSurface, activePreview, highSpeedRange)
                val reqList = session.createHighSpeedRequestList(builder.build())
                session.setRepeatingBurst(reqList, captureCb, handler)
                reportCaptureMode("HS[${highSpeedRange.lower}-${highSpeedRange.upper}]")
                handler.postDelayed({ requestSyncFrame() }, 150L)
                return true
            }

            val builder = buildRegularRequestBuilder(camera, chars, lens, encSurface, activePreview)
            session.setRepeatingRequest(builder.build(), captureCb, handler)
            handler.postDelayed({ requestSyncFrame() }, 150L)
            true
        } catch (e: Exception) {
            if (highSpeedRange == null) {
                callback?.onError("Failed to start repeating capture: ${e.message}")
            }
            false
        }
    }

    private fun buildHighSpeedRequestBuilder(
        camera: CameraDevice,
        chars: CameraCharacteristics,
        lens: CameraLensOption,
        encSurface: Surface,
        activePreview: Surface?,
        hsRange: Range<Int>
    ): CaptureRequest.Builder {
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encSurface)
            if (activePreview != null && activePreview.isValid) {
                addTarget(activePreview)
            }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, hsRange)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            applyZoomRatioIfNeeded(this, chars, lens)
        }
    }

    private fun buildRegularRequestBuilder(
        camera: CameraDevice,
        chars: CameraCharacteristics,
        lens: CameraLensOption,
        encSurface: Surface,
        activePreview: Surface?
    ): CaptureRequest.Builder {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encSurface)
            if (activePreview != null && activePreview.isValid) {
                addTarget(activePreview)
            }
            set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

            val stabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            if (targetFps <= 30 && stabModes != null &&
                stabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            ) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else if (stabModes != null &&
                stabModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            ) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }

            if (targetFps >= 60) {
                selectFastestControlMode(
                    chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    CaptureRequest.EDGE_MODE_FAST,
                    CaptureRequest.EDGE_MODE_OFF
                )?.let { set(CaptureRequest.EDGE_MODE, it) }
                selectFastestControlMode(
                    chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                )?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
                selectFastestControlMode(
                    chars.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES),
                    CaptureRequest.HOT_PIXEL_MODE_FAST,
                    CaptureRequest.HOT_PIXEL_MODE_OFF
                )?.let { set(CaptureRequest.HOT_PIXEL_MODE, it) }
                selectFastestControlMode(
                    chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                )?.let { set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
                selectFastestControlMode(
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                )?.let { set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }
            }

            val manualParams = if (manual60Active) buildManual60Params(chars) else null
            if (manualParams != null) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_FRAME_DURATION, manualParams.frameDurationNs)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualParams.exposureNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, manualParams.iso)
                reportCaptureMode("MAN[${targetFps}]")
            } else {
                val fpsRange = pickBestFpsRange(
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
                    targetFps
                )
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                if (fpsRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                    reportCaptureMode("AE[${fpsRange.lower}-${fpsRange.upper}]")
                } else {
                    reportCaptureMode("AE[default]")
                }
                if (targetFps >= 60) {
                    set(
                        CaptureRequest.SENSOR_FRAME_DURATION,
                        1_000_000_000L / targetFps.coerceAtLeast(1)
                    )
                }
            }

            applyZoomRatioIfNeeded(this, chars, lens)
        }
        return builder
    }

    private fun applyZoomRatioIfNeeded(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
        lens: CameraLensOption
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (activePhysicalCameraId == null || lens.zoomRatio != null)
        ) {
            val zoom = lens.zoomRatio ?: if (lens.wireName == "ultrawide") {
                val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (range != null && range.lower <= 0.65f) maxOf(range.lower, 0.6f) else null
            } else {
                null
            }
            if (zoom != null) {
                runCatching { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }
            }
        }
    }

    private fun canUseManual60Sensor(chars: CameraCharacteristics): Boolean {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
            return false
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val targetSize = Size(targetWidth, targetHeight)
        val codecMin = runCatching {
            map.getOutputMinFrameDuration(MediaCodec::class.java, targetSize)
        }.getOrDefault(0L)
        val texMin = runCatching {
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, targetSize)
        }.getOrDefault(0L)
        val minDurationNs = listOf(codecMin, texMin).filter { it > 0L }.minOrNull() ?: 0L
        if (minDurationNs <= 0L || minDurationNs > 18_500_000L) {
            return false
        }
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return false
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return false
        return expRange.lower <= 16_000_000L && isoRange.upper >= isoRange.lower
    }

    private fun buildManual60Params(chars: CameraCharacteristics): ManualExposureParams? {
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        if (lastAeExposureNs <= 0L || lastAeIso <= 0) return null
        return computeGainMatchedManualParams(
            lastAeExpNs = lastAeExposureNs,
            lastAeIso = lastAeIso,
            expLowerNs = expRange.lower,
            expUpperNs = expRange.upper,
            isoLower = isoRange.lower,
            isoUpper = isoRange.upper,
            targetFps = targetFps
        )
    }

    private fun createCaptureMonitorCallback(
        camera: CameraDevice,
        session: CameraCaptureSession,
        chars: CameraCharacteristics,
        lens: CameraLensOption,
        encSurface: Surface,
        activePreview: Surface?,
        handler: Handler,
        highSpeedRange: Range<Int>?,
        expectedGen: Int
    ): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                cbSession: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (!running.get() || expectedGen != sessionGeneration || captureSession !== cbSession) {
                    return
                }
                val tsNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                val reportedDurNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
                val deltaNs = if (lastCaptureTimestampNs > 0L && tsNs > lastCaptureTimestampNs) {
                    tsNs - lastCaptureTimestampNs
                } else {
                    0L
                }
                if (tsNs > 0L) {
                    lastCaptureTimestampNs = tsNs
                }
                val sampleDurNs = when {
                    deltaNs in 4_000_000L..250_000_000L -> deltaNs
                    reportedDurNs in 4_000_000L..250_000_000L -> reportedDurNs
                    else -> 0L
                }
                if (sampleDurNs > 0L) {
                    emaFrameDurationNs = if (emaFrameDurationNs == 0L) {
                        sampleDurNs
                    } else {
                        (emaFrameDurationNs * 3L + sampleDurNs) / 4L
                    }
                }

                if (highSpeedRange != null || targetFps < 60) {
                    return
                }

                if (!manual60Active) {
                    val aeExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val aeIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    if (aeExp > 0L) lastAeExposureNs = aeExp
                    if (aeIso > 0) lastAeIso = aeIso

                    regularSettledFrames++
                    if (!manual60Blocked &&
                        regularSettledFrames == 30 &&
                        emaFrameDurationNs >= 25_000_000L &&
                        canUseManual60Sensor(chars) &&
                        buildManual60Params(chars) != null
                    ) {
                        manual60Active = true
                        manual60ProbeFrames = 0
                        emaFrameDurationNs = 0L
                        runCatching {
                            val updated = buildRegularRequestBuilder(camera, chars, lens, encSurface, activePreview)
                            session.setRepeatingRequest(updated.build(), this, handler)
                        }.onFailure {
                            manual60Active = false
                            manual60Blocked = true
                        }
                    }
                } else {
                    manual60ProbeFrames++
                    if (manual60ProbeFrames >= 20 && emaFrameDurationNs >= 25_000_000L) {
                        // Hardware/HAL still enforces >=25ms frame interval even with AE_OFF; revert cleanly to AE_ON.
                        manual60Active = false
                        manual60Blocked = true
                        runCatching {
                            val reverted = buildRegularRequestBuilder(camera, chars, lens, encSurface, activePreview)
                            session.setRepeatingRequest(reverted.build(), this, handler)
                        }
                    }
                }
            }
        }
    }

    private fun closeCameraOnly() {
        ++sessionGeneration
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

    companion object {
        internal data class ManualExposureParams(
            val frameDurationNs: Long,
            val exposureNs: Long,
            val iso: Int
        )

        internal enum class EncoderFormatTier {
            FULL,
            CORE_60FPS,
            MINIMAL
        }

        internal fun pickBestFpsRange(ranges: Array<Range<Int>>?, desiredFps: Int): Range<Int>? {
            if (ranges.isNullOrEmpty()) return null
            ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }
            ranges.filter { it.upper == desiredFps }.maxByOrNull { it.lower }?.let { return it }
            if (desiredFps >= 60) {
                ranges
                    .filter { it.lower <= desiredFps && it.upper > desiredFps && it.upper <= 120 }
                    .minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })
                    ?.let { return it }
            }
            return ranges.maxWithOrNull(
                compareBy<Range<Int>> { -kotlin.math.abs(it.upper - desiredFps) }
                    .thenBy { it.upper }
                    .thenBy { it.lower }
            )
        }

        internal fun pickBestHighSpeedFpsRange(ranges: Array<Range<Int>>?, desiredFps: Int): Range<Int>? {
            if (ranges.isNullOrEmpty()) return null
            ranges.firstOrNull { it.lower == desiredFps && it.upper == desiredFps }?.let { return it }
            return ranges
                .filter { it.upper == desiredFps && it.lower <= desiredFps }
                .maxByOrNull { it.lower }
        }

        internal fun selectFastestControlMode(availableModes: IntArray?, vararg preferredOrder: Int): Int? {
            if (availableModes == null || availableModes.isEmpty()) return null
            for (mode in preferredOrder) {
                if (availableModes.contains(mode)) return mode
            }
            return null
        }

        internal fun computeGainMatchedManualParams(
            lastAeExpNs: Long,
            lastAeIso: Int,
            expLowerNs: Long,
            expUpperNs: Long,
            isoLower: Int,
            isoUpper: Int,
            targetFps: Int
        ): ManualExposureParams {
            val safeFps = targetFps.coerceAtLeast(1)
            val targetFrameNs = 1_000_000_000L / safeFps
            val safeExpUpper = maxOf(expLowerNs, expUpperNs)
            val maxAllowedExpNs = minOf(safeExpUpper, (targetFrameNs * 94L) / 100L)
            val clampedExpNs = minOf(lastAeExpNs.coerceAtLeast(1L), maxAllowedExpNs).coerceAtLeast(expLowerNs)

            val ratio = lastAeExpNs.coerceAtLeast(1L).toDouble() / clampedExpNs.coerceAtLeast(1L).toDouble()
            val safeIsoUpper = maxOf(isoLower, isoUpper)
            val scaledIso = (lastAeIso.coerceAtLeast(isoLower) * ratio).toLong()
                .coerceIn(isoLower.toLong(), safeIsoUpper.toLong()).toInt()
            return ManualExposureParams(
                frameDurationNs = targetFrameNs,
                exposureNs = clampedExpNs,
                iso = scaledIso
            )
        }

        internal fun buildEncoderFormat(
            encoder: EncoderOption,
            width: Int,
            height: Int,
            fps: Int,
            targetBitrateBps: Int,
            tier: EncoderFormatTier
        ): MediaFormat = MediaFormat.createVideoFormat(encoder.mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (tier == EncoderFormatTier.MINIMAL) return@apply

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
            if (encoder.isHardware) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, maxOf(fps, 60))
            }
            if (tier == EncoderFormatTier.CORE_60FPS) return@apply

            // Keep repeat-frame interval above 1 frame period so normal 59.94/60fps jitter never emits duplicate frames.
            val repeatUs = maxOf(33_333L, 2_000_000L / fps.coerceAtLeast(1))
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatUs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }

        internal fun clampBitrateForRange(
            requestedBps: Int,
            minBps: Int?,
            maxBps: Int?
        ): Int {
            val safeRequested = requestedBps.coerceAtLeast(1)
            if (minBps != null && maxBps != null && minBps > maxBps) {
                return safeRequested
            }
            val lower = minBps?.takeIf { it > 0 } ?: 1
            val upper = maxBps?.takeIf { it > 0 && it >= lower } ?: Int.MAX_VALUE
            return safeRequested.coerceIn(lower, upper)
        }
    }
}
