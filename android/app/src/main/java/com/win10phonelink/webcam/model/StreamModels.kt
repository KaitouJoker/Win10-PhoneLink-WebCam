package com.win10phonelink.webcam.model

enum class StreamConnectionState {
    IDLE,
    CONNECTING,
    STREAMING,
    RECONNECTING,
    ERROR
}

enum class CodecFamily(val wireCodec: String, val label: String) {
    H264("h264", "H.264 / AVC (Low Latency)"),
    HEVC("hevc", "HEVC / H.265 (High Efficiency)");

    override fun toString(): String = label

    companion object {
        fun fromWireCodec(raw: String?): CodecFamily? {
            return when (raw?.trim()?.lowercase()) {
                "hevc", "h265", "h.265" -> HEVC
                "h264", "avc" -> H264
                else -> null
            }
        }
    }
}

enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    HD_720P(1280, 720, "1280×720 (HD)"),
    FHD_1080P(1920, 1080, "1920×1080 (FHD)"),
    QHD_540P(960, 540, "960×540 (qHD)"),
    NHD_360P(640, 360, "640×360 (nHD)");

    override fun toString(): String = label

    companion object {
        fun matchSize(w: Int?, h: Int?): ResolutionPreset? {
            if (w == null || h == null || w <= 0 || h <= 0) return null
            return values().firstOrNull { it.width == w && it.height == h }
                ?: values().minByOrNull { kotlin.math.abs(it.width - w) + kotlin.math.abs(it.height - h) }
        }
    }
}

enum class FpsPreset(val fps: Int, val label: String) {
    FPS_30(30, "30 fps"),
    FPS_60(60, "60 fps");

    override fun toString(): String = label
}

enum class BitratePreset(val bps: Int, val label: String) {
    MBPS_4(4_000_000, "4 Mbps"),
    MBPS_6(6_000_000, "6 Mbps"),
    MBPS_8(8_000_000, "8 Mbps"),
    MBPS_12(12_000_000, "12 Mbps"),
    MBPS_16(16_000_000, "16 Mbps"),
    MBPS_20(20_000_000, "20 Mbps"),
    MBPS_25(25_000_000, "25 Mbps"),
    MBPS_35(35_000_000, "35 Mbps"),
    MBPS_50(50_000_000, "50 Mbps"),
    MBPS_60(60_000_000, "60 Mbps"),
    MBPS_80(80_000_000, "80 Mbps"),
    MBPS_100(100_000_000, "100 Mbps"),
    MBPS_120(120_000_000, "120 Mbps"),
    MBPS_150(150_000_000, "150 Mbps"),
    MBPS_200(200_000_000, "200 Mbps");

    override fun toString(): String = label
}

enum class RotationMode(val fixedDegrees: Int?, val label: String) {
    AUTO(null, "Auto (Sensor)"),
    DEG_0(0, "0° (Landscape)"),
    DEG_90(90, "90° (Portrait)"),
    DEG_180(180, "180°"),
    DEG_270(270, "270°");

    override fun toString(): String = label
}

data class EncoderOption(
    val codecName: String,
    val mimeType: String,
    val wireCodec: String,
    val isHardware: Boolean,
    val vendorLabel: String,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val displayName: String,
    val supports1080p60: Boolean = false,
    val maxBitrateBps: Int = 0
) {
    fun formatDisplayName(isKorean: Boolean): String {
        val badge = if (isHardware) {
            if (isKorean) "[HW 가속]" else "[HW Accel]"
        } else {
            if (isKorean) "[SW 폴백]" else "[SW Fallback]"
        }
        val codecTag = wireCodec.uppercase()
        val hwNote = if (supports1080p60) "$vendorLabel, 1080p60" else vendorLabel
        return if (isHardware) {
            "$badge $codecTag • $codecName ($hwNote)"
        } else {
            "$badge $codecTag • $codecName"
        }
    }

    override fun toString(): String = displayName
}

data class CameraLensOption(
    val id: String,
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val label: String,
    val wireName: String,
    val isFrontFacing: Boolean,
    val sensorOrientation: Int,
    val focalLengthMm: Float,
    val zoomRatio: Float? = null,
    val koLabel: String = label
) {
    fun formatLabel(isKorean: Boolean): String = if (isKorean) koLabel else label

    override fun toString(): String = label
}

data class StreamTelemetry(
    val state: StreamConnectionState = StreamConnectionState.IDLE,
    val statusDetail: String = "",
    val actualFps: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val rotationDegrees: Int = 0,
    val activeLensLabel: String = "",
    val activeEncoderName: String = "",
    val activeCodec: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val packetsSent: Long = 0L,
    val droppedPackets: Long = 0L
)
