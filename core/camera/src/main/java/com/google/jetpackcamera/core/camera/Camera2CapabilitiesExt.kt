/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.jetpackcamera.core.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExperimentalZeroShutterLag
import com.google.jetpackcamera.model.LensInfo
import com.google.jetpackcamera.model.ManualCapabilities
import com.google.jetpackcamera.model.RawPhysicalLens
import com.google.jetpackcamera.model.WhiteBalanceMode
import com.google.jetpackcamera.model.buildLensInfos

private const val TAG = "Camera2CapabilitiesExt"

/**
 * Reads the Camera2 characteristics relevant to "Pro" controls for this camera.
 *
 * All lookups are defensive: any missing characteristic simply disables the corresponding
 * capability, so legacy or external cameras degrade to [ManualCapabilities.NONE].
 */
val CameraInfo.manualCapabilities: ManualCapabilities
    @OptIn(ExperimentalCamera2Interop::class, ExperimentalZeroShutterLag::class)
    get() {
        val camera2Info = try {
            Camera2CameraInfo.from(this)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Camera2CameraInfo unavailable; no manual capabilities", e)
            return ManualCapabilities.NONE
        }

        val capabilities = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet() ?: emptySet()
        val isManualSensorSupported =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
        val isRawSupported = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in capabilities
        val isManualPostProcessingSupported =
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capabilities

        val isoRange = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { it.lower..it.upper }
        val exposureTimeRange = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.let { it.lower..it.upper }

        val exposureCompensationRange = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?.let { it.lower..it.upper }
        val exposureCompensationStep = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat() ?: 0f

        val minimumFocusDistance = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f
        val afModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toSet() ?: emptySet()
        // Manual focus needs AF_MODE_OFF and a focuser (min focus distance > 0).
        val manualFocusDistance =
            if (CameraMetadata.CONTROL_AF_MODE_OFF in afModes) minimumFocusDistance else 0f

        val rawAwbModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?.toSet() ?: emptySet()
        val awbModes = rawAwbModes
            .mapNotNull { it.toWhiteBalanceMode() }
            .toSet()
            .ifEmpty { setOf(WhiteBalanceMode.AUTO) }
        val isAwbOffSupported = CameraMetadata.CONTROL_AWB_MODE_OFF in rawAwbModes

        val tonemapModes = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            ?.toSet() ?: emptySet()
        val isTonemapCurveSupported = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes
        val maxTonemapCurvePoints = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0

        val isAeLockSupported = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val isAwbLockSupported = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false

        val isZslSupported = try {
            this.isZslSupported
        } catch (e: Exception) {
            false
        }

        return ManualCapabilities(
            isoRange = isoRange,
            exposureTimeRangeNanos = exposureTimeRange,
            exposureCompensationRange = exposureCompensationRange,
            exposureCompensationStep = exposureCompensationStep,
            minimumFocusDistanceDiopters = manualFocusDistance,
            supportedWhiteBalanceModes = awbModes,
            isManualSensorSupported = isManualSensorSupported,
            isAeLockSupported = isAeLockSupported,
            isAwbLockSupported = isAwbLockSupported,
            isRawSupported = isRawSupported,
            isZslSupported = isZslSupported,
            isManualPostProcessingSupported = isManualPostProcessingSupported,
            isAwbOffSupported = isAwbOffSupported,
            isTonemapCurveSupported = isTonemapCurveSupported,
            maxTonemapCurvePoints = maxTonemapCurvePoints
        )
    }

/**
 * Enumerates the physical lenses behind this (possibly logical) camera and derives the zoom ratio
 * at which each one becomes active, relative to the default lens.
 *
 * On API < 28 (no logical multi-camera) or when the OEM hides the physical ids, this returns a
 * single [LensInfo] for the camera itself so the UI can still label the 1x chip.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun CameraInfo.physicalLenses(context: Context): List<LensInfo> {
    val camera2Info = try {
        Camera2CameraInfo.from(this)
    } catch (e: IllegalArgumentException) {
        return emptyList()
    }
    val logicalId = camera2Info.cameraId
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        ?: return emptyList()

    val logicalCharacteristics = try {
        cameraManager.getCameraCharacteristics(logicalId)
    } catch (e: CameraAccessException) {
        Log.w(TAG, "Cannot read characteristics for $logicalId", e)
        return emptyList()
    } catch (e: IllegalArgumentException) {
        return emptyList()
    }

    val default = logicalCharacteristics.toRawPhysicalLens(logicalId) ?: return emptyList()

    val physicalIds: Set<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            logicalCharacteristics.physicalCameraIds
        } else {
            emptySet()
        }

    val rawLenses = if (physicalIds.isEmpty()) {
        listOf(default)
    } else {
        physicalIds.mapNotNull { id ->
            try {
                cameraManager.getCameraCharacteristics(id).toRawPhysicalLens(id)
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Cannot read characteristics for physical camera $id", e)
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }.ifEmpty { listOf(default) }
    }

    val zoomRange = zoomState.value?.let { it.minZoomRatio..it.maxZoomRatio }

    return buildLensInfos(
        lenses = rawLenses,
        defaultFocalLengthMm = default.focalLengthMm,
        defaultSensorWidthMm = default.sensorWidthMm,
        defaultSensorHeightMm = default.sensorHeightMm,
        zoomRange = zoomRange
    )
}

private fun CameraCharacteristics.toRawPhysicalLens(id: String): RawPhysicalLens? {
    val focalLengths = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    val physicalSize = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val focalLength = focalLengths?.firstOrNull() ?: return null
    if (physicalSize == null || focalLength <= 0f) return null
    return RawPhysicalLens(
        cameraId = id,
        focalLengthMm = focalLength,
        sensorWidthMm = physicalSize.width,
        sensorHeightMm = physicalSize.height
    )
}

/** Maps a Camera2 `CONTROL_AWB_MODE_*` value to the app model, ignoring unknown values. */
internal fun Int.toWhiteBalanceMode(): WhiteBalanceMode? = when (this) {
    CameraMetadata.CONTROL_AWB_MODE_AUTO -> WhiteBalanceMode.AUTO
    CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> WhiteBalanceMode.INCANDESCENT
    CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> WhiteBalanceMode.FLUORESCENT
    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> WhiteBalanceMode.WARM_FLUORESCENT
    CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> WhiteBalanceMode.DAYLIGHT
    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> WhiteBalanceMode.CLOUDY_DAYLIGHT
    CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> WhiteBalanceMode.TWILIGHT
    CameraMetadata.CONTROL_AWB_MODE_SHADE -> WhiteBalanceMode.SHADE
    else -> null
}

/** Maps the app model back to the Camera2 `CONTROL_AWB_MODE_*` value. */
internal fun WhiteBalanceMode.toCamera2AwbMode(): Int = when (this) {
    WhiteBalanceMode.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    WhiteBalanceMode.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
    WhiteBalanceMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
    WhiteBalanceMode.WARM_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
    WhiteBalanceMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
    WhiteBalanceMode.CLOUDY_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    WhiteBalanceMode.TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
    WhiteBalanceMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
}
