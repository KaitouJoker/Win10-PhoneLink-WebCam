package com.win10phonelink.webcam.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.win10phonelink.webcam.LocaleHelper
import com.win10phonelink.webcam.MainActivity
import com.win10phonelink.webcam.R
import com.win10phonelink.webcam.camera.CameraLensCatalog
import com.win10phonelink.webcam.camera.CameraStreamEngine
import com.win10phonelink.webcam.codec.VideoEncoderCatalog
import com.win10phonelink.webcam.model.CameraLensOption
import com.win10phonelink.webcam.model.EncoderOption
import com.win10phonelink.webcam.model.RotationMode
import com.win10phonelink.webcam.model.StreamConnectionState
import com.win10phonelink.webcam.model.StreamTelemetry
import com.win10phonelink.webcam.net.HandshakeRejectedException
import com.win10phonelink.webcam.net.PairingPayload
import com.win10phonelink.webcam.net.StreamClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): CameraStreamService = this@CameraStreamService
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private fun lStr(@StringRes resId: Int, vararg formatArgs: Any): String {
        val res = LocaleHelper.getLocalizedResources(this)
        return if (formatArgs.isEmpty()) res.getString(resId) else res.getString(resId, *formatArgs)
    }

    private data class ActiveConfig(
        val pairing: PairingPayload,
        val lens: CameraLensOption,
        val encoder: EncoderOption,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateBps: Int,
        val rotationMode: RotationMode
    )

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isStreamingIntent = AtomicBoolean(false)

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    private lateinit var engine: CameraStreamEngine
    private var streamClient: StreamClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeConfig: ActiveConfig? = null

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var engineStarted: Boolean = false
    private var reconnectAttempt: Int = 0
    private var lastDisconnectReason: String? = null
    @StringRes
    private var lastStatusErrorResId: Int? = null

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (isStreamingIntent.get() && engineStarted) {
                engine.requestSyncFrame()
                mainHandler.postDelayed(this, PERIODIC_SYNC_MS)
            }
        }
    }

    private val reconnectRunnable = Runnable {
        if (isStreamingIntent.get()) {
            connectStreamClient()
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = CameraStreamEngine(this)
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_STREAM -> {
                stopStreaming()
                return START_NOT_STICKY
            }

            ACTION_START_STREAM -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    lastStatusErrorResId = R.string.permission_camera_required
                    updateTelemetry(
                        StreamTelemetry(
                            state = StreamConnectionState.ERROR,
                            statusDetail = lStr(R.string.permission_camera_required)
                        )
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundCompat()
                val cfg = parseConfigFromIntent(intent)
                if (cfg != null) {
                    beginSession(cfg)
                } else {
                    stopStreaming()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreamingInternal(stopService = false)
        super.onDestroy()
    }

    fun attachPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (engineStarted) {
            engine.attachPreviewSurface(surface)
        }
    }

    fun switchLens(newLens: CameraLensOption) {
        val cfg = activeConfig ?: return
        activeConfig = cfg.copy(lens = newLens)
        if (engineStarted) {
            engine.switchLens(newLens)
            val rot = engine.getCurrentRotationDegrees()
            streamClient?.sendControl(rot, newLens.wireName)
            _telemetry.update {
                it.copy(
                    activeLensLabel = newLens.formatLabel(LocaleHelper.isKorean(this)),
                    rotationDegrees = rot
                )
            }
        }
    }

    fun updateRotationMode(mode: RotationMode) {
        val cfg = activeConfig ?: return
        activeConfig = cfg.copy(rotationMode = mode)
        if (engineStarted) {
            engine.setRotationMode(mode)
        }
    }

    fun requestSyncFrame() {
        if (engineStarted) {
            engine.requestSyncFrame()
        }
    }

    fun stopStreaming() {
        stopStreamingInternal(stopService = true)
    }

    fun refreshLocalization() {
        ensureNotificationChannel()
        val cfg = activeConfig
        val isKorean = LocaleHelper.isKorean(this)
        val cur = _telemetry.value

        val updatedTelemetry = when (cur.state) {
            StreamConnectionState.IDLE -> cur.copy(
                statusDetail = lStr(R.string.status_idle)
            )
            StreamConnectionState.CONNECTING -> cur.copy(
                statusDetail = if (cfg != null) {
                    lStr(R.string.status_connecting_target, cfg.pairing.ip, cfg.pairing.port)
                } else {
                    cur.statusDetail
                },
                activeLensLabel = cfg?.lens?.formatLabel(isKorean) ?: cur.activeLensLabel
            )
            StreamConnectionState.STREAMING -> cur.copy(
                statusDetail = if (cfg != null) {
                    lStr(R.string.status_streaming_target, cfg.pairing.toDisplaySummary())
                } else {
                    cur.statusDetail
                },
                activeLensLabel = cfg?.lens?.formatLabel(isKorean) ?: cur.activeLensLabel
            )
            StreamConnectionState.RECONNECTING -> cur.copy(
                statusDetail = lStr(
                    R.string.status_reconnecting_attempt,
                    reconnectAttempt,
                    lastDisconnectReason ?: lStr(R.string.err_disconnected)
                ),
                activeLensLabel = cfg?.lens?.formatLabel(isKorean) ?: cur.activeLensLabel
            )
            StreamConnectionState.ERROR -> lastStatusErrorResId?.let { resId ->
                cur.copy(statusDetail = lStr(resId))
            } ?: cur
        }
        updateTelemetry(updatedTelemetry)

        if (isStreamingIntent.get()) {
            val notifText = when (cur.state) {
                StreamConnectionState.STREAMING -> if (cfg != null) {
                    lStr(
                        R.string.notif_streaming_format,
                        cfg.width,
                        cfg.height,
                        cfg.encoder.wireCodec.uppercase(),
                        cfg.pairing.ip
                    )
                } else {
                    lStr(R.string.notif_initializing)
                }
                StreamConnectionState.RECONNECTING -> lStr(
                    R.string.notif_reconnecting_target,
                    cfg?.pairing?.ip.orEmpty()
                )
                else -> lStr(R.string.notif_initializing)
            }
            updateNotificationText(notifText)
        }
    }

    private fun beginSession(cfg: ActiveConfig) {
        stopStreamingInternal(stopService = false)
        activeConfig = cfg
        isStreamingIntent.set(true)
        reconnectAttempt = 0
        lastDisconnectReason = null
        lastStatusErrorResId = null
        acquireWakeLock()

        updateTelemetry(
            StreamTelemetry(
                state = StreamConnectionState.CONNECTING,
                statusDetail = lStr(R.string.status_connecting_target, cfg.pairing.ip, cfg.pairing.port),
                activeLensLabel = cfg.lens.formatLabel(LocaleHelper.isKorean(this)),
                activeEncoderName = cfg.encoder.codecName,
                activeCodec = cfg.encoder.wireCodec.uppercase(),
                width = cfg.width,
                height = cfg.height
            )
        )
        connectStreamClient()
    }

    private fun connectStreamClient() {
        val cfg = activeConfig ?: return
        if (!isStreamingIntent.get()) return

        streamClient?.stop()
        val client = StreamClient(
            pairing = cfg.pairing,
            codecWireName = cfg.encoder.wireCodec,
            encoderName = cfg.encoder.codecName,
            width = cfg.width,
            height = cfg.height,
            fps = cfg.fps,
            onConnected = {
                mainHandler.post { handleClientConnected(cfg) }
            },
            onDisconnected = { error ->
                mainHandler.post { handleClientDisconnected(error) }
            },
            onNeedSyncFrame = {
                if (engineStarted) engine.requestSyncFrame()
            },
            onStatsSample = { measuredFps, kbps, sentTotal, droppedTotal ->
                _telemetry.update {
                    it.copy(
                        actualFps = measuredFps,
                        bitrateKbps = kbps,
                        packetsSent = sentTotal,
                        droppedPackets = droppedTotal
                    )
                }
            }
        )
        streamClient = client
        client.start()
    }

    private fun handleClientConnected(cfg: ActiveConfig) {
        if (!isStreamingIntent.get()) return
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectAttempt = 0

        if (!engineStarted) {
            engineStarted = true
            engine.start(
                lens = cfg.lens,
                encoder = cfg.encoder,
                width = cfg.width,
                height = cfg.height,
                fps = cfg.fps,
                bitrateBps = cfg.bitrateBps,
                rotationMode = cfg.rotationMode,
                initialPreviewSurface = previewSurface,
                cb = object : CameraStreamEngine.Callback {
                    override fun onEncodedPacket(
                        isKeyframe: Boolean,
                        isCodecConfig: Boolean,
                        rotationDegrees: Int,
                        ptsUs: Long,
                        payload: ByteArray
                    ) {
                        streamClient?.sendVideoFrame(
                            isKeyframe = isKeyframe,
                            isCodecConfig = isCodecConfig,
                            rotationDegrees = rotationDegrees,
                            ptsUs = ptsUs,
                            payload = payload
                        )
                    }

                    override fun onRotationChanged(rotationDegrees: Int, cameraWireName: String) {
                        streamClient?.sendControl(rotationDegrees, cameraWireName)
                        _telemetry.update { it.copy(rotationDegrees = rotationDegrees) }
                    }

                    override fun onError(message: String) {
                        mainHandler.post {
                            lastStatusErrorResId = null
                            lastDisconnectReason = null
                            updateTelemetry(
                                _telemetry.value.copy(
                                    state = StreamConnectionState.ERROR,
                                    statusDetail = message
                                )
                            )
                            stopStreamingInternal(stopService = true)
                        }
                    }
                }
            )
            mainHandler.removeCallbacks(periodicSyncRunnable)
            mainHandler.postDelayed(periodicSyncRunnable, PERIODIC_SYNC_MS)
        } else {
            // Reconnected while encoder was already running: re-send cached SPS/PPS/VPS and request IDR.
            engine.getCachedCodecConfig()?.let { cfgBytes ->
                streamClient?.sendVideoFrame(
                    isKeyframe = false,
                    isCodecConfig = true,
                    rotationDegrees = engine.getCurrentRotationDegrees(),
                    ptsUs = 0L,
                    payload = cfgBytes
                )
            }
            engine.requestSyncFrame()
        }

        val rot = engine.getCurrentRotationDegrees()
        streamClient?.sendControl(rot, engine.getCurrentCameraWireName())
        lastDisconnectReason = null
        updateTelemetry(
            _telemetry.value.copy(
                state = StreamConnectionState.STREAMING,
                statusDetail = lStr(R.string.status_streaming_target, cfg.pairing.toDisplaySummary()),
                rotationDegrees = rot,
                activeLensLabel = cfg.lens.formatLabel(LocaleHelper.isKorean(this)),
                activeEncoderName = cfg.encoder.codecName,
                activeCodec = cfg.encoder.wireCodec.uppercase(),
                width = cfg.width,
                height = cfg.height
            )
        )
        updateNotificationText(
            lStr(
                R.string.notif_streaming_format,
                cfg.width,
                cfg.height,
                cfg.encoder.wireCodec.uppercase(),
                cfg.pairing.ip
            )
        )
    }

    private fun handleClientDisconnected(error: Throwable) {
        if (!isStreamingIntent.get()) return

        if (error is HandshakeRejectedException) {
            lastStatusErrorResId = if (error.message == null) R.string.err_handshake_rejected else null
            updateTelemetry(
                _telemetry.value.copy(
                    state = StreamConnectionState.ERROR,
                    statusDetail = error.message ?: lStr(R.string.err_handshake_rejected)
                )
            )
            stopStreamingInternal(stopService = true, preserveErrorTelemetry = true)
            return
        }

        reconnectAttempt++
        val delayMs = when {
            reconnectAttempt <= 1 -> 1000L
            reconnectAttempt == 2 -> 2000L
            reconnectAttempt == 3 -> 3000L
            else -> 5000L
        }
        lastDisconnectReason = error.message
        val msg = lastDisconnectReason ?: lStr(R.string.err_disconnected)
        updateTelemetry(
            _telemetry.value.copy(
                state = StreamConnectionState.RECONNECTING,
                statusDetail = lStr(R.string.status_reconnecting_attempt, reconnectAttempt, msg)
            )
        )
        updateNotificationText(
            lStr(R.string.notif_reconnecting_target, activeConfig?.pairing?.ip.orEmpty())
        )
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun stopStreamingInternal(stopService: Boolean, preserveErrorTelemetry: Boolean = false) {
        isStreamingIntent.set(false)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(periodicSyncRunnable)

        streamClient?.stop()
        streamClient = null

        if (engineStarted) {
            engineStarted = false
            engine.stop()
        }

        releaseWakeLock()
        activeConfig = null

        if (!preserveErrorTelemetry) {
            updateTelemetry(
                StreamTelemetry(
                    state = StreamConnectionState.IDLE,
                    statusDetail = lStr(R.string.status_idle)
                )
            )
        }

        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun parseConfigFromIntent(intent: Intent): ActiveConfig? {
        val pairingJson = intent.getStringExtra(EXTRA_PAIRING_JSON) ?: return null
        val pairing = PairingPayload.fromQrJson(pairingJson) ?: return null

        val lenses = CameraLensCatalog.discoverLenses(this)
        val lensId = intent.getStringExtra(EXTRA_LENS_ID)
        val lens = lenses.firstOrNull { it.id == lensId } ?: lenses.firstOrNull() ?: return null

        val encoders = VideoEncoderCatalog.discoverEncoders(LocaleHelper.isKorean(this))
        val encoderName = intent.getStringExtra(EXTRA_ENCODER_NAME)
        val encoder = encoders.firstOrNull { it.codecName == encoderName }
            ?: encoders.firstOrNull()
            ?: return null

        val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val fps = intent.getIntExtra(EXTRA_FPS, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE_BPS, 20_000_000)
        val rotOrdinal = intent.getIntExtra(EXTRA_ROTATION_ORDINAL, 0)
        val rotMode = RotationMode.values().getOrElse(rotOrdinal) { RotationMode.AUTO }

        return ActiveConfig(
            pairing = pairing,
            lens = lens,
            encoder = encoder,
            width = width,
            height = height,
            fps = fps,
            bitrateBps = bitrate,
            rotationMode = rotMode
        )
    }

    private fun startForegroundCompat() {
        ensureNotificationChannel()
        val notification = buildNotification(lStr(R.string.notif_initializing))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            lStr(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = lStr(R.string.notif_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            100,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP_STREAM
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(lStr(R.string.notif_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_videocam)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(R.drawable.ic_videocam_off, lStr(R.string.notif_stop_action), stopPending)
            .build()
    }

    private fun updateNotificationText(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun updateTelemetry(telemetry: StreamTelemetry) {
        _telemetry.value = telemetry
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Win10PhoneCam::StreamWakeLock")
                .apply { setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "win10_phonecam_stream"
        private const val NOTIFICATION_ID = 4001
        private const val PERIODIC_SYNC_MS = 4_000L

        const val ACTION_START_STREAM = "com.win10phonelink.webcam.action.START_STREAM"
        const val ACTION_STOP_STREAM = "com.win10phonelink.webcam.action.STOP_STREAM"

        const val EXTRA_PAIRING_JSON = "extra_pairing_json"
        const val EXTRA_LENS_ID = "extra_lens_id"
        const val EXTRA_ENCODER_NAME = "extra_encoder_name"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE_BPS = "extra_bitrate_bps"
        const val EXTRA_ROTATION_ORDINAL = "extra_rotation_ordinal"
    }
}
