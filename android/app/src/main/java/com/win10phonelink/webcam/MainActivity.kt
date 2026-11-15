package com.win10phonelink.webcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.win10phonelink.webcam.camera.CameraLensCatalog
import com.win10phonelink.webcam.codec.VideoEncoderCatalog
import com.win10phonelink.webcam.databinding.ActivityMainBinding
import com.win10phonelink.webcam.databinding.DialogManualConnectBinding
import com.win10phonelink.webcam.model.BitratePreset
import com.win10phonelink.webcam.model.CameraLensOption
import com.win10phonelink.webcam.model.CodecFamily
import com.win10phonelink.webcam.model.EncoderOption
import com.win10phonelink.webcam.model.FpsPreset
import com.win10phonelink.webcam.model.ResolutionPreset
import com.win10phonelink.webcam.model.RotationMode
import com.win10phonelink.webcam.model.StreamConnectionState
import com.win10phonelink.webcam.model.StreamTelemetry
import com.win10phonelink.webcam.net.PairingPayload
import com.win10phonelink.webcam.service.CameraStreamService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var lensOptions: List<CameraLensOption> = emptyList()
    private var allEncoders: List<EncoderOption> = emptyList()
    private var encoderOptions: List<EncoderOption> = emptyList()

    private var currentPairing: PairingPayload? = null
    private var selectedLens: CameraLensOption? = null
    private var selectedCodec: CodecFamily = CodecFamily.H264
    private var selectedEncoder: EncoderOption? = null
    private var selectedResolution: ResolutionPreset = ResolutionPreset.HD_720P
    private var selectedFps: FpsPreset = FpsPreset.FPS_60
    private var selectedBitrate: BitratePreset = BitratePreset.MBPS_20
    private var selectedRotation: RotationMode = RotationMode.AUTO

    private var suppressSpinnerEvents: Boolean = true
    private var latestState: StreamConnectionState = StreamConnectionState.IDLE

    private var streamService: CameraStreamService? = null
    private var isServiceBound: Boolean = false
    private var telemetryJob: Job? = null

    private var previewSurface: Surface? = null

    // Idle preview camera (only active when service is IDLE and activity is foreground).
    private var idleBgThread: HandlerThread? = null
    private var idleBgHandler: Handler? = null
    private var idleCamera: CameraDevice? = null
    private var idleSession: CameraCaptureSession? = null
    private var idleCloseLatch: CountDownLatch? = null
    private var pendingActionAfterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true || hasCameraPermission()
            updatePermissionOverlay(cameraGranted)
            if (cameraGranted) {
                refreshCatalogsIfNeeded()
                val action = pendingActionAfterPermission
                pendingActionAfterPermission = null
                if (action != null) {
                    action()
                } else {
                    maybeStartIdlePreview()
                }
            } else {
                pendingActionAfterPermission = null
                Toast.makeText(this, R.string.permission_camera_required, Toast.LENGTH_LONG).show()
            }
        }

    private val qrLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    val json = result.data?.getStringExtra(QrScannerActivity.EXTRA_PAIRING_JSON)
                    val parsed = json?.let { PairingPayload.fromQrJson(it) }
                    if (parsed != null) {
                        savePairing(parsed)
                        applyPairingHints(parsed)
                        startStreamingWithChecks()
                    }
                }

                QrScannerActivity.RESULT_MANUAL_FALLBACK -> {
                    showManualConnectDialog()
                }
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? CameraStreamService.LocalBinder ?: return
            val svc = binder.getService()
            streamService = svc
            isServiceBound = true
            svc.attachPreviewSurface(previewSurface)

            telemetryJob?.cancel()
            telemetryJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    svc.telemetry.collect { telemetry ->
                        renderTelemetry(telemetry)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryJob?.cancel()
            telemetryJob = null
            streamService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textSubtitle.text = "${getString(R.string.subtitle_device)} • ${Build.MANUFACTURER} ${Build.MODEL}"

        loadSavedPairing()
        setupCatalogsAndSpinners()
        setupButtons()

        val savedEnc = prefs.getString(KEY_ENCODER_NAME, null)
        val needPrompt = !prefs.getBoolean(KEY_HW_ACCEL_PROMPTED, false) ||
            (savedEnc != null && allEncoders.none { it.codecName == savedEnc })
        if (needPrompt && allEncoders.isNotEmpty()) {
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    showHwAccelerationSelectorDialog(isStartup = true)
                }
            }
        } else if (allEncoders.isNotEmpty()) {
            val hwCount = allEncoders.count { it.isHardware }
            Toast.makeText(
                this,
                "감지된 HW 가속 인코더 ${hwCount}개 • 활성: ${selectedEncoder?.codecName ?: "-"}",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.texturePreview.surfaceTextureListener = this
        updatePermissionOverlay(hasCameraPermission())
        if (!hasCameraPermission()) {
            requestRequiredPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        val bindIntent = Intent(this, CameraStreamService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionOverlay(hasCameraPermission())
        configurePreviewTransform()
        maybeStartIdlePreview()
    }

    override fun onPause() {
        closeIdlePreviewSync()
        super.onPause()
    }

    override fun onStop() {
        telemetryJob?.cancel()
        telemetryJob = null
        if (isServiceBound) {
            streamService?.attachPreviewSurface(null)
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        closeIdlePreviewSync()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurePreviewTransform()
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(selectedResolution.width, selectedResolution.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        configurePreviewTransform()
        if (isStreamActive()) {
            streamService?.attachPreviewSurface(previewSurface)
        } else {
            maybeStartIdlePreview()
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surfaceTexture.setDefaultBufferSize(selectedResolution.width, selectedResolution.height)
        configurePreviewTransform()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        closeIdlePreviewSync()
        streamService?.attachPreviewSurface(null)
        previewSurface?.release()
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // Frame updated.
    }

    private fun setupButtons() {
        binding.btnGrantPermission.setOnClickListener {
            requestRequiredPermissions()
        }

        binding.btnScanQr.setOnClickListener {
            if (!hasCameraPermission()) {
                pendingActionAfterPermission = { launchQrScanner() }
                requestRequiredPermissions()
                return@setOnClickListener
            }
            launchQrScanner()
        }

        binding.btnManualConnect.setOnClickListener {
            showManualConnectDialog()
        }

        binding.btnToggleStream.setOnClickListener {
            if (isStreamActive()) {
                stopStreaming()
            } else {
                startStreamingWithChecks()
            }
        }

        binding.btnRequestSync.setOnClickListener {
            streamService?.requestSyncFrame()
            Toast.makeText(this, "Requested IDR keyframe", Toast.LENGTH_SHORT).show()
        }

        binding.btnSelectHwEncoder.setOnClickListener {
            showHwAccelerationSelectorDialog(isStartup = false)
        }
    }

    private fun launchQrScanner() {
        closeIdlePreviewSync()
        qrLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    private fun setupCatalogsAndSpinners() {
        suppressSpinnerEvents = true

        lensOptions = CameraLensCatalog.discoverLenses(this)
        allEncoders = VideoEncoderCatalog.discoverEncoders()

        val savedLensId = prefs.getString(KEY_LENS_ID, null)
        val savedCodecName = prefs.getString(KEY_CODEC, CodecFamily.H264.name)
        val savedEncoderName = prefs.getString(KEY_ENCODER_NAME, null)
        val savedResName = prefs.getString(KEY_RESOLUTION, ResolutionPreset.HD_720P.name)
        val savedFpsName = prefs.getString(KEY_FPS, FpsPreset.FPS_60.name)
        val savedBitrateName = prefs.getString(KEY_BITRATE, BitratePreset.MBPS_20.name)
        val savedRotName = prefs.getString(KEY_ROTATION, RotationMode.AUTO.name)

        selectedCodec = CodecFamily.values().firstOrNull { it.name == savedCodecName }
            ?: CodecFamily.H264
        selectedResolution = ResolutionPreset.values().firstOrNull { it.name == savedResName }
            ?: ResolutionPreset.HD_720P
        selectedFps = FpsPreset.values().firstOrNull { it.name == savedFpsName }
            ?: FpsPreset.FPS_60
        selectedBitrate = BitratePreset.values().firstOrNull { it.name == savedBitrateName }
            ?: BitratePreset.MBPS_20
        selectedRotation = RotationMode.values().firstOrNull { it.name == savedRotName }
            ?: RotationMode.AUTO

        binding.spinnerLens.adapter = makeSpinnerAdapter(lensOptions)
        val lensIdx = lensOptions.indexOfFirst { it.id == savedLensId }.coerceAtLeast(0)
        if (lensOptions.isNotEmpty()) {
            selectedLens = lensOptions[lensIdx]
            binding.spinnerLens.setSelection(lensIdx, false)
        }

        val codecValues = CodecFamily.values().toList()
        binding.spinnerCodec.adapter = makeSpinnerAdapter(codecValues)
        binding.spinnerCodec.setSelection(codecValues.indexOf(selectedCodec), false)

        refilterEncoderSpinner(preferredCodecName = savedEncoderName)

        val resValues = ResolutionPreset.values().toList()
        binding.spinnerResolution.adapter = makeSpinnerAdapter(resValues)
        binding.spinnerResolution.setSelection(resValues.indexOf(selectedResolution), false)

        val fpsValues = FpsPreset.values().toList()
        binding.spinnerFps.adapter = makeSpinnerAdapter(fpsValues)
        binding.spinnerFps.setSelection(fpsValues.indexOf(selectedFps), false)

        val bitrateValues = BitratePreset.values().toList()
        binding.spinnerBitrate.adapter = makeSpinnerAdapter(bitrateValues)
        binding.spinnerBitrate.setSelection(bitrateValues.indexOf(selectedBitrate), false)

        val rotValues = RotationMode.values().toList()
        binding.spinnerRotation.adapter = makeSpinnerAdapter(rotValues)
        binding.spinnerRotation.setSelection(rotValues.indexOf(selectedRotation), false)

        attachSpinnerListeners(codecValues, resValues, fpsValues, bitrateValues, rotValues)

        binding.root.post { suppressSpinnerEvents = false }
    }

    private fun refilterEncoderSpinner(preferredCodecName: String? = null) {
        val filtered = allEncoders.filter { it.wireCodec == selectedCodec.wireCodec }
        encoderOptions = if (filtered.isNotEmpty()) filtered else allEncoders

        binding.spinnerEncoder.adapter = makeSpinnerAdapter(encoderOptions)
        val encIdx = preferredCodecName?.let { name ->
            encoderOptions.indexOfFirst { it.codecName == name }
        }?.takeIf { it >= 0 } ?: 0

        if (encoderOptions.isNotEmpty()) {
            selectedEncoder = encoderOptions[encIdx]
            binding.spinnerEncoder.setSelection(encIdx, false)
        } else {
            selectedEncoder = null
        }
        updateEncoderDetailText()
    }

    private fun applyPairingHints(payload: PairingPayload) {
        suppressSpinnerEvents = true
        try {
            val hintedCodec = CodecFamily.fromWireCodec(payload.codec)
            if (hintedCodec != null && hintedCodec != selectedCodec) {
                selectedCodec = hintedCodec
                prefs.edit().putString(KEY_CODEC, hintedCodec.name).apply()
                val codecIdx = CodecFamily.values().indexOf(hintedCodec)
                if (codecIdx >= 0) binding.spinnerCodec.setSelection(codecIdx, false)
                refilterEncoderSpinner(preferredCodecName = null)
                selectedEncoder?.let { prefs.edit().putString(KEY_ENCODER_NAME, it.codecName).apply() }
            }

            val hintedRes = ResolutionPreset.matchSize(payload.width, payload.height)
            if (hintedRes != null && hintedRes != selectedResolution) {
                selectedResolution = hintedRes
                prefs.edit().putString(KEY_RESOLUTION, hintedRes.name).apply()
                val resIdx = ResolutionPreset.values().indexOf(hintedRes)
                if (resIdx >= 0) binding.spinnerResolution.setSelection(resIdx, false)
                binding.texturePreview.surfaceTexture?.setDefaultBufferSize(hintedRes.width, hintedRes.height)
                configurePreviewTransform()
            }
        } finally {
            binding.root.post { suppressSpinnerEvents = false }
        }
    }

    private fun refreshCatalogsIfNeeded() {
        if (lensOptions.isEmpty()) {
            setupCatalogsAndSpinners()
        }
    }

    private fun <T> makeSpinnerAdapter(items: List<T>): ArrayAdapter<T> {
        return ArrayAdapter(this, R.layout.item_spinner, items).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
    }

    private fun attachSpinnerListeners(
        codecValues: List<CodecFamily>,
        resValues: List<ResolutionPreset>,
        fpsValues: List<FpsPreset>,
        bitrateValues: List<BitratePreset>,
        rotValues: List<RotationMode>
    ) {
        binding.spinnerLens.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in lensOptions.indices) return
                val next = lensOptions[pos]
                if (next.id == selectedLens?.id) return
                selectedLens = next
                prefs.edit().putString(KEY_LENS_ID, next.id).apply()
                if (isStreamActive()) {
                    streamService?.switchLens(next)
                } else {
                    closeIdlePreviewSync()
                    maybeStartIdlePreview()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerCodec.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in codecValues.indices) return
                val next = codecValues[pos]
                if (next == selectedCodec) return
                selectedCodec = next
                prefs.edit().putString(KEY_CODEC, next.name).apply()
                refilterEncoderSpinner(preferredCodecName = null)
                selectedEncoder?.let { prefs.edit().putString(KEY_ENCODER_NAME, it.codecName).apply() }
                restartStreamIfActive()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerEncoder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in encoderOptions.indices) return
                val next = encoderOptions[pos]
                if (next.codecName == selectedEncoder?.codecName) return
                selectedEncoder = next
                prefs.edit().putString(KEY_ENCODER_NAME, next.codecName).apply()
                updateEncoderDetailText()
                restartStreamIfActive()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in resValues.indices) return
                val next = resValues[pos]
                if (next == selectedResolution) return
                selectedResolution = next
                prefs.edit().putString(KEY_RESOLUTION, next.name).apply()
                binding.texturePreview.surfaceTexture?.setDefaultBufferSize(next.width, next.height)
                configurePreviewTransform()
                restartStreamIfActive()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in fpsValues.indices) return
                val next = fpsValues[pos]
                if (next == selectedFps) return
                selectedFps = next
                prefs.edit().putString(KEY_FPS, next.name).apply()
                restartStreamIfActive()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in bitrateValues.indices) return
                val next = bitrateValues[pos]
                if (next == selectedBitrate) return
                selectedBitrate = next
                prefs.edit().putString(KEY_BITRATE, next.name).apply()
                restartStreamIfActive()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerRotation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinnerEvents || pos !in rotValues.indices) return
                val next = rotValues[pos]
                if (next == selectedRotation) return
                selectedRotation = next
                prefs.edit().putString(KEY_ROTATION, next.name).apply()
                if (isStreamActive()) {
                    streamService?.updateRotationMode(next)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateEncoderDetailText() {
        val enc = selectedEncoder
        if (enc == null) {
            binding.textEncoderDetail.text = "호환되는 MediaCodec 비디오 인코더를 찾지 못했습니다."
            return
        }
        val hwCount = allEncoders.count { it.isHardware }
        val modeDesc = buildString {
            append(if (enc.isHardware) "HW 가속 (${enc.vendorLabel})" else "SW 폴백")
            append(" • ${enc.mimeType}")
            append(
                when {
                    enc.supportsCbr -> " • CBR"
                    enc.supportsVbr -> " • VBR"
                    else -> ""
                }
            )
            if (enc.supports1080p60) append(" • 1080p60")
            append(" • 감지된 기기 HW: ${hwCount}개 (전체 ${allEncoders.size}개)")
        }
        binding.textEncoderDetail.text = modeDesc
    }

    private fun showHwAccelerationSelectorDialog(isStartup: Boolean) {
        val candidates = VideoEncoderCatalog.discoverHardwareEncoders()
        if (candidates.isEmpty()) {
            Toast.makeText(this, "사용 가능한 비디오 인코더를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }

        val labels = candidates.map { opt ->
            val hwTag = if (opt.isHardware) "HW (${opt.vendorLabel})" else "SW 폴백"
            val rc = when {
                opt.supportsCbr -> "CBR"
                opt.supportsVbr -> "VBR"
                else -> "Default"
            }
            val fpsTag = if (opt.supports1080p60) "1080p60 지원" else "기본 해상도"
            "[${opt.wireCodec.uppercase()}] ${opt.codecName}\n$hwTag • $rc • $fpsTag"
        }.toTypedArray()

        var chosenIdx = candidates.indexOfFirst {
            it.codecName == selectedEncoder?.codecName && it.wireCodec == selectedCodec.wireCodec
        }.coerceAtLeast(0)

        val hwCount = candidates.count { it.isHardware }
        val title = if (isStartup) {
            "${Build.MODEL} HW 가속 인코더 (${hwCount}개 감지)"
        } else {
            getString(R.string.hw_dialog_title)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, chosenIdx) { _, which ->
                chosenIdx = which
            }
            .setPositiveButton("선택 적용") { _, _ ->
                val chosen = candidates[chosenIdx]
                applyChosenEncoder(chosen)
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                prefs.edit().putBoolean(KEY_HW_ACCEL_PROMPTED, true).apply()
            }
            .show()
    }

    private fun applyChosenEncoder(chosen: EncoderOption) {
        suppressSpinnerEvents = true
        try {
            val targetFamily = CodecFamily.fromWireCodec(chosen.wireCodec) ?: selectedCodec
            selectedCodec = targetFamily
            val codecIdx = CodecFamily.values().indexOf(targetFamily)
            if (codecIdx >= 0) {
                binding.spinnerCodec.setSelection(codecIdx, false)
            }
            refilterEncoderSpinner(preferredCodecName = chosen.codecName)
            selectedEncoder = chosen
            prefs.edit()
                .putString(KEY_CODEC, targetFamily.name)
                .putString(KEY_ENCODER_NAME, chosen.codecName)
                .putBoolean(KEY_HW_ACCEL_PROMPTED, true)
                .apply()
            updateEncoderDetailText()
        } finally {
            binding.root.post { suppressSpinnerEvents = false }
        }
        restartStreamIfActive()
    }

    private fun showManualConnectDialog() {
        val dlgBinding = DialogManualConnectBinding.inflate(layoutInflater)
        currentPairing?.let { existing ->
            dlgBinding.editIp.setText(existing.ip)
            dlgBinding.editPort.setText(existing.port.toString())
            dlgBinding.editToken.setText(existing.token)
            dlgBinding.editPcName.setText(existing.name)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_dialog_title)
            .setView(dlgBinding.root)
            .setPositiveButton(R.string.btn_connect) { _, _ ->
                val ip = dlgBinding.editIp.text?.toString()?.trim().orEmpty()
                val port = dlgBinding.editPort.text?.toString()?.trim()?.toIntOrNull() ?: -1
                val token = dlgBinding.editToken.text?.toString()?.trim().orEmpty()
                val pcName = dlgBinding.editPcName.text?.toString()?.trim().orEmpty()

                if (ip.isEmpty() || port !in 1..65535 || token.isEmpty()) {
                    Toast.makeText(this, R.string.err_invalid_manual_input, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                savePairing(PairingPayload(version = 1, ip = ip, port = port, token = token, name = pcName))
                startStreamingWithChecks()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun loadSavedPairing() {
        val savedJson = prefs.getString(KEY_PAIRING_JSON, null)
        currentPairing = savedJson?.let { PairingPayload.fromQrJson(it) }
        renderPairedTarget()
    }

    private fun savePairing(payload: PairingPayload) {
        currentPairing = payload
        prefs.edit().putString(KEY_PAIRING_JSON, payload.toJsonString()).apply()
        renderPairedTarget()
    }

    private fun renderPairedTarget() {
        val pairing = currentPairing
        if (pairing == null) {
            binding.textPairedTarget.text = getString(R.string.no_pc_paired)
            binding.textPairedTarget.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.textPairedTarget.text = "Paired PC: ${pairing.toDisplaySummary()} (Token: ${pairing.token.take(4)}…)"
            binding.textPairedTarget.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    private fun startStreamingWithChecks() {
        if (!hasCameraPermission()) {
            pendingActionAfterPermission = { startStreamingWithChecks() }
            requestRequiredPermissions()
            return
        }
        val pairing = currentPairing
        if (pairing == null) {
            Toast.makeText(this, R.string.no_pc_paired, Toast.LENGTH_SHORT).show()
            showManualConnectDialog()
            return
        }
        val lens = selectedLens ?: lensOptions.firstOrNull()
        val encoder = selectedEncoder ?: encoderOptions.firstOrNull()
        if (lens == null || encoder == null) {
            Toast.makeText(this, "Missing camera lens or video encoder.", Toast.LENGTH_LONG).show()
            return
        }

        closeIdlePreviewSync()
        binding.texturePreview.surfaceTexture?.setDefaultBufferSize(
            selectedResolution.width,
            selectedResolution.height
        )
        configurePreviewTransform()

        val intent = Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START_STREAM
            putExtra(CameraStreamService.EXTRA_PAIRING_JSON, pairing.toJsonString())
            putExtra(CameraStreamService.EXTRA_LENS_ID, lens.id)
            putExtra(CameraStreamService.EXTRA_ENCODER_NAME, encoder.codecName)
            putExtra(CameraStreamService.EXTRA_WIDTH, selectedResolution.width)
            putExtra(CameraStreamService.EXTRA_HEIGHT, selectedResolution.height)
            putExtra(CameraStreamService.EXTRA_FPS, selectedFps.fps)
            putExtra(CameraStreamService.EXTRA_BITRATE_BPS, selectedBitrate.bps)
            putExtra(CameraStreamService.EXTRA_ROTATION_ORDINAL, selectedRotation.ordinal)
        }
        ContextCompat.startForegroundService(this, intent)
        streamService?.attachPreviewSurface(previewSurface)
    }

    private fun stopStreaming() {
        if (isServiceBound && streamService != null) {
            streamService?.stopStreaming()
        } else {
            val stopIntent = Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_STOP_STREAM
            }
            startService(stopIntent)
        }
    }

    private fun restartStreamIfActive() {
        if (isStreamActive()) {
            startStreamingWithChecks()
        }
    }

    private fun isStreamActive(): Boolean {
        return latestState == StreamConnectionState.CONNECTING ||
            latestState == StreamConnectionState.STREAMING ||
            latestState == StreamConnectionState.RECONNECTING
    }

    private fun renderTelemetry(telemetry: StreamTelemetry) {
        val prevState = latestState
        latestState = telemetry.state

        val active = isStreamActive()
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val (statusText, dotColorRes) = when (telemetry.state) {
            StreamConnectionState.IDLE -> getString(R.string.status_idle) to R.color.text_secondary
            StreamConnectionState.CONNECTING -> getString(R.string.status_connecting) to R.color.accent_amber
            StreamConnectionState.STREAMING -> getString(R.string.status_streaming) to R.color.accent_green
            StreamConnectionState.RECONNECTING -> getString(R.string.status_reconnecting) to R.color.accent_amber
            StreamConnectionState.ERROR -> getString(R.string.status_error) to R.color.accent_red
        }

        binding.textStatus.text = statusText
        binding.viewStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))

        if (active) {
            binding.btnToggleStream.setText(R.string.btn_stop_stream)
            binding.btnToggleStream.setIconResource(R.drawable.ic_videocam_off)
            binding.btnToggleStream.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_red))
            binding.btnRequestSync.isEnabled = telemetry.state == StreamConnectionState.STREAMING
        } else {
            binding.btnToggleStream.setText(R.string.btn_start_stream)
            binding.btnToggleStream.setIconResource(R.drawable.ic_videocam)
            binding.btnToggleStream.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_blue))
            binding.btnRequestSync.isEnabled = false
        }

        if (telemetry.state == StreamConnectionState.STREAMING) {
            val mbps = telemetry.bitrateKbps / 1000.0
            binding.textTelemetryOverlay.visibility = View.VISIBLE
            binding.textTelemetryOverlay.text = String.format(
                Locale.US,
                "%dx%d • %.1f fps • %.2f Mbps • %s • Rot %d° • Drop %d",
                telemetry.width,
                telemetry.height,
                telemetry.actualFps,
                mbps,
                telemetry.activeCodec,
                telemetry.rotationDegrees,
                telemetry.droppedPackets
            )
        } else if (telemetry.state == StreamConnectionState.ERROR ||
            telemetry.state == StreamConnectionState.RECONNECTING ||
            telemetry.state == StreamConnectionState.CONNECTING
        ) {
            binding.textTelemetryOverlay.visibility = View.VISIBLE
            binding.textTelemetryOverlay.text = telemetry.statusDetail
        } else {
            binding.textTelemetryOverlay.visibility = View.GONE
        }

        val wasActive = prevState == StreamConnectionState.CONNECTING ||
            prevState == StreamConnectionState.STREAMING ||
            prevState == StreamConnectionState.RECONNECTING
        if (wasActive && !active) {
            maybeStartIdlePreview()
        }
    }

    private fun configurePreviewTransform() {
        val tv = binding.texturePreview
        if (!tv.isAvailable) return
        val displayRotation = getDisplayRotationCompat()
        tv.configureTransform(
            viewWidth = tv.width,
            viewHeight = tv.height,
            streamWidth = selectedResolution.width,
            streamHeight = selectedResolution.height,
            displayRotation = displayRotation
        )
    }

    private fun getDisplayRotationCompat(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun updatePermissionOverlay(granted: Boolean) {
        binding.layoutPermissionPrompt.visibility = if (granted) View.GONE else View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun maybeStartIdlePreview() {
        if (!hasCameraPermission() || isStreamActive()) return
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        val lens = selectedLens ?: lensOptions.firstOrNull() ?: return
        if (idleCamera != null) return

        val thread = HandlerThread("IdlePreviewBg").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { cmd -> handler.post(cmd) }
        idleBgThread = thread
        idleBgHandler = handler
        idleCloseLatch = CountDownLatch(1)

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera(
                lens.logicalCameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (isStreamActive() || previewSurface?.isValid != true) {
                            camera.close()
                            return
                        }
                        idleCamera = camera
                        createIdleSession(camera, surface, lens, executor, handler, usePhysical = lens.physicalCameraId != null)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                    }

                    override fun onClosed(camera: CameraDevice) {
                        if (idleCamera === camera) idleCamera = null
                        idleCloseLatch?.countDown()
                    }
                },
                handler
            )
        } catch (_: Exception) {
            idleCloseLatch?.countDown()
        }
    }

    private fun createIdleSession(
        camera: CameraDevice,
        surface: Surface,
        lens: CameraLensOption,
        executor: Executor,
        handler: Handler,
        usePhysical: Boolean
    ) {
        try {
            val outCfg = OutputConfiguration(surface).apply {
                if (usePhysical && lens.physicalCameraId != null) {
                    setPhysicalCameraId(lens.physicalCameraId)
                }
            }
            val sessionCfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outCfg),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (idleCamera !== camera) {
                            session.close()
                            return
                        }
                        idleSession = session
                        try {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                    val chars = manager.getCameraCharacteristics(lens.logicalCameraId)
                                    val zoom = lens.zoomRatio ?: if (lens.wireName == "ultrawide" && lens.physicalCameraId == null) {
                                        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                                        if (range != null && range.lower <= 0.65f) maxOf(range.lower, 0.6f) else null
                                    } else null
                                    if (zoom != null) {
                                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                                    }
                                }
                            }
                            session.setRepeatingRequest(req.build(), null, handler)
                        } catch (_: Exception) {
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (usePhysical && lens.physicalCameraId != null) {
                            createIdleSession(camera, surface, lens, executor, handler, usePhysical = false)
                        }
                    }
                }
            )
            camera.createCaptureSession(sessionCfg)
        } catch (_: Exception) {
        }
    }

    private fun closeIdlePreviewSync() {
        val cam = idleCamera
        val latch = idleCloseLatch
        try {
            idleSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            idleSession?.close()
        } catch (_: Exception) {
        }
        idleSession = null

        if (cam != null) {
            try {
                cam.close()
                latch?.await(800L, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
            idleCamera = null
        }
        idleCloseLatch = null

        idleBgThread?.quitSafely()
        idleBgThread = null
        idleBgHandler = null
    }

    companion object {
        private const val PREFS_NAME = "win10_phonecam_prefs"
        private const val KEY_PAIRING_JSON = "pairing_json"
        private const val KEY_LENS_ID = "lens_id"
        private const val KEY_CODEC = "codec_family"
        private const val KEY_ENCODER_NAME = "encoder_name"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_ROTATION = "rotation"
        private const val KEY_HW_ACCEL_PROMPTED = "hw_accel_prompted"
    }
}
