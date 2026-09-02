/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState as CXCameraState
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.video.AudioStats
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
import androidx.camera.video.VideoRecordEvent.Finalize.ERROR_NONE
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toFile
import androidx.lifecycle.asFlow
import com.google.jetpackcamera.core.common.FilePathGenerator
import com.google.jetpackcamera.model.AspectRatio
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.DeviceRotation
import com.google.jetpackcamera.model.DynamicRange
import com.google.jetpackcamera.model.ExposureInfo
import com.google.jetpackcamera.model.FlashMode
import com.google.jetpackcamera.model.Illuminant
import com.google.jetpackcamera.model.ImageOutputFormat
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.model.LowLightBoostState
import com.google.jetpackcamera.model.ManualCapabilities
import com.google.jetpackcamera.model.ManualControls
import com.google.jetpackcamera.model.SaveLocation
import com.google.jetpackcamera.model.StabilizationMode
import com.google.jetpackcamera.model.TARGET_FPS_AUTO
import com.google.jetpackcamera.model.TestPattern
import com.google.jetpackcamera.model.VideoQuality
import com.google.jetpackcamera.model.VideoQuality.FHD
import com.google.jetpackcamera.model.VideoQuality.HD
import com.google.jetpackcamera.model.VideoQuality.SD
import com.google.jetpackcamera.model.VideoQuality.UHD
import com.google.jetpackcamera.model.WhiteBalanceMode
import com.google.jetpackcamera.model.buildShadowsTonemapCurve
import com.google.jetpackcamera.model.kelvinToRggbGains
import com.google.jetpackcamera.settings.model.CameraConstraints
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executor
import javax.inject.Provider
import kotlin.coroutines.ContinuationInterceptor
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.AtomicLong
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraSession"
private val QUALITY_RANGE_MAP = mapOf(
    UHD to Range.create(2160, 4319),
    FHD to Range.create(1080, 1439),
    HD to Range.create(720, 1079),
    SD to Range.create(241, 719)
)

context(CameraSessionContext)
@ExperimentalCamera2Interop
internal suspend fun runSingleCameraSession(
    sessionSettings: PerpetualSessionSettings.SingleCamera,
    cameraConstraints: CameraConstraints?,
    // TODO(tm): ImageCapture should go through an event channel like VideoCapture
    onImageCaptureCreated: (ImageCapture) -> Unit = {}
) = coroutineScope {
    Log.d(TAG, "Starting new single camera session")
    // CameraX Extensions (Night/Bokeh/HDR/Face Retouch) only support Preview + ImageCapture.
    // When an extension is active we bind through the extension-enabled selector, skip the
    // video pipeline and any CameraEffect, and fall back to plain JPEG without ZSL.
    val isExtensionEnabled = sessionSettings.extensionMode.isEnabled
    if (isExtensionEnabled) {
        Log.d(TAG, "Session running with CameraX extension ${sessionSettings.extensionMode}")
    }
    val initialCameraSelector = extensionsManager.extensionEnabledSelector(
        transientSettings.filterNotNull().first().primaryLensFacing.toCameraSelector(),
        sessionSettings.extensionMode
    )

    // only create video use case in standard or video_only (never alongside an extension)
    val videoCaptureUseCase = when (sessionSettings.captureMode) {
        CaptureMode.STANDARD, CaptureMode.VIDEO_ONLY ->
            if (isExtensionEnabled) null else createVideoUseCase(
                cameraProvider.getCameraInfo(initialCameraSelector),
                sessionSettings.aspectRatio,
                sessionSettings.targetFrameRate,
                sessionSettings.stabilizationMode,
                sessionSettings.dynamicRange,
                sessionSettings.videoQuality,
                backgroundDispatcher
            )

        else -> {
            null
        }
    }

    launch {
        processVideoControlEvents(
            videoCaptureUseCase,
            captureTypeSuffix = if (sessionSettings.activeCameraEffect != null) {
                "SingleStream"
            } else {
                "MultiStream"
            }
        )
    }

    transientSettings
        .filterNotNull()
        .distinctUntilChanged { old, new ->
            (
                old.primaryLensFacing == new.primaryLensFacing &&
                    !(
                        (old.flashMode == FlashMode.LOW_LIGHT_BOOST) xor
                            (new.flashMode == FlashMode.LOW_LIGHT_BOOST)
                        )
                )
        }
        .collectLatest { currentTransientSettings ->
            coroutineScope sessionScope@{
                // CameraX requires unbind/bind to happen on the main thread. The session scope
                // is normally Main.immediate already, but we make it explicit so a caller using a
                // different dispatcher can't accidentally trigger an IllegalStateException.
                withContext(Dispatchers.Main.immediate) {
                    cameraProvider.unbindAll()
                }
                val currentCameraSelector = extensionsManager.extensionEnabledSelector(
                    currentTransientSettings.primaryLensFacing.toCameraSelector(),
                    sessionSettings.extensionMode
                )
                val cameraInfo = cameraProvider.getCameraInfo(currentCameraSelector)
                val camera2Info = Camera2CameraInfo.from(cameraInfo)
                val cameraId = camera2Info.cameraId

                var cameraEffect: CameraEffect? = null
                var captureResults: MutableStateFlow<TotalCaptureResult?>? = null
                if (currentTransientSettings.flashMode == FlashMode.LOW_LIGHT_BOOST) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        cameraConstraints?.supportedIlluminants?.contains(
                            Illuminant.LOW_LIGHT_BOOST_CAMERA_EFFECT
                        ) == true && lowLightBoostEffectProvider != null
                    ) {
                        captureResults = MutableStateFlow(null)
                        cameraEffect = lowLightBoostEffectProvider.create(
                            cameraId = cameraId,
                            captureResults = captureResults,
                            coroutineScope = this@sessionScope,
                            onSceneBrightnessChanged = { boostStrength ->
                                val strength = LowLightBoostState.Active(strength = boostStrength)
                                currentCameraState.update { old ->
                                    if (old.lowLightBoostState != strength) {
                                        old.copy(lowLightBoostState = strength)
                                    } else {
                                        old
                                    }
                                }
                            },
                            onLowLightBoostError = { e ->
                                Log.w(TAG, "Emitting LLB Error", e)
                                currentCameraState.update { old ->
                                    old.copy(lowLightBoostState = LowLightBoostState.Error(e))
                                }
                            }
                        )
                    }
                }
                if (cameraEffect == null && !isExtensionEnabled) {
                    sessionSettings.activeCameraEffect?.let { key ->
                        cameraEffect = cameraEffectProviders[key]?.createEffect(this@sessionScope)
                    }
                }
                val useCaseGroup = createUseCaseGroup(
                    cameraInfo = cameraInfo,
                    videoCaptureUseCase = videoCaptureUseCase,
                    initialTransientSettings = currentTransientSettings,
                    stabilizationMode = sessionSettings.stabilizationMode,
                    aspectRatio = sessionSettings.aspectRatio,
                    imageFormat = if (isExtensionEnabled) {
                        ImageOutputFormat.JPEG
                    } else {
                        sessionSettings.imageFormat
                    },
                    captureMode = sessionSettings.captureMode,
                    effect = if (isExtensionEnabled) null else cameraEffect,
                    captureResults = captureResults,
                    isExtensionEnabled = isExtensionEnabled
                ).apply {
                    getImageCapture()?.let(onImageCaptureCreated)
                }

                cameraProvider.runWith(
                    currentCameraSelector,
                    useCaseGroup
                ) { camera ->
                    Log.d(TAG, "Camera session started")
                    launch {
                        processFocusMeteringEvents(
                            camera.cameraInfo,
                            camera.cameraControl
                        )
                    }

                    launch {
                        camera.cameraInfo.torchState.asFlow().collectLatest { torchState ->
                            currentCameraState.update { old ->
                                old.copy(isTorchEnabled = torchState == TorchState.ON)
                            }
                        }
                    }

                    if (videoCaptureUseCase != null) {
                        val videoQuality = getVideoQualityFromResolution(
                            videoCaptureUseCase.resolutionInfo?.resolution
                        )
                        if (videoQuality != sessionSettings.videoQuality) {
                            Log.e(
                                TAG,
                                "Failed to select video quality: $sessionSettings.videoQuality. " +
                                    "Fallback: $videoQuality"
                            )
                        }
                        launch {
                            currentCameraState.update { old ->
                                old.copy(
                                    videoQualityInfo = VideoQualityInfo(
                                        videoQuality,
                                        getWidthFromCropRect(
                                            videoCaptureUseCase.resolutionInfo?.cropRect
                                        ),
                                        getHeightFromCropRect(
                                            videoCaptureUseCase.resolutionInfo?.cropRect
                                        )
                                    )
                                )
                            }
                        }
                    }

                    // Update CameraState to reflect when camera is running
                    launch {
                        camera.cameraInfo.cameraState
                            .asFlow()
                            .filterNotNull()
                            .distinctUntilChanged()
                            .onCompletion {
                                currentCameraState.update { old ->
                                    old.copy(
                                        isCameraRunning = false
                                    )
                                }
                            }
                            .collectLatest { cameraState ->
                                currentCameraState.update { old ->
                                    old.copy(
                                        isCameraRunning =
                                        cameraState.type == CXCameraState.Type.OPEN
                                    )
                                }
                            }
                    }

                    // Update CameraState to mirror current ZoomState
                    launch {
                        camera.cameraInfo.zoomState
                            .asFlow()
                            .filterNotNull()
                            .distinctUntilChanged()
                            .onCompletion {
                                // reset current camera state when changing cameras.
                                currentCameraState.update { old ->
                                    old.copy(
                                        zoomRatios = emptyMap(),
                                        linearZoomScales = emptyMap()
                                    )
                                }
                            }
                            .collectLatest { zoomState ->
                                // TODO(b/405987189): remove checks after buggy zoomState is fixed
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                    if (zoomState.zoomRatio != 1.0f ||
                                        zoomState.zoomRatio == currentTransientSettings
                                            .zoomRatios[currentTransientSettings.primaryLensFacing]
                                    ) {
                                        currentCameraState.update { old ->
                                            old.copy(
                                                zoomRatios = old.zoomRatios
                                                    .toMutableMap()
                                                    .apply {
                                                        put(
                                                            camera.cameraInfo.appLensFacing,
                                                            zoomState.zoomRatio
                                                        )
                                                    }.toMap(),
                                                linearZoomScales = old.linearZoomScales
                                                    .toMutableMap()
                                                    .apply {
                                                        put(
                                                            camera.cameraInfo.appLensFacing,
                                                            zoomState.linearZoom
                                                        )
                                                    }.toMap()
                                            )
                                        }
                                    }
                                }
                            }
                    }

                    applyDeviceRotation(currentTransientSettings.deviceRotation, useCaseGroup)
                    processTransientSettingEvents(
                        camera,
                        cameraConstraints,
                        useCaseGroup,
                        currentTransientSettings,
                        transientSettings,
                        sessionSettings
                    )
                }
            }
        }
}

context(CameraSessionContext)
@OptIn(ExperimentalCamera2Interop::class)
internal suspend fun processTransientSettingEvents(
    camera: Camera,
    cameraConstraints: CameraConstraints?,
    useCaseGroup: UseCaseGroup,
    initialTransientSettings: TransientSessionSettings,
    transientSettings: StateFlow<TransientSessionSettings?>,
    sessionSettings: PerpetualSessionSettings.SingleCamera?
) {
    // Immediately Apply camera zoom from current settings when opening a new camera
    camera.cameraControl.setZoomRatio(
        initialTransientSettings.zoomRatios[camera.cameraInfo.appLensFacing] ?: 1f
    )

    val camera2OptionsBuilder = CaptureRequestOptions.Builder()
    updateCamera2RequestOptions(
        camera,
        cameraConstraints,
        null,
        initialTransientSettings,
        sessionSettings,
        camera2OptionsBuilder
    )

    var prevTransientSettings = initialTransientSettings
    val isFrontFacing = camera.cameraInfo.appLensFacing == LensFacing.FRONT
    var torchOn = false
    fun setTorch(newTorchOn: Boolean) {
        if (newTorchOn != torchOn) {
            camera.cameraControl.enableTorch(newTorchOn)
            torchOn = newTorchOn
        }
    }
    combine(
        transientSettings.filterNotNull(),
        currentCameraState.asStateFlow().transform { emit(it.videoRecordingState) }
    ) { newTransientSettings, videoRecordingState ->
        return@combine Pair(newTransientSettings, videoRecordingState)
    }.collect { transientPair ->
        val newTransientSettings = transientPair.first
        val videoRecordingState = transientPair.second

        // todo(): handle torch on Auto FlashMode
        // enable torch only while recording is in progress
        if ((videoRecordingState !is VideoRecordingState.Inactive) &&
            newTransientSettings.flashMode == FlashMode.ON &&
            !isFrontFacing
        ) {
            setTorch(true)
        } else {
            setTorch(false)
        }

        // apply camera torch mode to image capture
        useCaseGroup.getImageCapture()?.let { imageCapture ->
            if (prevTransientSettings.flashMode != newTransientSettings.flashMode) {
                setFlashModeInternal(
                    imageCapture = imageCapture,
                    flashMode = newTransientSettings.flashMode,
                    isFrontFacing = camera.cameraInfo.appLensFacing == LensFacing.FRONT
                )
            }
        }

        if (prevTransientSettings.deviceRotation
            != newTransientSettings.deviceRotation
        ) {
            Log.d(
                TAG,
                "Updating device rotation from " +
                    "${prevTransientSettings.deviceRotation} -> " +
                    "${newTransientSettings.deviceRotation}"
            )
            applyDeviceRotation(newTransientSettings.deviceRotation, useCaseGroup)
        }

        // setzoomratio when the primary zoom value changes.
        if (prevTransientSettings.primaryLensFacing == newTransientSettings.primaryLensFacing &&
            prevTransientSettings.zoomRatios[prevTransientSettings.primaryLensFacing] !=
            newTransientSettings.zoomRatios[newTransientSettings.primaryLensFacing]
        ) {
            newTransientSettings.primaryLensFacing.let {
                camera.cameraControl.setZoomRatio(newTransientSettings.zoomRatios[it] ?: 1f)
            }
        }

        updateCamera2RequestOptions(
            camera,
            cameraConstraints,
            prevTransientSettings,
            newTransientSettings,
            sessionSettings,
            camera2OptionsBuilder
        )

        prevTransientSettings = newTransientSettings
    }
}

context(CameraSessionContext)
@ExperimentalCamera2Interop
private suspend fun updateCamera2RequestOptions(
    camera: Camera,
    cameraConstraints: CameraConstraints?,
    prevTransientSettings: TransientSessionSettings?,
    newTransientSettings: TransientSessionSettings,
    sessionSettings: PerpetualSessionSettings.SingleCamera?,
    optionsBuilder: CaptureRequestOptions.Builder
) {
    var needsUpdate = false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
        prevTransientSettings?.flashMode != newTransientSettings.flashMode
    ) {
        when (newTransientSettings.flashMode) {
            FlashMode.LOW_LIGHT_BOOST -> {
                if (cameraConstraints?.supportedIlluminants?.contains(
                        Illuminant.LOW_LIGHT_BOOST_AE_MODE
                    ) == true
                ) {
                    Log.d(
                        TAG,
                        "Setting LLB with " +
                            "CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY"
                    )
                    val captureRequestOptions = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
                        )
                        .build()

                    Camera2CameraControl.from(camera.cameraControl)
                        .addCaptureRequestOptions(captureRequestOptions)
                }
            }

            else -> {
                // Manual exposure owns CONTROL_AE_MODE (OFF); don't wipe it on a flash change.
                if (!newTransientSettings.manualControls.isManualExposure) {
                    optionsBuilder.clearCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE)
                }
            }
        }
        needsUpdate = true
    }

    val newTestPattern = newTransientSettings.testPattern
    if (prevTransientSettings?.testPattern != newTestPattern) {
        val (mode: Int?, data: IntArray?) = when (newTestPattern) {
            TestPattern.Off -> Pair(null, null)
            TestPattern.ColorBars -> Pair(CameraMetadata.SENSOR_TEST_PATTERN_MODE_COLOR_BARS, null)
            TestPattern.ColorBarsFadeToGray -> Pair(
                CameraMetadata.SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY,
                null
            )

            TestPattern.PN9 -> Pair(CameraMetadata.SENSOR_TEST_PATTERN_MODE_PN9, null)
            TestPattern.Custom1 -> Pair(CameraMetadata.SENSOR_TEST_PATTERN_MODE_CUSTOM1, null)
            is TestPattern.SolidColor -> {
                Pair(
                    CameraMetadata.SENSOR_TEST_PATTERN_MODE_SOLID_COLOR,
                    intArrayOf(
                        newTestPattern.red.toInt(),
                        newTestPattern.greenEven.toInt(),
                        newTestPattern.greenOdd.toInt(),
                        newTestPattern.blue.toInt()
                    )
                )
            }
        }
        if (mode != null) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_TEST_PATTERN_MODE,
                mode
            )
        } else {
            optionsBuilder.clearCaptureRequestOption(CaptureRequest.SENSOR_TEST_PATTERN_MODE)
        }

        if (data != null) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_TEST_PATTERN_DATA,
                data
            )
        } else {
            optionsBuilder.clearCaptureRequestOption(CaptureRequest.SENSOR_TEST_PATTERN_DATA)
        }
        needsUpdate = true
    }

    val newManualControls = newTransientSettings.manualControls
    if (prevTransientSettings?.manualControls != newManualControls) {
        applyManualControls(
            optionsBuilder = optionsBuilder,
            controls = newManualControls,
            // Keep LLB's AE mode untouched: manual exposure and LLB are mutually exclusive in UI.
            preserveAeMode = newTransientSettings.flashMode == FlashMode.LOW_LIGHT_BOOST,
            lastExposureInfo = currentCameraState.value.exposureInfo,
            capabilities = cameraConstraints?.manualCapabilities ?: ManualCapabilities.NONE
        )
        needsUpdate = true
    }

    if (needsUpdate) {
        Camera2CameraControl.from(camera.cameraControl)
            .setCaptureRequestOptions(optionsBuilder.build())
    }
}

/**
 * Translates [ManualControls] into Camera2 capture request options on [optionsBuilder].
 *
 * Every key is either set (manual) or cleared (back to CameraX defaults / auto), so toggling a
 * control off fully restores automatic behaviour without needing a session restart.
 *
 * Exposure: when ISO or shutter is pinned, `CONTROL_AE_MODE` is set to OFF and *both*
 * `SENSOR_SENSITIVITY` and `SENSOR_EXPOSURE_TIME` are supplied (Camera2 requires both in
 * manual mode). The missing one is taken from the last AE readout so the image brightness stays
 * continuous when the user pins just one value, exactly like Pixel's Pro controls.
 *
 * White balance: a kelvin value switches AWB off and drives `COLOR_CORRECTION_GAINS` with gains
 * derived from the colour temperature (identity colour transform); a preset maps to the matching
 * `CONTROL_AWB_MODE_*`. Shadows (Dual Exposure) install a `TONEMAP_MODE_CONTRAST_CURVE` built by
 * [buildShadowsTonemapCurve] sized to the HAL's `TONEMAP_MAX_CURVE_POINTS`.
 */
@ExperimentalCamera2Interop
internal fun applyManualControls(
    optionsBuilder: CaptureRequestOptions.Builder,
    controls: ManualControls,
    preserveAeMode: Boolean,
    lastExposureInfo: ExposureInfo,
    capabilities: ManualCapabilities = ManualCapabilities.NONE
) {
    // --- Exposure (ISO + shutter) ---
    val iso = controls.resolvedIso(lastExposureInfo.iso)
    val exposureTime = controls.resolvedExposureTimeNanos(lastExposureInfo.exposureTimeNanos)
    if (controls.isManualExposure && iso != null && exposureTime != null) {
        optionsBuilder
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CameraMetadata.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
            // AE lock and EV compensation are meaningless with AE off.
            .clearCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK)
            .clearCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)
    } else {
        if (!preserveAeMode) {
            optionsBuilder.clearCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE)
        }
        optionsBuilder
            .clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
            .clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)

        // --- Exposure compensation (only meaningful while AE is on) ---
        val ev = controls.exposureCompensationIndex
        if (ev != null && ev != 0) {
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                ev
            )
        } else {
            optionsBuilder.clearCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION
            )
        }

        // --- AE lock ---
        if (controls.aeLock) {
            optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        } else {
            optionsBuilder.clearCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK)
        }
    }

    // --- White balance ---
    val wbKelvin = controls.whiteBalanceKelvin?.takeIf { capabilities.supportsWhiteBalanceKelvin }
    val wb = controls.whiteBalance
    if (wbKelvin != null) {
        val gains = kelvinToRggbGains(wbKelvin)
        optionsBuilder
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(gains[0], gains[1], gains[2], gains[3])
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                IDENTITY_COLOR_TRANSFORM
            )
            .clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK)
    } else {
        optionsBuilder
            .clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE)
            .clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS)
            .clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM)
        if (wb != null && wb != WhiteBalanceMode.AUTO) {
            optionsBuilder
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.toCamera2AwbMode())
                .clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK)
        } else {
            optionsBuilder.clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE)
            if (controls.awbLock) {
                optionsBuilder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            } else {
                optionsBuilder.clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK)
            }
        }
    }

    // --- Shadows (Dual Exposure) ---
    val shadows = controls.shadowsBoost
    if (shadows != null && shadows != 0f && capabilities.supportsShadowsBoost) {
        val points = capabilities.maxTonemapCurvePoints.coerceIn(
            ManualCapabilities.MIN_TONEMAP_CURVE_POINTS,
            MAX_TONEMAP_CURVE_POINTS_USED
        )
        val curve = buildShadowsTonemapCurve(shadows, points)
        optionsBuilder
            .setCaptureRequestOption(
                CaptureRequest.TONEMAP_MODE,
                CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
            )
            .setCaptureRequestOption(
                CaptureRequest.TONEMAP_CURVE,
                TonemapCurve(curve, curve, curve)
            )
    } else {
        optionsBuilder
            .clearCaptureRequestOption(CaptureRequest.TONEMAP_MODE)
            .clearCaptureRequestOption(CaptureRequest.TONEMAP_CURVE)
    }

    // --- Focus ---
    val focus = controls.focusDistanceDiopters
    if (focus != null) {
        optionsBuilder
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
    } else {
        optionsBuilder
            .clearCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE)
            .clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
    }
}

/**
 * Upper bound on tone curve points we send even if the HAL advertises more: 64 points is more than
 * enough to sample a smooth power curve and keeps each capture request small.
 */
private const val MAX_TONEMAP_CURVE_POINTS_USED = 64

/**
 * Identity 3x3 colour transform (rational elements as `numerator, denominator` pairs). Combined
 * with [CaptureRequest.COLOR_CORRECTION_GAINS] it makes white balance depend only on the gains.
 */
private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
    intArrayOf(
        1, 1, 0, 1, 0, 1,
        0, 1, 1, 1, 0, 1,
        0, 1, 0, 1, 1, 1
    )
)

internal fun applyDeviceRotation(deviceRotation: DeviceRotation, useCaseGroup: UseCaseGroup) {
    val targetRotation = deviceRotation.toUiSurfaceRotation()
    useCaseGroup.useCases.forEach {
        when (it) {
            is Preview -> {
                // Preview's target rotation should not be updated with device rotation.
                // Instead, preview rotation should match the display rotation.
                // When Preview is created, it is initialized with the display rotation.
                // This will need to be updated separately if the display rotation is not
                // locked. Currently the app is locked to portrait orientation.
            }

            is ImageCapture -> {
                it.targetRotation = targetRotation
            }

            is VideoCapture<*> -> {
                it.targetRotation = targetRotation
            }
        }
    }
}

context(CameraSessionContext)
internal fun createUseCaseGroup(
    cameraInfo: CameraInfo,
    initialTransientSettings: TransientSessionSettings,
    stabilizationMode: StabilizationMode,
    aspectRatio: AspectRatio,
    videoCaptureUseCase: VideoCapture<Recorder>?,
    imageFormat: ImageOutputFormat,
    captureMode: CaptureMode,
    effect: CameraEffect? = null,
    captureResults: MutableStateFlow<TotalCaptureResult?>? = null,
    isExtensionEnabled: Boolean = false
): UseCaseGroup {
    val previewUseCase =
        createPreviewUseCase(
            cameraInfo,
            aspectRatio,
            stabilizationMode,
            captureResults
        )

    // only create image use case in image or standard
    val imageCaptureUseCase = if (captureMode != CaptureMode.VIDEO_ONLY) {
        createImageUseCase(
            cameraInfo = cameraInfo,
            aspectRatio = aspectRatio,
            imageFormat = imageFormat,
            // ZSL only makes sense for pure photo capture with no effects/HDR/RAW/video pipeline:
            // CameraX ignores it when VideoCapture or an effect is bound, and it conflicts with
            // Ultra HDR and RAW output. Enabling it in IMAGE_ONLY gives Pixel-like instant shutter.
            // HEIC is a plain JPEG at the HAL level, so it keeps ZSL. Extensions run their own
            // multi-frame pipeline, so ZSL is never requested alongside them.
            preferZeroShutterLag = captureMode == CaptureMode.IMAGE_ONLY &&
                !isExtensionEnabled &&
                videoCaptureUseCase == null &&
                effect == null &&
                (imageFormat == ImageOutputFormat.JPEG || imageFormat == ImageOutputFormat.HEIC)
        )
    } else {
        null
    }

    imageCaptureUseCase?.let {
        setFlashModeInternal(
            imageCapture = imageCaptureUseCase,
            flashMode = initialTransientSettings.flashMode,
            isFrontFacing = cameraInfo.appLensFacing == LensFacing.FRONT
        )
    }

    return UseCaseGroup.Builder().apply {
        Log.d(
            TAG,
            "Setting initial device rotation to ${initialTransientSettings.deviceRotation}"
        )
        setViewPort(
            ViewPort.Builder(
                Rational(aspectRatio.numerator, aspectRatio.denominator),
                // Initialize rotation to Preview's rotation, which comes from Display rotation
                previewUseCase.targetRotation
            ).build()
        )
        addUseCase(previewUseCase)

        // image and video use cases are only created if supported by the configuration
        imageCaptureUseCase?.let { addUseCase(imageCaptureUseCase) }
        videoCaptureUseCase?.let { addUseCase(videoCaptureUseCase) }

        effect?.let { addEffect(it) }
    }.build()
}

private fun getVideoQualityFromResolution(resolution: Size?): VideoQuality =
    resolution?.let { res ->
        QUALITY_RANGE_MAP.firstNotNullOfOrNull {
            if (it.value.contains(res.height)) it.key else null
        }
    } ?: VideoQuality.UNSPECIFIED

/**
 * Width of the crop rect in sensor coordinates (`right - left`), or 0 if unknown.
 */
internal fun getWidthFromCropRect(cropRect: Rect?): Int {
    if (cropRect == null) {
        return 0
    }
    return abs(cropRect.right - cropRect.left)
}

/**
 * Height of the crop rect in sensor coordinates (`bottom - top`), or 0 if unknown.
 */
internal fun getHeightFromCropRect(cropRect: Rect?): Int {
    if (cropRect == null) {
        return 0
    }
    return abs(cropRect.bottom - cropRect.top)
}

@OptIn(ExperimentalZeroShutterLag::class)
private fun createImageUseCase(
    cameraInfo: CameraInfo,
    aspectRatio: AspectRatio,
    imageFormat: ImageOutputFormat,
    preferZeroShutterLag: Boolean = false
): ImageCapture {
    val builder = ImageCapture.Builder()
    builder.setResolutionSelector(
        getResolutionSelector(cameraInfo.sensorLandscapeRatio, aspectRatio)
    )
    val outputFormat = imageFormat.toCameraXOutputFormat()
    if (outputFormat != ImageCapture.OUTPUT_FORMAT_JPEG) {
        builder.setOutputFormat(outputFormat)
    }
    val zslSupported = try {
        cameraInfo.isZslSupported
    } catch (e: Exception) {
        false
    }
    if (preferZeroShutterLag && zslSupported) {
        Log.d(TAG, "Enabling zero-shutter-lag image capture")
        builder.setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
    } else {
        // Favour quality over latency for the "Pixel look" (full HAL post-processing).
        builder.setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    }
    return builder.build()
}

internal fun createVideoUseCase(
    cameraInfo: CameraInfo,
    aspectRatio: AspectRatio,
    targetFrameRate: Int,
    stabilizationMode: StabilizationMode,
    dynamicRange: DynamicRange,
    videoQuality: VideoQuality,
    backgroundDispatcher: CoroutineDispatcher
): VideoCapture<Recorder> {
    val sensorLandscapeRatio = cameraInfo.sensorLandscapeRatio
    val recorder = Recorder.Builder()
        .setAspectRatio(
            getAspectRatioForUseCase(sensorLandscapeRatio, aspectRatio)
        )
        .setExecutor(backgroundDispatcher.asExecutor())
        .apply {
            videoQuality.toQuality()?.let { quality ->
                // If the requested quality is unsupported, fall back to the next lower quality
                // (or a higher one if there is nothing lower) instead of failing to bind.
                setQualitySelector(
                    QualitySelector.from(
                        quality,
                        FallbackStrategy.lowerQualityOrHigherThan(quality)
                    )
                )
            }
        }.build()

    return VideoCapture.Builder(recorder).apply {
        // set video stabilization
        if (stabilizationMode == StabilizationMode.HIGH_QUALITY) {
            setVideoStabilizationEnabled(true)
        }
        // set target fps
        if (targetFrameRate != TARGET_FPS_AUTO) {
            setTargetFrameRate(Range(targetFrameRate, targetFrameRate))
        }

        setDynamicRange(dynamicRange.toCXDynamicRange())
    }.build()
}

private fun getAspectRatioForUseCase(sensorLandscapeRatio: Float, aspectRatio: AspectRatio): Int =
    when (aspectRatio) {
        AspectRatio.THREE_FOUR -> androidx.camera.core.AspectRatio.RATIO_4_3
        AspectRatio.NINE_SIXTEEN -> androidx.camera.core.AspectRatio.RATIO_16_9
        else -> {
            // Choose the aspect ratio which maximizes FOV by being closest to the sensor ratio
            if (
                abs(sensorLandscapeRatio - AspectRatio.NINE_SIXTEEN.toLandscapeFloat()) <
                abs(sensorLandscapeRatio - AspectRatio.THREE_FOUR.toLandscapeFloat())
            ) {
                androidx.camera.core.AspectRatio.RATIO_16_9
            } else {
                androidx.camera.core.AspectRatio.RATIO_4_3
            }
        }
    }

context(CameraSessionContext)
private fun createPreviewUseCase(
    cameraInfo: CameraInfo,
    aspectRatio: AspectRatio,
    stabilizationMode: StabilizationMode,
    captureResults: MutableStateFlow<TotalCaptureResult?>? = null
): Preview = Preview.Builder().apply {
    updateCameraStateWithCaptureResults(
        targetCameraInfo = cameraInfo,
        captureResults = captureResults
    )

    // set preview stabilization
    when (stabilizationMode) {
        StabilizationMode.ON -> setPreviewStabilizationEnabled(true)
        StabilizationMode.OPTICAL -> setOpticalStabilizationModeEnabled(true)
        StabilizationMode.OFF -> {
            setOpticalStabilizationModeEnabled(false)
            // Setting this to false in Preview use case will disable video stabilization, even in
            //  IMAGE_ONLY mode where there isn't a video capture use case attached
            setPreviewStabilizationEnabled(false)
        }
        StabilizationMode.HIGH_QUALITY -> {} // No-op. Handled by VideoCapture use case.
        StabilizationMode.AUTO -> {
            // AUTO should have been resolved to an explicit mode before reaching this point.
            // Rather than crashing the whole camera session, degrade gracefully to OFF.
            Log.w(
                TAG,
                "Unexpected stabilization mode AUTO reached createPreviewUseCase. " +
                    "Falling back to OFF."
            )
            setOpticalStabilizationModeEnabled(false)
            setPreviewStabilizationEnabled(false)
        }
    }

    setResolutionSelector(
        getResolutionSelector(cameraInfo.sensorLandscapeRatio, aspectRatio)
    )
}.build()
    .apply {
        setSurfaceProvider { surfaceRequest ->
            surfaceRequests.update { surfaceRequest }
        }
    }

@OptIn(ExperimentalCamera2Interop::class)
private fun Preview.Builder.setOpticalStabilizationModeEnabled(enabled: Boolean): Preview.Builder {
    Camera2Interop.Extender(this)
        .setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (enabled) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
        )
    return this
}

private fun getResolutionSelector(
    sensorLandscapeRatio: Float,
    aspectRatio: AspectRatio
): ResolutionSelector {
    val aspectRatioStrategy = when (aspectRatio) {
        AspectRatio.THREE_FOUR -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        AspectRatio.NINE_SIXTEEN -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else -> {
            // Choose the resolution selector strategy which maximizes FOV by being closest
            // to the sensor aspect ratio
            if (
                abs(sensorLandscapeRatio - AspectRatio.NINE_SIXTEEN.toLandscapeFloat()) <
                abs(sensorLandscapeRatio - AspectRatio.THREE_FOUR.toLandscapeFloat())
            ) {
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            } else {
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            }
        }
    }
    return ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatioStrategy).build()
}

context(CameraSessionContext)
private fun setFlashModeInternal(
    imageCapture: ImageCapture,
    flashMode: FlashMode,
    isFrontFacing: Boolean
) {
    val isScreenFlashRequired =
        isFrontFacing && (flashMode == FlashMode.ON || flashMode == FlashMode.AUTO)

    if (isScreenFlashRequired) {
        imageCapture.screenFlash = object : ImageCapture.ScreenFlash {
            override fun apply(
                expirationTimeMillis: Long,
                listener: ImageCapture.ScreenFlashListener
            ) {
                Log.d(TAG, "ImageCapture.ScreenFlash: apply")
                screenFlashEvents.trySend(
                    CameraSystem.ScreenFlashEvent(CameraSystem.ScreenFlashEvent.Type.APPLY_UI) {
                        listener.onCompleted()
                    }
                )
            }

            override fun clear() {
                Log.d(TAG, "ImageCapture.ScreenFlash: clear")
                screenFlashEvents.trySend(
                    CameraSystem.ScreenFlashEvent(CameraSystem.ScreenFlashEvent.Type.CLEAR_UI) {}
                )
            }
        }
    }

    imageCapture.flashMode = when (flashMode) {
        FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF // 2

        FlashMode.ON -> if (isScreenFlashRequired) {
            ImageCapture.FLASH_MODE_SCREEN // 3
        } else {
            ImageCapture.FLASH_MODE_ON // 1
        }

        FlashMode.AUTO -> if (isScreenFlashRequired) {
            ImageCapture.FLASH_MODE_SCREEN // 3
        } else {
            ImageCapture.FLASH_MODE_AUTO // 0
        }

        FlashMode.LOW_LIGHT_BOOST -> ImageCapture.FLASH_MODE_OFF // 2
    }
    Log.d(TAG, "Set flash mode to: ${imageCapture.flashMode}")
}

private fun getPendingRecording(
    context: Context,
    videoCaptureUseCase: VideoCapture<Recorder>,
    maxDurationMillis: Long,
    filePathGenerator: FilePathGenerator,
    captureTypeSuffix: String,
    saveLocation: SaveLocation,
    onVideoRecord: (OnVideoRecordEvent) -> Unit
): PendingRecording? {
    Log.d(TAG, "getPendingRecording")
    return when (saveLocation) {
        is SaveLocation.Explicit ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.applicationContext.contentResolver.openFileDescriptor(
                        saveLocation.locationUri,
                        "rw"
                    )?.let { pfd ->
                        videoCaptureUseCase.output.prepareRecording(
                            context,
                            FileDescriptorOutputOptions.Builder(pfd).build()
                        )
                    } ?: run {
                        onVideoRecord(
                            OnVideoRecordEvent.OnVideoRecordError(
                                FileNotFoundException(
                                    "Failed to open file descriptor " +
                                        "for URI: ${saveLocation.locationUri}"
                                )
                            )
                        )
                        null
                    }
                } catch (e: Exception) {
                    onVideoRecord(
                        OnVideoRecordEvent.OnVideoRecordError(e)
                    )
                    null
                }
            } else {
                if (saveLocation.locationUri.scheme == "file") {
                    saveLocation.locationUri.path?.let { path ->
                        val fileOutputOptions = FileOutputOptions.Builder(File(path)).build()
                        videoCaptureUseCase.output.prepareRecording(context, fileOutputOptions)
                    } ?: run {
                        onVideoRecord(
                            OnVideoRecordEvent.OnVideoRecordError(
                                RuntimeException("Uri path is null for file scheme.")
                            )
                        )
                        null
                    }
                } else {
                    onVideoRecord(
                        OnVideoRecordEvent.OnVideoRecordError(
                            RuntimeException("Uri scheme not supported.")
                        )
                    )
                    null
                }
            }

        is SaveLocation.Default -> {
            val outputFilename =
                filePathGenerator.generateVideoFilename(suffixText = captureTypeSuffix)
            val mediaUrl = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val contentResolver = context.contentResolver

            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, outputFilename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

                    // API 28 fix -- Manually set output directory and final output filename
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        val volumePath = filePathGenerator.absoluteVideoOutputPath
                        if (volumePath.isNotEmpty()) {
                            put(MediaStore.MediaColumns.DATA, "$volumePath/$outputFilename")
                            Log.d(
                                TAG,
                                "API 28- Video Fix: Setting _DATA to $volumePath/$outputFilename"
                            )
                        } else {
                            Log.d(
                                TAG,
                                "API 28- Fix: Could not determine volume path, cannot set _DATA column"
                            )
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
                        put(
                            MediaStore.Video.Media.RELATIVE_PATH,
                            filePathGenerator.relativeVideoOutputPath
                        )
                    }
                }
            val mediaStoreOutput =
                MediaStoreOutputOptions.Builder(
                    contentResolver,
                    mediaUrl
                )
                    .setDurationLimitMillis(maxDurationMillis)
                    .setContentValues(contentValues)
                    .build()
            videoCaptureUseCase.output.prepareRecording(context, mediaStoreOutput)
        }

        is SaveLocation.Cache -> {
            try {
                // 1. Get the app's cache directory
                val cacheDir = saveLocation.cacheDir?.toFile()
                    ?: context.applicationContext.cacheDir

                // 2. Create a unique temporary file for the video
                val tempFile = File.createTempFile(
                    "JCA_VID_CAPTURE_TEMP_", // Prefix
                    ".mp4", // Suffix
                    cacheDir // Directory
                )

                // 3. Build FileOutputOptions with the File object
                val fileOutputOptions = FileOutputOptions.Builder(tempFile)
                    .setDurationLimitMillis(maxDurationMillis)
                    .build()

                // 4. Prepare the recording
                videoCaptureUseCase.output.prepareRecording(context, fileOutputOptions)
            } catch (e: Exception) {
                onVideoRecord(
                    OnVideoRecordEvent.OnVideoRecordError(e)
                )
                null
            }
        }
    }
}

context(CameraSessionContext)
@OptIn(ExperimentalPersistentRecording::class)
private suspend fun startVideoRecordingInternal(
    context: Context,
    pendingRecord: PendingRecording,
    maxDurationMillis: Long,
    initialRecordingSettings: InitialRecordingSettings,
    onVideoRecord: (OnVideoRecordEvent) -> Unit
): Recording {
    // set the camerastate to starting
    currentCameraState.update { old ->
        old.copy(videoRecordingState = VideoRecordingState.Starting(initialRecordingSettings))
    }

    // ok. there is a difference between MUTING and ENABLING audio
    // audio must be enabled in order to be muted
    // if the video recording isn't started with audio enabled, you will not be able to un-mute it
    // the toggle should only affect whether or not the audio is muted.
    // the permission will determine whether or not the audio is enabled.
    val isAudioGranted = checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    pendingRecord.apply {
        if (isAudioGranted) {
            try {
                withAudioEnabled(initialMuted = !initialRecordingSettings.isAudioEnabled)
            } catch (e: SecurityException) {
                // The permission can be revoked between the check above and this call
                // (e.g. via Settings while the app is in the background). Record without audio
                // instead of crashing.
                Log.w(TAG, "RECORD_AUDIO permission lost before recording started", e)
            }
        }
    }
        .asPersistentRecording()

    val callbackExecutor: Executor =
        (
            currentCoroutineContext()[ContinuationInterceptor] as?
                CoroutineDispatcher
            )?.asExecutor() ?: ContextCompat.getMainExecutor(context)
    return pendingRecord.start(callbackExecutor) { onVideoRecordEvent ->
        Log.d(TAG, onVideoRecordEvent.toString())
        when (onVideoRecordEvent) {
            is VideoRecordEvent.Start -> {
                currentCameraState.update { old ->
                    old.copy(
                        videoRecordingState = VideoRecordingState.Active.Recording(
                            audioStreamState = onVideoRecordEvent.recordingStats.audioStats
                                .toAudioStreamState(),
                            maxDurationMillis = maxDurationMillis,
                            elapsedTimeNanos = onVideoRecordEvent.recordingStats
                                .recordedDurationNanos
                        )
                    )
                }
            }

            is VideoRecordEvent.Pause -> {
                currentCameraState.update { old ->
                    old.copy(
                        videoRecordingState = VideoRecordingState.Active.Paused(
                            audioStreamState = onVideoRecordEvent.recordingStats.audioStats
                                .toAudioStreamState(),
                            maxDurationMillis = maxDurationMillis,
                            elapsedTimeNanos = onVideoRecordEvent.recordingStats
                                .recordedDurationNanos
                        )
                    )
                }
            }

            is VideoRecordEvent.Resume -> {
                currentCameraState.update { old ->
                    old.copy(
                        videoRecordingState = VideoRecordingState.Active.Recording(
                            audioStreamState = onVideoRecordEvent.recordingStats.audioStats
                                .toAudioStreamState(),
                            maxDurationMillis = maxDurationMillis,
                            elapsedTimeNanos = onVideoRecordEvent.recordingStats
                                .recordedDurationNanos
                        )
                    )
                }
            }

            is VideoRecordEvent.Status -> {
                currentCameraState.update { old ->
                    // don't want to change state from paused to recording if status changes while paused
                    if (old.videoRecordingState is VideoRecordingState.Active.Paused) {
                        old.copy(
                            videoRecordingState = VideoRecordingState.Active.Paused(
                                audioStreamState = onVideoRecordEvent.recordingStats.audioStats
                                    .toAudioStreamState(),
                                maxDurationMillis = maxDurationMillis,
                                elapsedTimeNanos = onVideoRecordEvent.recordingStats
                                    .recordedDurationNanos
                            )
                        )
                    } else {
                        old.copy(
                            videoRecordingState = VideoRecordingState.Active.Recording(
                                audioStreamState = onVideoRecordEvent.recordingStats.audioStats
                                    .toAudioStreamState(),
                                maxDurationMillis = maxDurationMillis,
                                elapsedTimeNanos = onVideoRecordEvent.recordingStats
                                    .recordedDurationNanos
                            )
                        )
                    }
                }
            }

            is VideoRecordEvent.Finalize -> {
                when (onVideoRecordEvent.error) {
                    ERROR_NONE -> {
                        // update recording state to inactive with the final values of the recording.
                        currentCameraState.update { old ->
                            old.copy(
                                videoRecordingState = VideoRecordingState.Inactive(
                                    finalElapsedTimeNanos = onVideoRecordEvent.recordingStats
                                        .recordedDurationNanos
                                )
                            )
                        }
                        onVideoRecord(
                            OnVideoRecordEvent.OnVideoRecorded(
                                onVideoRecordEvent.outputResults.outputUri
                            )
                        )
                    }

                    ERROR_DURATION_LIMIT_REACHED -> {
                        currentCameraState.update { old ->
                            old.copy(
                                videoRecordingState = VideoRecordingState.Inactive(
                                    finalElapsedTimeNanos = maxDurationMillis.milliseconds
                                        .inWholeNanoseconds
                                )
                            )
                        }

                        onVideoRecord(
                            OnVideoRecordEvent.OnVideoRecorded(
                                onVideoRecordEvent.outputResults.outputUri
                            )
                        )
                    }

                    else -> {
                        onVideoRecord(
                            OnVideoRecordEvent.OnVideoRecordError(
                                RuntimeException(
                                    "Recording finished with error: ${onVideoRecordEvent.error}",
                                    onVideoRecordEvent.cause
                                )
                            )
                        )
                        currentCameraState.update { old ->
                            old.copy(
                                videoRecordingState = VideoRecordingState.Inactive(
                                    finalElapsedTimeNanos = onVideoRecordEvent.recordingStats
                                        .recordedDurationNanos
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

context(CameraSessionContext)
private suspend fun runVideoRecording(
    videoCapture: VideoCapture<Recorder>,
    captureTypeSuffix: String,
    context: Context,
    maxDurationMillis: Long,
    transientSettings: StateFlow<TransientSessionSettings?>,
    saveLocation: SaveLocation,
    videoControlEvents: Channel<VideoCaptureControlEvent>,
    onVideoRecord: (OnVideoRecordEvent) -> Unit,
    filePathGenerator: FilePathGenerator
) = coroutineScope {
    var currentSettings = transientSettings.filterNotNull().first()

    getPendingRecording(
        context,
        videoCapture,
        maxDurationMillis,
        filePathGenerator,
        captureTypeSuffix,
        saveLocation,
        onVideoRecord
    )?.let {
        startVideoRecordingInternal(
            context = context,
            pendingRecord = it,
            maxDurationMillis = maxDurationMillis,
            onVideoRecord = onVideoRecord,
            initialRecordingSettings = InitialRecordingSettings(
                isAudioEnabled = currentSettings.isAudioEnabled,
                lensFacing = currentSettings.primaryLensFacing,
                zoomRatios = currentSettings.zoomRatios
            )
        ).use { recording ->
            val recordingSettingsUpdater = launch {
                fun TransientSessionSettings.isFlashModeOn() = flashMode == FlashMode.ON

                transientSettings.filterNotNull()
                    .collectLatest { newTransientSettings ->
                        if (currentSettings.isAudioEnabled != newTransientSettings.isAudioEnabled) {
                            // audio mute state will be inverse of if audio is enabled.
                            recording.mute(!newTransientSettings.isAudioEnabled)
                        }
                        currentSettings = newTransientSettings
                    }
            }

            for (event in videoControlEvents) {
                when (event) {
                    is VideoCaptureControlEvent.StartRecordingEvent ->
                        Log.w(
                            TAG,
                            "A recording is already in progress, ignoring extra StartRecordingEvent"
                        )

                    VideoCaptureControlEvent.StopRecordingEvent -> {
                        recordingSettingsUpdater.cancel()
                        break
                    }

                    VideoCaptureControlEvent.PauseRecordingEvent -> recording.pause()
                    VideoCaptureControlEvent.ResumeRecordingEvent -> recording.resume()
                }
            }
        }
    }
}

context(CameraSessionContext)
internal suspend fun processVideoControlEvents(
    videoCapture: VideoCapture<Recorder>?,
    captureTypeSuffix: String
) = coroutineScope {
    for (event in videoCaptureControlEvents) {
        when (event) {
            is VideoCaptureControlEvent.StartRecordingEvent -> {
                if (videoCapture == null) {
                    // The UI asked to record while the session was bound without a VideoCapture
                    // use case (e.g. IMAGE_ONLY). Surface the error to the caller and keep the
                    // session alive instead of tearing down the whole camera.
                    Log.e(TAG, "Attempted video recording with null videoCapture")
                    event.onVideoRecord(
                        OnVideoRecordEvent.OnVideoRecordError(VideoCaptureUnavailableException())
                    )
                    continue
                }
                runVideoRecording(
                    videoCapture,
                    captureTypeSuffix,
                    context,
                    event.maxVideoDuration,
                    transientSettings,
                    event.saveLocation,
                    videoCaptureControlEvents,
                    event.onVideoRecord,
                    filePathGenerator
                )
            }

            else -> {}
        }
    }
}

/**
 * Applies a CaptureCallback to the provided image capture builder
 */
context(CameraSessionContext)
@OptIn(ExperimentalCamera2Interop::class)
private fun Preview.Builder.updateCameraStateWithCaptureResults(
    targetCameraInfo: CameraInfo,
    captureResults: MutableStateFlow<TotalCaptureResult?>? = null
): Preview.Builder {
    val isFirstFrameTimestampUpdated = atomic(false)
    val lastExposureInfoPublishNanos = atomic(0L)
    val targetCameraLogicalId = Camera2CameraInfo.from(targetCameraInfo).cameraId
    Camera2Interop.Extender(this).setSessionCaptureCallback(
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)

                captureResults?.update { result }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                    result.get(CaptureResult.CONTROL_AE_MODE) ==
                    CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY
                ) {
                    val nativeBoostState = result.get(CaptureResult.CONTROL_LOW_LIGHT_BOOST_STATE)
                    val boostStrength = when (nativeBoostState) {
                        CameraMetadata.CONTROL_LOW_LIGHT_BOOST_STATE_ACTIVE ->
                            LowLightBoostState.Active(LowLightBoostState.MAXIMUM_STRENGTH)

                        else -> LowLightBoostState.Inactive
                    }
                    currentCameraState.update { old ->
                        if (old.lowLightBoostState != boostStrength) {
                            old.copy(lowLightBoostState = boostStrength)
                        } else {
                            old
                        }
                    }
                }
                val logicalCameraId = session.device.id

                // todo(b/405987189): remove completely after buggy zoomState is fixed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    logicalCameraId == targetCameraLogicalId
                ) {
                    // update camerastate with zoom ratio
                    val newZoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                    currentCameraState.update { old ->
                        if (newZoomRatio != null &&
                            old.zoomRatios[targetCameraInfo.appLensFacing] != newZoomRatio
                        ) {
                            old.copy(
                                zoomRatios = old.zoomRatios
                                    .toMutableMap()
                                    .apply {
                                        put(targetCameraInfo.appLensFacing, newZoomRatio)
                                    }.toMap()
                            )
                        } else {
                            old
                        }
                    }
                }

                if (logicalCameraId != targetCameraLogicalId) return
                try {
                    val physicalCameraId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    } else {
                        null
                    }
                    currentCameraState.update { old ->
                        if (old.debugInfo.logicalCameraId != logicalCameraId ||
                            old.debugInfo.physicalCameraId != physicalCameraId
                        ) {
                            old.copy(
                                debugInfo = DebugInfo(logicalCameraId, physicalCameraId)
                            )
                        } else {
                            old
                        }
                    }
                    if (!isFirstFrameTimestampUpdated.value) {
                        currentCameraState.update { old ->
                            old.copy(
                                sessionFirstFrameTimestamp = SystemClock.elapsedRealtimeNanos()
                            )
                        }
                        isFirstFrameTimestampUpdated.value = true
                    }
                    // Publish stabilization state
                    publishStabilizationMode(result)
                    // Publish live ISO / shutter / focus readout for the Pro overlay
                    publishExposureInfo(result, lastExposureInfoPublishNanos)
                } catch (e: Exception) {
                    // Never let an exception escape a Camera2 callback (it would kill the
                    // camera thread), but don't hide it either.
                    Log.w(TAG, "Error while processing capture result", e)
                }
            }
        }
    )
    return this
}

/** Minimum interval between two exposure-info publications (~8 Hz) while in auto exposure. */
private const val EXPOSURE_INFO_MIN_INTERVAL_NANOS = 125_000_000L

/**
 * Publishes the live exposure readout (ISO / shutter / focus / locks) into [CameraState].
 *
 * In auto exposure the sensor values change on almost every frame, which would trigger a
 * [CameraState] emission (and UI recomposition) at the preview frame rate. Numeric changes are
 * therefore throttled to [EXPOSURE_INFO_MIN_INTERVAL_NANOS]; lock state changes are published
 * immediately because they are user-driven and must be reflected without delay.
 */
context(CameraSessionContext)
private fun publishExposureInfo(
    result: TotalCaptureResult,
    lastPublishNanos: AtomicLong
) {
    val exposureInfo = ExposureInfo(
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        isAeLocked = result.get(CaptureResult.CONTROL_AE_LOCK) ?: false,
        isAwbLocked = result.get(CaptureResult.CONTROL_AWB_LOCK) ?: false
    )
    val old = currentCameraState.value.exposureInfo
    if (old == exposureInfo) return

    val now = SystemClock.elapsedRealtimeNanos()
    val lockChanged = old.isAeLocked != exposureInfo.isAeLocked ||
        old.isAwbLocked != exposureInfo.isAwbLocked
    if (!lockChanged && now - lastPublishNanos.value < EXPOSURE_INFO_MIN_INTERVAL_NANOS) {
        return
    }
    lastPublishNanos.value = now
    currentCameraState.update { state ->
        if (state.exposureInfo != exposureInfo) {
            state.copy(exposureInfo = exposureInfo)
        } else {
            state
        }
    }
}

context(CameraSessionContext)
private fun publishStabilizationMode(result: TotalCaptureResult) {
    val nativeVideoStabilizationMode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
    val stabilizationMode = when (nativeVideoStabilizationMode) {
        CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION ->
            StabilizationMode.ON

        CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON -> StabilizationMode.HIGH_QUALITY
        else -> {
            result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)?.let {
                if (it == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    StabilizationMode.OPTICAL
                } else {
                    StabilizationMode.OFF
                }
            } ?: StabilizationMode.OFF
        }
    }

    currentCameraState.update { old ->
        if (old.stabilizationMode != stabilizationMode) {
            old.copy(stabilizationMode = stabilizationMode)
        } else {
            old
        }
    }
}

private fun AudioStats.toAudioStreamState(): AudioStreamState = when (this.audioState) {
    AudioStats.AUDIO_STATE_ACTIVE -> AudioStreamState.Active(this.audioAmplitude)
    AudioStats.AUDIO_STATE_MUTED -> AudioStreamState.Muted
    AudioStats.AUDIO_STATE_ENCODER_ERROR -> AudioStreamState.Error
    AudioStats.AUDIO_STATE_DISABLED -> AudioStreamState.Disabled
    AudioStats.AUDIO_STATE_SOURCE_SILENCED -> AudioStreamState.Silenced
    else -> AudioStreamState.Unknown
}

private fun Provider<CameraEffectProvider>.createEffect(scope: CoroutineScope): CameraEffect =
    get().create(scope)
