package com.win10phonelink.webcam.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.win10phonelink.webcam.model.EncoderOption

object VideoEncoderCatalog {

    private val SUPPORTED_MIMES = listOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC
    )

    fun discoverEncoders(isKorean: Boolean = false): List<EncoderOption> {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val discovered = LinkedHashMap<String, EncoderOption>()

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) continue

            val isHardware = isHardwareAccelerated(info)
            val vendor = classifyVendor(info.name, isHardware)

            for (mime in info.supportedTypes) {
                val matchedMime = SUPPORTED_MIMES.firstOrNull { it.equals(mime, ignoreCase = true) }
                    ?: continue

                val caps = try {
                    info.getCapabilitiesForType(matchedMime)
                } catch (_: Exception) {
                    continue
                }

                val supportsSurface = caps.colorFormats.any {
                    it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                }
                if (!supportsSurface) continue

                val wireCodec = if (matchedMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "hevc" else "h264"
                val key = "${info.name}|$wireCodec"
                if (discovered.containsKey(key)) continue

                val encCaps = caps.encoderCapabilities
                val supportsCbr = encCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) ?: false
                val supportsVbr = encCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) ?: true
                val videoCaps = caps.videoCapabilities
                val supports1080p60 = videoCaps?.let {
                    it.isSizeSupported(1920, 1080) &&
                        (it.getSupportedFrameRatesFor(1920, 1080)?.upper ?: 0.0) >= 59.0
                } ?: false

                val option = EncoderOption(
                    codecName = info.name,
                    mimeType = matchedMime,
                    wireCodec = wireCodec,
                    isHardware = isHardware,
                    vendorLabel = vendor,
                    supportsCbr = supportsCbr,
                    supportsVbr = supportsVbr,
                    displayName = "",
                    supports1080p60 = supports1080p60
                )
                discovered[key] = option.copy(
                    displayName = option.formatDisplayName(isKorean)
                )
            }
        }

        return discovered.values.sortedWith(
            compareByDescending<EncoderOption> { it.isHardware }
                .thenBy { vendorSortRank(it.codecName, it.isHardware) }
                .thenBy { if (it.wireCodec == "h264") 0 else 1 }
                .thenBy { it.codecName }
        )
    }

    fun discoverHardwareEncoders(isKorean: Boolean = false): List<EncoderOption> {
        val all = discoverEncoders(isKorean)
        val hw = all.filter { it.isHardware }
        return hw.ifEmpty { all }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val lower = info.name.lowercase()
        return !lower.startsWith("omx.google.") &&
            !lower.startsWith("c2.android.") &&
            !lower.contains(".sw.")
    }

    private fun classifyVendor(codecName: String, isHardware: Boolean): String {
        val lower = codecName.lowercase()
        return when {
            lower.startsWith("c2.qti.") || lower.startsWith("omx.qcom.") -> "Qualcomm"
            lower.startsWith("c2.exynos.") || lower.startsWith("omx.exynos.") -> "Exynos"
            lower.startsWith("c2.sec.") || lower.startsWith("omx.sec.") -> "Samsung"
            lower.startsWith("c2.mtk.") || lower.startsWith("omx.mtk.") -> "MediaTek"
            lower.startsWith("omx.hisi.") || lower.startsWith("c2.hisi.") -> "HiSilicon"
            lower.startsWith("c2.google.") && isHardware -> "Google"
            lower.startsWith("c2.android.") || lower.startsWith("omx.google.") -> "Software"
            isHardware -> "Hardware"
            else -> "Software"
        }
    }

    private fun vendorSortRank(codecName: String, isHardware: Boolean): Int {
        if (!isHardware) return 100
        val lower = codecName.lowercase()
        return when {
            lower.startsWith("c2.qti.") || lower.startsWith("c2.exynos.") -> 0
            lower.startsWith("c2.sec.") -> 1
            lower.startsWith("c2.") -> 2
            lower.startsWith("omx.qcom.") || lower.startsWith("omx.exynos.") -> 3
            else -> 4
        }
    }
}
