/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.Camera2Config
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraXConfig
import androidx.camera.core.DynamicRange as CXDynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.takePicture
import androidx.camera.lifecycle.ExperimentalCameraProviderConfiguration
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.Recorder
import androidx.core.net.toFile
import com.google.jetpackcamera.core.camera.CameraCoreUtil.getAllCamerasPropertiesJSONArray
import com.google.jetpackcamera.core.camera.CameraCoreUtil.writeFileExternalStorage
import com.google.jetpackcamera.core.camera.effects.CameraEffectFeatureKey
import com.google.jetpackcamera.core.camera.lowlight.LowLightBoostAvailabilityChecker
import com.google.jetpackcamera.core.camera.lowlight.LowLightBoostEffectProvider
import com.google.jetpackcamera.core.camera.lowlight.LowLightBoostFeatureKey
import com.google.jetpackcamera.core.camera.postprocess.ImagePostProcessor
import com.google.jetpackcamera.core.camera.postprocess.ImagePostProcessorFeatureKey
import com.google.jetpackcamera.core.common.FilePathGenerator
import com.google.jetpackcamera.model.AspectRatio
import com.google.jetpackcamera.model.CameraEffectId
import com.google.jetpackcamera.model.CameraEffectTarget
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CameraZoomRatio
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.ConcurrentCameraMode
import com.google.jetpackcamera.model.DNG_FILE_EXTENSION
import com.google.jetpackcamera.model.DNG_MIME_TYPE
import com.google.jetpackcamera.model.DeviceRotation
import com.google.jetpackcamera.model.DynamicRange
import com.google.jetpackcamera.model.FlashMode
import com.google.jetpackcamera.model.Illuminant
import com.google.jetpackcamera.model.ImageOutputFormat
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.model.LensToZoom
import com.google.jetpackcamera.model.LowLightBoostAvailability
import com.google.jetpackcamera.model.LowLightBoostPriority
import com.google.jetpackcamera.model.LowLightBoostState
import com.google.jetpackcamera.model.ManualControls
import com.google.jetpackcamera.model.SaveLocation
import com.google.jetpackcamera.model.StabilizationMode
import com.google.jetpackcamera.model.TARGET_FPS_120
import com.google.jetpackcamera.model.TARGET_FPS_15
import com.google.jetpackcamera.model.TARGET_FPS_24
import com.google.jetpackcamera.model.TARGET_FPS_30
import com.google.jetpackcamera.model.TARGET_FPS_60
import com.google.jetpackcamera.model.TARGET_FPS_AUTO
import com.google.jetpackcamera.model.TestPattern
import com.google.jetpackcamera.model.UNLIMITED_VIDEO_DURATION
import com.google.jetpackcamera.model.VideoQuality
import com.google.jetpackcamera.model.ZoomStrategy
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraConstraints
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.settings.model.forCurrentLens
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CameraXCameraSystem"

/** Upper bound for waiting on AF/AE convergence before applying a long-press lock. */
private const val LOCK_CONVERGENCE_TIMEOUT_MILLIS = 3_000L

/**
 * CameraX based implementation for [CameraSystem]
 */
class CameraXCameraSystem(
    private val application: Application,
    private val defaultDispatcher: CoroutineDispatcher,
    private val iODispatcher: CoroutineDispatcher,
    private val filePathGenerator: FilePathGenerator,
    availabilityCheckers:
    Map<LowLightBoostFeatureKey, @JvmSuppressWildcards Provider<LowLightBoostAvailabilityChecker>>,
    effectProviders:
    Map<LowLightBoostFeatureKey, @JvmSuppressWildcards Provider<LowLightBoostEffectProvider>>,
    val imagePostProcessors:
    Map<ImagePostProcessorFeatureKey, @JvmSuppressWildcards Provider<ImagePostProcessor>>,
    private val cameraEffectProviders:
    Map<CameraEffectFeatureKey, @JvmSuppressWildcards Provider<CameraEffectProvider>>
) : CameraSystem {
    /**
     * Set once [initialize] succeeds. Kept nullable (instead of `lateinit`) so that any public
     * method invoked before/without a successful initialization fails gracefully instead of
     * throwing [UninitializedPropertyAccessException].
     */
    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    /** Vendor extensions entry point; `null` when the device exposes none. Set in [initialize]. */
    @Volatile
    private var extensionsManager: ExtensionsManager? = null

    private var imageCaptureUseCase: ImageCapture? = null

    /**
     * Constraints discovered during [initialize]. Defaults to an empty set of constraints so all
     * lookups (`perLensConstraints[...]`) safely resolve to `null` before initialization.
     */
    @Volatile
    private var systemConstraints: CameraSystemConstraints = CameraSystemConstraints()

    private val screenFlashEvents: Channel<CameraSystem.ScreenFlashEvent> =
        Channel(capacity = Channel.UNLIMITED)
    private val focusMeteringEvents =
        Channel<CameraEvent.FocusMeteringEvent>(capacity = Channel.CONFLATED)
    private val videoCaptureControlEvents = Channel<VideoCaptureControlEvent>()

    private val currentSettings = MutableStateFlow<CameraAppSettings?>(null)

    // Could be improved by setting initial value only when camera is initialized
    private var currentCameraState = MutableStateFlow(CameraState())
    override fun getCurrentCameraState(): StateFlow<CameraState> = currentCameraState.asStateFlow()

    private val _systemConstraints = MutableStateFlow<CameraSystemConstraints?>(null)
    override fun getSystemConstraints(): StateFlow<CameraSystemConstraints?> =
        _systemConstraints.asStateFlow()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)

    override fun getSurfaceRequest(): StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    private val lowLightBoostAvailabilityChecker: LowLightBoostAvailabilityChecker?
    private val lowLightBoostEffectProvider: LowLightBoostEffectProvider?

    init {
        val entry = availabilityCheckers.entries.firstOrNull()
        if (entry == null) {
            Log.d(TAG, "No LowLightBoost implementation found.")
            lowLightBoostAvailabilityChecker = null
            lowLightBoostEffectProvider = null
        } else {
            Log.d(TAG, "Using LowLightBoost implementation with key: ${entry.key}")
            lowLightBoostAvailabilityChecker = entry.value.get()
            val effectProviderForKey = requireNotNull(effectProviders[entry.key]) {
                "LowLightBoostEffectProvider missing for feature key ${entry.key}"
            }
            lowLightBoostEffectProvider = effectProviderForKey.get()
        }
    }

    override suspend fun initialize(
        cameraAppSettings: CameraAppSettings,
        cameraPropertiesJSONCallback: (result: String) -> Unit
    ) {
        val debugSettings = cameraAppSettings.debugSettings
        val cameraProvider = configureAndGetCameraProvider(
            context = application,
            singleLensMode = debugSettings.singleLensMode
        )
        this.cameraProvider = cameraProvider
        val extensionsManager = getExtensionsManagerOrNull(application, cameraProvider)
        this.extensionsManager = extensionsManager

        // updates values for available cameras
        val availableCameraLenses =
            listOf(
                LensFacing.FRONT,
                LensFacing.BACK
            ).filter {
                cameraProvider.hasCamera(it.toCameraSelector())
            }

        // verify the initial camera exists
        val settingsWithVerifiedLens =
            if (cameraAppSettings.cameraLensFacing !in availableCameraLenses &&
                availableCameraLenses.isNotEmpty()
            ) {
                cameraAppSettings.copy(cameraLensFacing = availableCameraLenses.first())
            } else {
                cameraAppSettings
            }

        // Build and update the system constraints
        systemConstraints = CameraSystemConstraints(
            availableLenses = availableCameraLenses,
            concurrentCamerasSupported = cameraProvider.availableConcurrentCameraInfos.any {
                it.map { cameraInfo -> cameraInfo.appLensFacing }
                    .toSet() == setOf(LensFacing.FRONT, LensFacing.BACK)
            },
            perLensConstraints = buildMap {
                val availableCameraInfos = cameraProvider.availableCameraInfos
                for (lensFacing in availableCameraLenses) {
                    val selector = lensFacing.toCameraSelector()
                    selector.filter(availableCameraInfos).firstOrNull()?.let { camInfo ->
                        val videoCapabilities = Recorder.getVideoCapabilities(camInfo)
                        val supportedDynamicRanges =
                            videoCapabilities.supportedDynamicRanges
                                .mapNotNull(CXDynamicRange::toSupportedAppDynamicRange)
                                .toSet()
                        val supportedVideoQualitiesMap =
                            buildMap {
                                for (dynamicRange in supportedDynamicRanges) {
                                    val supportedVideoQualities =
                                        videoCapabilities.getSupportedQualities(
                                            dynamicRange.toCXDynamicRange()
                                        ).map { it.toVideoQuality() }
                                    put(dynamicRange, supportedVideoQualities)
                                }
                            }
                        val zoomState = camInfo.zoomState.value
                        val supportedZoomRange: Range<Float>? =
                            zoomState?.let { Range(it.minZoomRatio, it.maxZoomRatio) }

                        val supportedStabilizationModes = buildSet {
                            if (camInfo.isPreviewStabilizationSupported) {
                                add(StabilizationMode.ON)
                                add(StabilizationMode.AUTO)
                            }

                            if (camInfo.isVideoStabilizationSupported) {
                                add(StabilizationMode.HIGH_QUALITY)
                            }

                            if (camInfo.isOpticalStabilizationSupported) {
                                add(StabilizationMode.OPTICAL)
                                add(StabilizationMode.AUTO)
                            }

                            add(StabilizationMode.OFF)
                        }

                        val unsupportedStabilizationFpsMap = buildMap {
                            for (stabilizationMode in supportedStabilizationModes) {
                                when (stabilizationMode) {
                                    StabilizationMode.ON -> setOf(TARGET_FPS_15, TARGET_FPS_60, TARGET_FPS_120)
                                    StabilizationMode.HIGH_QUALITY -> setOf(TARGET_FPS_60, TARGET_FPS_120)
                                    StabilizationMode.OPTICAL -> emptySet()
                                    else -> null
                                }?.let { put(stabilizationMode, it) }
                            }
                        }

                        val supportedFixedFrameRates =
                            camInfo.filterSupportedFixedFrameRates(FIXED_FRAME_RATES)
                        val supportedImageFormats = camInfo.supportedImageFormats
                        val supportedIlluminants = generateSupportedIlluminants(
                            camInfo,
                            lensFacing,
                            settingsWithVerifiedLens
                        )
                        val supportedFlashModes = generateSupportedFlashModes(supportedIlluminants)

                        val supportedTestPatterns = if (debugSettings.isDebugModeEnabled) {
                            camInfo.availableTestPatterns
                        } else {
                            setOf(TestPattern.Off)
                        }

                        val manualCapabilities = camInfo.manualCapabilities
                        val physicalLenses = camInfo.physicalLenses(application)
                        val supportedExtensionModes =
                            extensionsManager?.supportedExtensionModes(selector) ?: emptySet()
                        Log.d(
                            TAG,
                            "Lens $lensFacing: manual=$manualCapabilities lenses=" +
                                physicalLenses.map { it.zoomRatio } +
                                " extensions=$supportedExtensionModes"
                        )

                        put(
                            lensFacing,
                            CameraConstraints(
                                supportedStabilizationModes = supportedStabilizationModes,
                                supportedFixedFrameRates = supportedFixedFrameRates,
                                supportedDynamicRanges = supportedDynamicRanges,
                                supportedImageFormatsMap = mapOf(
                                    // Only JPEG is supported in single-stream mode (represented by true),
                                    // since single-stream mode uses CameraEffect, which does not support
                                    // Ultra HDR now.
                                    Pair(true, setOf(ImageOutputFormat.JPEG)),
                                    Pair(false, supportedImageFormats)
                                ),
                                supportedEffects = cameraEffectProviders.keys.map { it.id }.toSet(),
                                effectTargetsMap = cameraEffectProviders.map { (key, provider) ->
                                    key.id to provider.get().targets
                                }.toMap(),
                                supportedVideoQualitiesMap = supportedVideoQualitiesMap,
                                supportedIlluminants = supportedIlluminants,
                                supportedFlashModes = supportedFlashModes,
                                supportedZoomRange = supportedZoomRange,
                                unsupportedStabilizationFpsMap = unsupportedStabilizationFpsMap,
                                supportedTestPatterns = supportedTestPatterns,
                                manualCapabilities = manualCapabilities,
                                physicalLenses = physicalLenses,
                                supportedExtensionModes = supportedExtensionModes
                            )
                        )
                    }
                }
            }
        )

        _systemConstraints.value = systemConstraints

        currentSettings.value =
            settingsWithVerifiedLens
                .tryApplyDynamicRangeConstraints()
                .tryApplyAspectRatioForExternalCapture(settingsWithVerifiedLens.captureMode)
                .tryApplyImageFormatConstraints()
                .tryApplyFrameRateConstraints()
                .tryApplyStabilizationConstraints()
                .tryApplyConcurrentCameraModeConstraints()
                .tryApplyFlashModeConstraints()
                .tryApplyCaptureModeConstraints()
                .tryApplyVideoQualityConstraints()
                .tryApplyTestPatternConstraints()
                .tryApplyExtensionModeConstraints()
        if (debugSettings.isDebugModeEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            withContext(iODispatcher) {
                val cameraPropertiesJSON =
                    getAllCamerasPropertiesJSONArray(cameraProvider.availableCameraInfos).toString()
                val fileDir = File(application.getExternalFilesDir(null), "Debug")
                fileDir.mkdirs()
                val file = File(
                    fileDir,
                    "JCACameraProperties.json"
                )
                writeFileExternalStorage(file, cameraPropertiesJSON)
                cameraPropertiesJSONCallback.invoke(cameraPropertiesJSON)
                Log.d(
                    TAG,
                    "JCACameraProperties written to ${file.path}. \n" +
                        cameraPropertiesJSON
                )
            }
        }
    }

    private suspend fun generateSupportedIlluminants(
        camInfo: CameraInfo,
        lensFacing: LensFacing,
        cameraAppSettings: CameraAppSettings
    ): Set<Illuminant> {
        return buildSet {
            if (camInfo.hasFlashUnit()) {
                add(Illuminant.FLASH_UNIT)
            }

            if (lensFacing == LensFacing.FRONT) {
                add(Illuminant.SCREEN)
            }

            val llbAvailability =
                camInfo.getLowLightBoostAvailability(application, lowLightBoostAvailabilityChecker)
            if (llbAvailability == LowLightBoostAvailability.AE_MODE_ONLY ||
                (
                    llbAvailability ==
                        LowLightBoostAvailability.AE_MODE_AND_CAMERA_EFFECT &&
                        cameraAppSettings.lowLightBoostPriority ==
                        LowLightBoostPriority.PRIORITIZE_AE_MODE
                    )
            ) {
                add(Illuminant.LOW_LIGHT_BOOST_AE_MODE)
            }
            if (llbAvailability ==
                LowLightBoostAvailability.CAMERA_EFFECT_ONLY ||
                (
                    llbAvailability ==
                        LowLightBoostAvailability.AE_MODE_AND_CAMERA_EFFECT &&
                        cameraAppSettings.lowLightBoostPriority ==
                        LowLightBoostPriority.PRIORITIZE_GOOGLE_PLAY_SERVICES
                    )
            ) {
                add(Illuminant.LOW_LIGHT_BOOST_CAMERA_EFFECT)
            }
        }
    }

    private suspend fun generateSupportedFlashModes(
        supportedIlluminants: Set<Illuminant>
    ): Set<FlashMode> {
        return buildSet {
            add(FlashMode.OFF)
            if ((
                    setOf(
                        Illuminant.FLASH_UNIT,
                        Illuminant.SCREEN
                    ) intersect supportedIlluminants
                    ).isNotEmpty()
            ) {
                add(FlashMode.ON)
                add(FlashMode.AUTO)
            }

            if (Illuminant.LOW_LIGHT_BOOST_AE_MODE in supportedIlluminants ||
                Illuminant.LOW_LIGHT_BOOST_CAMERA_EFFECT in supportedIlluminants
            ) {
                add(FlashMode.LOW_LIGHT_BOOST)
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override suspend fun runCamera() = coroutineScope {
        Log.d(TAG, "runCamera")

        val cameraProvider = checkNotNull(cameraProvider) {
            "CameraXCameraSystem.runCamera() called before initialize() completed successfully."
        }

        launch {
            handleLowLightBoostErrors()
        }

        val transientSettings = MutableStateFlow<TransientSessionSettings?>(null)
        currentSettings
            .filterNotNull()
            .map { currentCameraSettings ->
                transientSettings.value = TransientSessionSettings(
                    isAudioEnabled = currentCameraSettings.audioEnabled,
                    deviceRotation = currentCameraSettings.deviceRotation,
                    flashMode = currentCameraSettings.flashMode,
                    primaryLensFacing = currentCameraSettings.cameraLensFacing,
                    zoomRatios = currentCameraSettings.defaultZoomRatios,
                    testPattern = currentCameraSettings.debugSettings.testPattern,
                    // Clamp to the active lens' capabilities so a value chosen on the rear lens
                    // never produces an illegal request on the front lens.
                    manualControls = systemConstraints.forCurrentLens(currentCameraSettings)
                        ?.manualCapabilities
                        ?.sanitize(currentCameraSettings.manualControls)
                        ?: ManualControls.AUTO
                )

                when (currentCameraSettings.concurrentCameraMode) {
                    ConcurrentCameraMode.OFF -> {
                        val cameraConstraints = checkNotNull(
                            systemConstraints.forCurrentLens(currentCameraSettings)
                        ) {
                            "Could not retrieve constraints for " +
                                "${currentCameraSettings.cameraLensFacing}"
                        }

                        val resolvedStabilizationMode = resolveStabilizationMode(
                            requestedStabilizationMode = currentCameraSettings.stabilizationMode,
                            targetFrameRate = currentCameraSettings.targetFrameRate,
                            cameraConstraints = cameraConstraints,
                            concurrentCameraMode = currentCameraSettings.concurrentCameraMode
                        )

                        val activeCameraEffect = cameraEffectProviders.keys.firstOrNull {
                            it.id == currentCameraSettings.selectedCameraEffect
                        }

                        PerpetualSessionSettings.SingleCamera(
                            aspectRatio = currentCameraSettings.aspectRatio,
                            captureMode = currentCameraSettings.captureMode,
                            activeCameraEffect = activeCameraEffect,
                            targetFrameRate = currentCameraSettings.targetFrameRate,
                            stabilizationMode = resolvedStabilizationMode,
                            dynamicRange = currentCameraSettings.dynamicRange,
                            videoQuality = currentCameraSettings.videoQuality,
                            imageFormat = currentCameraSettings.imageFormat,
                            lowLightBoostPriority = currentCameraSettings.lowLightBoostPriority,
                            extensionMode = currentCameraSettings.extensionMode
                        )
                    }

                    ConcurrentCameraMode.DUAL -> {
                        val primaryFacing = currentCameraSettings.cameraLensFacing
                        val secondaryFacing = primaryFacing.flip()
                        cameraProvider.availableConcurrentCameraInfos.firstNotNullOf {
                            var primaryCameraInfo: CameraInfo? = null
                            var secondaryCameraInfo: CameraInfo? = null
                            it.forEach { cameraInfo ->
                                if (cameraInfo.appLensFacing == primaryFacing) {
                                    primaryCameraInfo = cameraInfo
                                } else if (cameraInfo.appLensFacing == secondaryFacing) {
                                    secondaryCameraInfo = cameraInfo
                                }
                            }

                            primaryCameraInfo?.let { nonNullPrimary ->
                                secondaryCameraInfo?.let { nonNullSecondary ->
                                    PerpetualSessionSettings.ConcurrentCamera(
                                        primaryCameraInfo = nonNullPrimary,
                                        secondaryCameraInfo = nonNullSecondary,
                                        aspectRatio = currentCameraSettings.aspectRatio
                                    )
                                }
                            }
                        }
                    }
                }
            }.distinctUntilChanged()
            .collectLatest { sessionSettings ->
                coroutineScope {
                    // Snapshot the settings that produced this session. They are guaranteed
                    // non-null here because the upstream flow is `filterNotNull()`.
                    val sessionAppSettings = currentSettings.value
                    if (sessionAppSettings == null) {
                        Log.w(TAG, "Settings became null before session start; skipping session")
                        return@coroutineScope
                    }
                    val lensConstraints = systemConstraints.forCurrentLens(sessionAppSettings)
                    with(
                        CameraSessionContext(
                            context = application,
                            cameraProvider = cameraProvider,
                            backgroundDispatcher = defaultDispatcher,
                            screenFlashEvents = screenFlashEvents,
                            filePathGenerator = filePathGenerator,
                            focusMeteringEvents = focusMeteringEvents,
                            videoCaptureControlEvents = videoCaptureControlEvents,
                            currentCameraState = currentCameraState,
                            surfaceRequests = _surfaceRequest,
                            transientSettings = transientSettings,
                            lowLightBoostEffectProvider = lowLightBoostEffectProvider,
                            cameraEffectProviders = cameraEffectProviders,
                            extensionsManager = extensionsManager
                        )
                    ) {
                        try {
                            when (sessionSettings) {
                                is PerpetualSessionSettings.SingleCamera -> runSingleCameraSession(
                                    sessionSettings,
                                    lensConstraints,
                                    onImageCaptureCreated = { imageCapture ->
                                        imageCaptureUseCase = imageCapture
                                    }
                                )

                                is PerpetualSessionSettings.ConcurrentCamera ->
                                    runConcurrentCameraSession(
                                        sessionSettings,
                                        lensConstraints
                                    )
                            }
                        } finally {
                            // The ImageCapture instance belongs to the session that is being torn
                            // down. Clear it so takePicture() reports a recoverable error instead
                            // of capturing on an unbound use case.
                            imageCaptureUseCase = null
                            // Use cases must be unbound from the main thread, and the cleanup must
                            // run to completion even though this coroutine is being cancelled.
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                cameraProvider.unbindAll()
                            }
                        }
                    }
                }
            }
    }

    private fun resolveStabilizationMode(
        requestedStabilizationMode: StabilizationMode,
        targetFrameRate: Int,
        cameraConstraints: CameraConstraints,
        concurrentCameraMode: ConcurrentCameraMode
    ): StabilizationMode = if (concurrentCameraMode == ConcurrentCameraMode.DUAL) {
        StabilizationMode.OFF
    } else {
        with(cameraConstraints) {
            // Convert AUTO stabilization mode to the first supported stabilization mode
            val stabilizationMode = if (requestedStabilizationMode == StabilizationMode.AUTO) {
                // Choose between ON, OPTICAL, or OFF, depending on support, in that order
                sequenceOf(StabilizationMode.ON, StabilizationMode.OPTICAL, StabilizationMode.OFF)
                    .first {
                        it in supportedStabilizationModes &&
                            targetFrameRate !in it.unsupportedFpsSet
                    }
            } else {
                requestedStabilizationMode
            }

            // Check that the stabilization mode can be supported, otherwise return OFF
            if (stabilizationMode in supportedStabilizationModes &&
                targetFrameRate !in stabilizationMode.unsupportedFpsSet
            ) {
                stabilizationMode
            } else {
                StabilizationMode.OFF
            }
        }
    }

    override suspend fun takePicture(onCaptureStarted: (() -> Unit)) {
        val imageCapture = imageCaptureUseCase
            ?: throw ImageCaptureUnavailableException()
        try {
            val imageProxy = imageCapture.takePicture(onCaptureStarted)
            Log.d(TAG, "onCaptureSuccess")
            imageProxy.close()
        } catch (exception: Exception) {
            Log.d(TAG, "takePicture onError: $exception")
            throw exception
        }
    }

    // TODO(b/319733374): Return bitmap for external mediastore capture without URI
    override suspend fun takePicture(
        contentResolver: ContentResolver,
        saveLocation: SaveLocation,
        onCaptureStarted: (() -> Unit)
    ): ImageCapture.OutputFileResults {
        val imageCaptureUseCase = imageCaptureUseCase ?: throw ImageCaptureUnavailableException()
        val imageFormat = currentSettings.value?.imageFormat ?: ImageOutputFormat.JPEG
        // RAW is only produced when the bound use case was built for it *and* the destination is
        // the public MediaStore (explicit URIs / cache files have a single output stream).
        val captureRaw = imageFormat.producesRaw &&
            imageCaptureUseCase.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG &&
            saveLocation is SaveLocation.Default

        // Shared base name so a RAW+JPEG pair is stored as IMG_x.jpg + IMG_x.dng (Pixel behaviour).
        val baseName = filePathGenerator.generateImageFilename(fileExtension = null)
        val (outputFileOptions, closeable) = when (saveLocation) {
            is SaveLocation.Default -> {
                buildMediaStoreOutputOptions(
                    contentResolver = contentResolver,
                    displayName = baseName + imageFormat.fileExtension,
                    mimeType = imageFormat.mimeType
                ) to null
            }

            is SaveLocation.Explicit -> {
                try {
                    val imageCaptureUri = saveLocation.locationUri
                    val outputStream = contentResolver.openOutputStream(imageCaptureUri)
                        ?: throw RuntimeException("Provider recently crashed.")
                    val options = OutputFileOptions.Builder(outputStream).build()
                    options to outputStream
                } catch (e: FileNotFoundException) {
                    Log.d(TAG, "takePicture onError: $e")
                    throw e
                }
            }

            is SaveLocation.Cache -> {
                // 1. Get the app's cache directory
                val cacheDir = saveLocation.cacheDir?.toFile() ?: application.cacheDir

                // 2. Create a unique temporary file
                val tempFile = File.createTempFile(
                    "JCA_IMG_CAPTURE_TEMP_",
                    ".jpg", // Use .jpg to support Ultra HDR
                    cacheDir
                )
                Log.d(TAG, "cached image location: ${tempFile.absolutePath}")

                // 3. Build OutputFileOptions directly with the File object
                val options = OutputFileOptions.Builder(tempFile).build()

                // 4. Return options. CameraX manages the stream, so there is nothing to close.
                options to null
            }
        }

        val results = try {
            if (captureRaw) {
                val rawOptions = buildMediaStoreOutputOptions(
                    contentResolver = contentResolver,
                    displayName = baseName + DNG_FILE_EXTENSION,
                    mimeType = DNG_MIME_TYPE
                )
                val dual = imageCaptureUseCase.takeRawJpegPicture(
                    rawOutputFileOptions = rawOptions,
                    jpegOutputFileOptions = outputFileOptions,
                    executor = defaultDispatcher.asExecutor(),
                    onCaptureStarted = onCaptureStarted
                )
                dual.raw?.savedUri?.let { Log.d(TAG, "Saved DNG to $it") }
                dual.jpeg
            } else {
                imageCaptureUseCase.takePicture(outputFileOptions, onCaptureStarted)
            }
        } finally {
            closeable?.close()
        }

        results.savedUri?.let { savedUri ->
            // Post-processing (Ultra HDR gain map handling, HEIC transcode, ...) can be CPU heavy.
            // Run it on the background dispatcher so the CameraX capture executor is free to
            // start the next capture immediately.
            withContext(defaultDispatcher) {
                for ((key, value) in imagePostProcessors) {
                    value.get().postProcessImage(savedUri)
                    Log.d(TAG, "Post processed image with $key")
                }
            }
            Log.d(TAG, "Saved image to $savedUri")
        }
        return results
    }

    private fun buildMediaStoreOutputOptions(
        contentResolver: ContentResolver,
        displayName: String,
        mimeType: String
    ): OutputFileOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    filePathGenerator.relativeImageOutputPath
                )
            }
        }
        return OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
    }

    override suspend fun startVideoRecording(
        saveLocation: SaveLocation,
        onVideoRecord: (OnVideoRecordEvent) -> Unit
    ) {
        videoCaptureControlEvents.send(
            VideoCaptureControlEvent.StartRecordingEvent(
                saveLocation,
                currentSettings.value?.maxVideoDurationMillis
                    ?: UNLIMITED_VIDEO_DURATION,
                onVideoRecord = onVideoRecord
            )
        )
    }

    override suspend fun pauseVideoRecording() {
        videoCaptureControlEvents.send(VideoCaptureControlEvent.PauseRecordingEvent)
    }

    override suspend fun resumeVideoRecording() {
        videoCaptureControlEvents.send(VideoCaptureControlEvent.ResumeRecordingEvent)
    }

    override suspend fun stopVideoRecording() {
        videoCaptureControlEvents.send(VideoCaptureControlEvent.StopRecordingEvent)
    }

    override fun changeZoomRatio(newZoomState: CameraZoomRatio) {
        currentSettings.update { old ->
            old?.tryApplyNewZoomRatio(newZoomState) ?: old
        }
    }

    override fun setTestPattern(newTestPattern: TestPattern) {
        currentSettings.update { old ->
            old?.copy(debugSettings = old.debugSettings.copy(testPattern = newTestPattern)) ?: old
        }
    }

    override fun setManualControls(manualControls: ManualControls) {
        currentSettings.update { old ->
            // Vendor extensions own exposure/processing; manual controls are not applicable.
            if (old?.extensionMode?.isEnabled == true) {
                old
            } else {
                old?.copy(manualControls = manualControls)
            }
        }
    }

    override suspend fun setProModeEnabled(enabled: Boolean) {
        currentSettings.update { old ->
            old?.copy(
                isProModeEnabled = enabled,
                manualControls = if (enabled) old.manualControls else ManualControls.AUTO
            )
        }
    }

    // Sets the camera to the designated lensFacing direction
    override suspend fun setLensFacing(lensFacing: LensFacing) {
        // TODO: Handle lens flipping during recording when only one lens supports HDR.
        // We should define the expected behavior (e.g., disable flip button, stop recording with error,
        // or fallback to SDR mid-recording if supported by CameraX).
        currentSettings.update { old ->
            if (systemConstraints.availableLenses.contains(lensFacing)) {
                old?.copy(cameraLensFacing = lensFacing)
                    ?.tryApplyExtensionModeConstraints()
                    ?.tryApplyDynamicRangeConstraints()
                    ?.tryApplyImageFormatConstraints()
                    ?.tryApplyFlashModeConstraints()
                    ?.tryApplyCaptureModeConstraints()
                    ?.tryApplyTestPatternConstraints()
            } else {
                old
            }
        }
    }

    override suspend fun setExtensionMode(extensionMode: CameraExtensionMode) {
        currentSettings.update { old ->
            old?.copy(extensionMode = extensionMode)
                ?.tryApplyExtensionModeConstraints()
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyCaptureModeConstraints()
        }
    }

    private fun CameraAppSettings.isSingleStreamLayout(): Boolean {
        return cameraEffectProviders.keys.any { it.id == selectedCameraEffect }
    }

    /**
     * Drops the vendor extension when the active lens cannot bind it, when a concurrent (dual)
     * session is running, or when the app is in video-only mode (extensions are still-image
     * pipelines). With an extension active the vendor owns exposure and processing, so manual
     * controls are reset and the output falls back to a plain JPEG.
     */
    private fun CameraAppSettings.tryApplyExtensionModeConstraints(): CameraAppSettings {
        if (!extensionMode.isEnabled) return this
        val supported = systemConstraints.perLensConstraints[cameraLensFacing]
            ?.supportedExtensionModes ?: emptySet()
        val allowed = extensionMode in supported &&
            concurrentCameraMode == ConcurrentCameraMode.OFF &&
            captureMode != CaptureMode.VIDEO_ONLY &&
            flashMode != FlashMode.LOW_LIGHT_BOOST
        return if (allowed) {
            copy(
                manualControls = ManualControls.AUTO,
                imageFormat = ImageOutputFormat.JPEG,
                dynamicRange = DynamicRange.SDR
            )
        } else {
            Log.d(TAG, "Extension $extensionMode not applicable; falling back to NONE")
            copy(extensionMode = CameraExtensionMode.NONE)
        }
    }

    /**
     * Turns the vendor extension off when the user explicitly picks a feature that cannot run
     * inside an extension session (HDR / Ultra HDR / RAW output). The user's newest choice wins.
     */
    private fun CameraAppSettings.dropExtensionIfConflicting(): CameraAppSettings {
        if (!extensionMode.isEnabled) return this
        val conflicts = dynamicRange != DynamicRange.SDR || imageFormat != ImageOutputFormat.JPEG
        return if (conflicts) copy(extensionMode = CameraExtensionMode.NONE) else this
    }

    /**
     * Applies an appropriate Capture Mode for given settings, if necessary
     *
     * Should be applied whenever
     * [tryApplyImageFormatConstraints],
     * [tryApplyDynamicRangeConstraints],
     * or [tryApplyConcurrentCameraModeConstraints] would be called
     *
     * @param defaultCaptureMode if multiple capture modes are supported by the device, this capture
     * mode will be applied. If left null, it will not change the current capture mode.
     */
    private fun CameraAppSettings.tryApplyCaptureModeConstraints(
        defaultCaptureMode: CaptureMode? = null
    ): CameraAppSettings {
        Log.d(TAG, "applying capture mode constraints")
        return systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            val newCaptureMode =
                // concurrent currently only supports VIDEO_ONLY
                if (concurrentCameraMode == ConcurrentCameraMode.DUAL) {
                    CaptureMode.VIDEO_ONLY
                } else {
                    defaultCaptureMode ?: return this
                }

            Log.d(TAG, "new capture mode $newCaptureMode")
            this@tryApplyCaptureModeConstraints.copy(
                captureMode = newCaptureMode
            ).tryApplyAspectRatioForExternalCapture(newCaptureMode)
        } ?: this
    }

    private fun CameraAppSettings.tryApplyNewZoomRatio(
        newZoomState: CameraZoomRatio
    ): CameraAppSettings {
        val lensFacing = when (newZoomState.changeType.lensToZoom) {
            LensToZoom.PRIMARY -> cameraLensFacing

            LensToZoom.SECONDARY -> {
                cameraLensFacing.flip()
            }
        }
        // no-op if lens doesn't exist
        if (systemConstraints.perLensConstraints[lensFacing] == null) {
            return this
        }

        return systemConstraints.perLensConstraints[lensFacing]?.let { constraints ->
            val newZoomRatio = constraints.supportedZoomRange?.let { zoomRatioRange ->
                when (val change = newZoomState.changeType) {
                    is ZoomStrategy.Absolute -> change.value
                    is ZoomStrategy.Scale -> (
                        this.defaultZoomRatios
                            [lensFacing]
                            ?: 1.0f
                        ) *
                        change.value

                    is ZoomStrategy.Increment -> {
                        (this.defaultZoomRatios[lensFacing] ?: 1.0f) + change.value
                    }
                }.coerceIn(zoomRatioRange.lower, zoomRatioRange.upper)
            } ?: 1f
            this@tryApplyNewZoomRatio
                .copy(
                    defaultZoomRatios = this.defaultZoomRatios.toMutableMap().apply {
                        put(lensFacing, newZoomRatio)
                    }
                )
        } ?: this
    }

    private fun CameraAppSettings.tryApplyDynamicRangeConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedDynamicRanges) {
                val newDynamicRange = if (contains(dynamicRange) &&
                    flashMode != FlashMode.LOW_LIGHT_BOOST &&
                    captureMode != CaptureMode.STANDARD
                ) {
                    dynamicRange
                } else {
                    // TODO: Consider preserving user preference for HDR instead of permanently
                    //  resetting to SDR here when switching lenses.
                    DynamicRange.SDR
                }

                this@tryApplyDynamicRangeConstraints.copy(
                    dynamicRange = newDynamicRange
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyAspectRatioForExternalCapture(
        captureMode: CaptureMode
    ): CameraAppSettings = when (captureMode) {
        CaptureMode.STANDARD -> this
        CaptureMode.IMAGE_ONLY ->
            this.copy(aspectRatio = AspectRatio.THREE_FOUR)

        CaptureMode.VIDEO_ONLY ->
            this.copy(aspectRatio = AspectRatio.NINE_SIXTEEN)
    }

    private fun CameraAppSettings.affectsImageCapture(): Boolean {
        val activeEffectTargets = systemConstraints.perLensConstraints[cameraLensFacing]
            ?.effectTargetsMap?.get(selectedCameraEffect) ?: emptySet()
        return activeEffectTargets.contains(CameraEffectTarget.IMAGE_CAPTURE)
    }

    private fun CameraAppSettings.tryApplyImageFormatConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedImageFormatsMap[affectsImageCapture()]) {
                // Prioritize Low Light Boost over Ultra HDR to maintain consistency with
                // Video HDR / Low Light Boost conflict resolution.
                val newImageFormat = if (this != null && contains(imageFormat) &&
                    captureMode != CaptureMode.STANDARD &&
                    flashMode != FlashMode.LOW_LIGHT_BOOST
                ) {
                    imageFormat
                } else {
                    // TODO: Consider preserving user preference for HDR instead of permanently resetting to JPEG here when switching lenses.
                    ImageOutputFormat.JPEG
                }

                this@tryApplyImageFormatConstraints.copy(
                    imageFormat = newImageFormat
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyFrameRateConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedFixedFrameRates) {
                val newTargetFrameRate = if (contains(targetFrameRate)) {
                    targetFrameRate
                } else {
                    TARGET_FPS_AUTO
                }

                this@tryApplyFrameRateConstraints.copy(
                    targetFrameRate = newTargetFrameRate
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyStabilizationConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints) {
                val newStabilizationMode = if (stabilizationMode != StabilizationMode.AUTO &&
                    stabilizationMode in constraints.supportedStabilizationModes &&
                    targetFrameRate !in stabilizationMode.unsupportedFpsSet
                ) {
                    stabilizationMode
                } else {
                    StabilizationMode.AUTO
                }

                this@tryApplyStabilizationConstraints.copy(
                    stabilizationMode = newStabilizationMode
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyConcurrentCameraModeConstraints(): CameraAppSettings =
        when (concurrentCameraMode) {
            ConcurrentCameraMode.OFF -> this
            else ->
                if (systemConstraints.concurrentCamerasSupported &&
                    captureMode == CaptureMode.VIDEO_ONLY &&
                    dynamicRange == DynamicRange.SDR &&
                    !isSingleStreamLayout() &&
                    flashMode != FlashMode.LOW_LIGHT_BOOST
                ) {
                    copy(
                        targetFrameRate = TARGET_FPS_AUTO
                    )
                } else {
                    copy(concurrentCameraMode = ConcurrentCameraMode.OFF)
                }
        }

    private fun CameraAppSettings.tryApplyVideoQualityConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedVideoQualitiesMap) {
                val newVideoQuality = get(dynamicRange).let {
                    if (it == null) {
                        VideoQuality.UNSPECIFIED
                    } else if (it.contains(videoQuality)) {
                        videoQuality
                    } else {
                        VideoQuality.UNSPECIFIED
                    }
                }

                this@tryApplyVideoQualityConstraints.copy(
                    videoQuality = newVideoQuality
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyFlashModeConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            with(constraints.supportedFlashModes) {
                val newFlashMode = if (contains(flashMode)) {
                    flashMode
                } else {
                    FlashMode.OFF
                }

                this@tryApplyFlashModeConstraints.copy(
                    flashMode = newFlashMode
                )
            }
        } ?: this

    private fun CameraAppSettings.tryApplyTestPatternConstraints(): CameraAppSettings =
        systemConstraints.perLensConstraints[cameraLensFacing]?.let { constraints ->
            if (debugSettings.testPattern in constraints.supportedTestPatterns) {
                this
            } else {
                copy(debugSettings = debugSettings.copy(testPattern = TestPattern.Off))
            }
        } ?: this

    override suspend fun tapToFocus(x: Float, y: Float) {
        // A plain tap releases any previous long-press AE/AF lock, like on Pixel.
        setViewfinderLocks(locked = false)
        focusMeteringEvents.send(CameraEvent.FocusMeteringEvent(x, y))
    }

    override suspend fun lockFocusAndExposure(x: Float, y: Float) {
        focusMeteringEvents.send(CameraEvent.FocusMeteringEvent(x, y, lock = true))
        // Like Pixel, freeze AE/AWB only once metering has converged on the pressed point. If the
        // user taps elsewhere in the meantime the focus state is replaced (isLocked = false) and
        // the lock is never applied. Times out silently when no session is processing events.
        val focusStates = currentCameraState.map { it.focusState }
        val converged = withTimeoutOrNull(LOCK_CONVERGENCE_TIMEOUT_MILLIS) {
            // Phase 1: the session picked up our lock request.
            focusStates.first { it.isLockRequestAt(x, y) }
            // Phase 2: metering finished, or a newer request replaced ours.
            focusStates.first { !it.isRunningLockRequestAt(x, y) }
        }
        if (converged?.isLockRequestAt(x, y) == true) {
            setViewfinderLocks(locked = true)
        }
    }

    private fun FocusState.isLockRequestAt(x: Float, y: Float): Boolean =
        this is FocusState.Specified && isLocked && this.x == x && this.y == y

    private fun FocusState.isRunningLockRequestAt(x: Float, y: Float): Boolean =
        this is FocusState.Specified &&
            isLocked &&
            this.x == x &&
            this.y == y &&
            status == FocusState.Status.RUNNING

    /**
     * Mirrors the long-press AE/AF lock into [ManualControls.aeLock]/[ManualControls.awbLock] so
     * that exposure and white balance are frozen through the same Camera2 path the Pro panel uses
     * (and the Pro "AE" chip reflects the lock). Unsupported locks are dropped by `sanitize`.
     */
    private fun setViewfinderLocks(locked: Boolean) {
        currentSettings.update { old ->
            val controls = old?.manualControls ?: return@update old
            if (controls.aeLock == locked && controls.awbLock == locked) {
                old
            } else if (old.extensionMode.isEnabled) {
                old
            } else {
                old.copy(manualControls = controls.copy(aeLock = locked, awbLock = locked))
            }
        }
    }

    override fun getScreenFlashEvents() = screenFlashEvents
    override fun getCurrentSettings() = currentSettings.asStateFlow()

    override fun setFlashMode(flashMode: FlashMode) {
        currentSettings.update { old ->
            old?.copy(flashMode = flashMode)
                ?.tryApplyExtensionModeConstraints()
                ?.tryApplyDynamicRangeConstraints()
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
        }
    }

    override fun isScreenFlashEnabled() =
        imageCaptureUseCase?.flashMode == ImageCapture.FLASH_MODE_SCREEN &&
            imageCaptureUseCase?.screenFlash != null

    override suspend fun setAspectRatio(aspectRatio: AspectRatio) {
        currentSettings.update { old ->
            old?.copy(aspectRatio = aspectRatio)
        }
    }

    override suspend fun setVideoQuality(videoQuality: VideoQuality) {
        currentSettings.update { old ->
            old?.copy(videoQuality = videoQuality)
                ?.tryApplyVideoQualityConstraints()
        }
    }

    override suspend fun setLowLightBoostPriority(lowLightBoostPriority: LowLightBoostPriority) {
        currentSettings.update { old ->
            old?.copy(lowLightBoostPriority = lowLightBoostPriority)
        }
    }

    override suspend fun setCameraEffect(cameraEffect: CameraEffectId) {
        currentSettings.update { old ->
            old?.copy(selectedCameraEffect = cameraEffect)
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
                ?.tryApplyCaptureModeConstraints()
                ?.tryApplyVideoQualityConstraints()
        }
    }

    override suspend fun setDynamicRange(dynamicRange: DynamicRange) {
        currentSettings.update { old ->
            old?.copy(dynamicRange = dynamicRange)
                ?.dropExtensionIfConflicting()
                ?.tryApplyDynamicRangeConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
                ?.tryApplyCaptureModeConstraints()
        }
    }

    override fun setDeviceRotation(deviceRotation: DeviceRotation) {
        currentSettings.update { old ->
            old?.copy(deviceRotation = deviceRotation)
        }
    }

    override suspend fun setConcurrentCameraMode(concurrentCameraMode: ConcurrentCameraMode) {
        currentSettings.update { old ->
            old?.copy(concurrentCameraMode = concurrentCameraMode)
                ?.tryApplyExtensionModeConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
                ?.tryApplyCaptureModeConstraints()
        }
    }

    override suspend fun setImageFormat(imageFormat: ImageOutputFormat) {
        currentSettings.update { old ->
            old?.copy(imageFormat = imageFormat)
                ?.dropExtensionIfConflicting()
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyCaptureModeConstraints()
        }
    }

    override suspend fun setMaxVideoDuration(durationInMillis: Long) {
        currentSettings.update { old ->
            old?.copy(
                maxVideoDurationMillis = durationInMillis
            )
        }
    }

    override suspend fun setStabilizationMode(stabilizationMode: StabilizationMode) {
        currentSettings.update { old ->
            old?.copy(stabilizationMode = stabilizationMode)
        }
    }

    override suspend fun setTargetFrameRate(targetFrameRate: Int) {
        currentSettings.update { old ->
            old?.copy(targetFrameRate = targetFrameRate)?.tryApplyFrameRateConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
        }
    }

    override suspend fun setAudioEnabled(isAudioEnabled: Boolean) {
        currentSettings.update { old ->
            old?.copy(audioEnabled = isAudioEnabled)
        }
    }

    override suspend fun setCaptureMode(captureMode: CaptureMode) {
        currentSettings.update { old ->
            old?.copy(captureMode = captureMode)
                ?.tryApplyExtensionModeConstraints()
                ?.tryApplyDynamicRangeConstraints()
                ?.tryApplyAspectRatioForExternalCapture(captureMode)
                ?.tryApplyImageFormatConstraints()
                ?.tryApplyConcurrentCameraModeConstraints()
        }
    }

    private suspend fun handleLowLightBoostErrors() {
        currentCameraState.map { it.lowLightBoostState }.distinctUntilChanged().collect { state ->
            if (state is LowLightBoostState.Error) {
                if (currentSettings.value?.flashMode == FlashMode.LOW_LIGHT_BOOST) {
                    setFlashMode(FlashMode.OFF)
                }
            }
        }
    }

    companion object {
        @OptIn(markerClass = [ExperimentalCameraProviderConfiguration::class])
        private suspend fun configureAndGetCameraProvider(
            context: Context,
            singleLensMode: LensFacing? = null
        ): ProcessCameraProvider {
            singleLensMode?.let {
                try {
                    Log.d(TAG, "Configuring camera provider for single lens mode: $it")
                    ProcessCameraProvider.configureInstance(
                        CameraXConfig.Builder.fromConfig(
                            Camera2Config.defaultConfig()
                        ).setAvailableCamerasLimiter(it.toCameraSelector()).build()
                    )
                } catch (_: IllegalStateException) {
                    // No-op. CameraX is already configured.
                    Log.d(TAG, "CameraX camera provider already configured")
                }
            }
            return ProcessCameraProvider.awaitInstance(context)
        }

        private val FIXED_FRAME_RATES = setOf(TARGET_FPS_15, TARGET_FPS_24, TARGET_FPS_30, TARGET_FPS_60, TARGET_FPS_120)
    }
}
