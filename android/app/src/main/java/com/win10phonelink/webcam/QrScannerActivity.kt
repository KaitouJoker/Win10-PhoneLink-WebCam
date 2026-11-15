package com.win10phonelink.webcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.win10phonelink.webcam.databinding.ActivityQrScannerBinding
import com.win10phonelink.webcam.net.PairingPayload
import java.util.EnumMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var analysisExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchEnabled: Boolean = false

    private val scanHandled = AtomicBoolean(false)
    private var lastInvalidToastMs: Long = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraScanner()
            } else {
                Toast.makeText(this, R.string.permission_camera_required, Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnToggleTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (cam.cameraInfo.hasFlashUnit()) {
                torchEnabled = !torchEnabled
                cam.cameraControl.enableTorch(torchEnabled)
            }
        }

        binding.btnManualFallback.setOnClickListener {
            setResult(RESULT_MANUAL_FALLBACK)
            finish()
        }

        binding.btnCloseScanner.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    private fun startCameraScanner() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindCameraUseCases(provider)
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "CameraX initialization failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    setResult(RESULT_CANCELED)
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .also { it.surfaceProvider = binding.previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor, QrFrameAnalyzer())
            }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    private inner class QrFrameAnalyzer : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader().apply {
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
            hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
            hints[DecodeHintType.TRY_HARDER] = true
            setHints(hints)
        }

        private var reusableYPlane = ByteArray(0)

        override fun analyze(image: ImageProxy) {
            try {
                if (scanHandled.get()) return
                val yPlane = image.planes.firstOrNull() ?: return
                val width = image.width
                val height = image.height
                if (width <= 0 || height <= 0) return

                val needed = width * height
                if (reusableYPlane.size != needed) {
                    reusableYPlane = ByteArray(needed)
                }

                val buffer = yPlane.buffer
                val rowStride = yPlane.rowStride
                if (rowStride == width && buffer.remaining() >= needed) {
                    buffer.rewind()
                    buffer.get(reusableYPlane, 0, needed)
                } else {
                    val limit = buffer.limit()
                    for (row in 0 until height) {
                        val pos = row * rowStride
                        if (pos + width > limit) break
                        buffer.position(pos)
                        buffer.get(reusableYPlane, row * width, width)
                    }
                }

                val source = PlanarYUVLuminanceSource(
                    reusableYPlane,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false
                )

                val decodedText = tryDecode(source) ?: tryDecode(source.invert())
                if (decodedText != null) {
                    val pairing = PairingPayload.fromQrJson(decodedText)
                    if (pairing != null) {
                        if (scanHandled.compareAndSet(false, true)) {
                            runOnUiThread {
                                val data = Intent().putExtra(
                                    EXTRA_PAIRING_JSON,
                                    pairing.toJsonString()
                                )
                                setResult(RESULT_OK, data)
                                finish()
                            }
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastInvalidToastMs > 2500L) {
                            lastInvalidToastMs = now
                            runOnUiThread {
                                Toast.makeText(
                                    this@QrScannerActivity,
                                    R.string.qr_invalid_payload,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            } finally {
                image.close()
            }
        }

        private fun tryDecode(source: com.google.zxing.LuminanceSource): String? {
            return try {
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                reader.decodeWithState(bitmap).text
            } catch (_: NotFoundException) {
                null
            } catch (_: Exception) {
                null
            } finally {
                reader.reset()
            }
        }
    }

    companion object {
        const val EXTRA_PAIRING_JSON = "extra_pairing_json"
        const val RESULT_MANUAL_FALLBACK = RESULT_FIRST_USER + 1
    }
}
