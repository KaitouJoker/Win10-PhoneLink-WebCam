package com.win10phonelink.webcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.win10phonelink.webcam.model.CameraLensOption
import java.util.Locale
import kotlin.math.max

object CameraLensCatalog {

    fun discoverLenses(context: Context): List<CameraLensOption> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val options = mutableListOf<CameraLensOption>()

        val logicalIds = try {
            manager.cameraIdList.toList()
        } catch (_: Exception) {
            emptyList()
        }

        var primaryBackId: String? = null
        var primaryBackFocal = 5.0f
        var primaryBackSensorOrientation = 90
        var hasUltraWide = false

        val logicalBackCameras = mutableListOf<Pair<String, CameraCharacteristics>>()

        for (id in logicalIds) {
            val chars = try {
                manager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                continue
            }

            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull() ?: 4.0f

            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> {
                    logicalBackCameras.add(id to chars)
                    if (primaryBackId == null) {
                        primaryBackId = id
                        primaryBackFocal = focal
                        primaryBackSensorOrientation = sensorOrientation
                        options.add(
                            CameraLensOption(
                                id = "logical:$id",
                                logicalCameraId = id,
                                physicalCameraId = null,
                                label = "Back Wide (ID $id, ${formatFocal(focal)})",
                                wireName = "back",
                                isFrontFacing = false,
                                sensorOrientation = sensorOrientation,
                                focalLengthMm = focal
                            )
                        )
                    } else {
                        val isUltraWide = focal < primaryBackFocal * 0.72f
                        val isTele = focal > primaryBackFocal * 1.5f
                        if (isUltraWide) hasUltraWide = true
                        val label = when {
                            isUltraWide -> "Ultra-Wide (ID $id, ${formatFocal(focal)})"
                            isTele -> "Back Telephoto (ID $id, ${formatFocal(focal)})"
                            else -> "Back Aux (ID $id, ${formatFocal(focal)})"
                        }
                        val wireName = if (isUltraWide) "ultrawide" else "back"
                        options.add(
                            CameraLensOption(
                                id = "logical:$id",
                                logicalCameraId = id,
                                physicalCameraId = null,
                                label = label,
                                wireName = wireName,
                                isFrontFacing = false,
                                sensorOrientation = sensorOrientation,
                                focalLengthMm = focal
                            )
                        )
                    }
                }

                CameraCharacteristics.LENS_FACING_FRONT -> {
                    val isFirstFront = options.none { it.isFrontFacing }
                    val label = if (isFirstFront) {
                        "Front (ID $id)"
                    } else {
                        "Front Aux (ID $id)"
                    }
                    options.add(
                        CameraLensOption(
                            id = "logical:$id",
                            logicalCameraId = id,
                            physicalCameraId = null,
                            label = label,
                            wireName = "front",
                            isFrontFacing = true,
                            sensorOrientation = sensorOrientation,
                            focalLengthMm = focal
                        )
                    )
                }
            }
        }

        // Inspect physical sub-cameras inside logical multi-camera clusters (multi-camera Android devices expose
        // Ultra-Wide and telephoto lenses as physicalCameraIds under logical ID "0").
        val seenPhysicalFocal = mutableSetOf<String>()
        for ((logicalId, logicalChars) in logicalBackCameras) {
            val capabilities = logicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)
            val isMultiCam = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
            if (!isMultiCam) continue

            val physicalIds = logicalChars.physicalCameraIds
            for (physicalId in physicalIds) {
                if (logicalIds.contains(physicalId)) continue
                val physChars = try {
                    manager.getCameraCharacteristics(physicalId)
                } catch (_: Exception) {
                    continue
                }
                val physFocal = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: continue
                val physOrientation = physChars.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    ?: primaryBackSensorOrientation

                val isUltraWide = physFocal < primaryBackFocal * 0.72f
                val isTele = physFocal > primaryBackFocal * 1.5f
                if (!isUltraWide && !isTele) continue

                val dedupKey = "${logicalId}_${formatFocal(physFocal)}"
                if (!seenPhysicalFocal.add(dedupKey)) continue

                if (isUltraWide) hasUltraWide = true
                val label = when {
                    isUltraWide -> "Ultra-Wide (Phys $physicalId, ${formatFocal(physFocal)})"
                    else -> "Back Telephoto (Phys $physicalId, ${formatFocal(physFocal)})"
                }
                val wireName = if (isUltraWide) "ultrawide" else "back"
                options.add(
                    CameraLensOption(
                        id = "phys:$logicalId:$physicalId",
                        logicalCameraId = logicalId,
                        physicalCameraId = physicalId,
                        label = label,
                        wireName = wireName,
                        isFrontFacing = false,
                        sensorOrientation = physOrientation,
                        focalLengthMm = physFocal
                    )
                )
            }
        }

        // Fallback for firmwares that hide the physical UW ID and instead expose 0.6x via CONTROL_ZOOM_RATIO
        if (!hasUltraWide && primaryBackId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val chars = logicalBackCameras.firstOrNull { it.first == primaryBackId }?.second
            val zoomRange = chars?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null && zoomRange.lower <= 0.65f) {
                val targetZoom = max(zoomRange.lower, 0.6f)
                options.add(
                    CameraLensOption(
                        id = "zoom:$primaryBackId:$targetZoom",
                        logicalCameraId = primaryBackId,
                        physicalCameraId = null,
                        label = "Ultra-Wide (${String.format(Locale.US, "%.1fx", targetZoom)} Zoom)",
                        wireName = "ultrawide",
                        isFrontFacing = false,
                        sensorOrientation = primaryBackSensorOrientation,
                        focalLengthMm = primaryBackFocal * targetZoom,
                        zoomRatio = targetZoom
                    )
                )
            }
        }

        return options.sortedWith(
            compareBy<CameraLensOption> {
                when (it.wireName) {
                    "back" -> if (it.id == "logical:$primaryBackId") 0 else 3
                    "front" -> 1
                    "ultrawide" -> 2
                    else -> 4
                }
            }.thenBy { it.focalLengthMm }
        )
    }

    private fun formatFocal(focalMm: Float): String {
        return String.format(Locale.US, "%.1fmm", focalMm)
    }
}
