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
package com.google.jetpackcamera.settings.model

import com.google.jetpackcamera.model.AspectRatio
import com.google.jetpackcamera.model.CameraEffectId
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.CaptureTimer
import com.google.jetpackcamera.model.ConcurrentCameraMode
import com.google.jetpackcamera.model.DarkMode
import com.google.jetpackcamera.model.DebugSettings
import com.google.jetpackcamera.model.DeviceRotation
import com.google.jetpackcamera.model.DynamicRange
import com.google.jetpackcamera.model.ExternalCaptureMode
import com.google.jetpackcamera.model.ExternalCaptureMode.Companion.toCaptureMode
import com.google.jetpackcamera.model.FlashMode
import com.google.jetpackcamera.model.ImageOutputFormat
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.model.LowLightBoostPriority
import com.google.jetpackcamera.model.ManualControls
import com.google.jetpackcamera.model.NONE_EFFECT_ID
import com.google.jetpackcamera.model.StabilizationMode
import com.google.jetpackcamera.model.TARGET_FPS_AUTO
import com.google.jetpackcamera.model.UNLIMITED_VIDEO_DURATION
import com.google.jetpackcamera.model.VideoQuality
import com.google.jetpackcamera.model.ViewfinderAssistSettings

/**
 * Data layer representation for settings.
 */
data class CameraAppSettings(
    val captureMode: CaptureMode = CaptureMode.STANDARD,
    val cameraLensFacing: LensFacing = LensFacing.BACK,
    val darkMode: DarkMode = DarkMode.DARK,
    val flashMode: FlashMode = FlashMode.OFF,
    val selectedCameraEffect: CameraEffectId = NONE_EFFECT_ID,
    val aspectRatio: AspectRatio = AspectRatio.NINE_SIXTEEN,
    val stabilizationMode: StabilizationMode = StabilizationMode.AUTO,
    val dynamicRange: DynamicRange = DynamicRange.SDR,
    val videoQuality: VideoQuality = VideoQuality.UNSPECIFIED,
    val defaultZoomRatios: Map<LensFacing, Float> = mapOf(),
    val targetFrameRate: Int = TARGET_FPS_AUTO,
    val imageFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val audioEnabled: Boolean = true,
    val deviceRotation: DeviceRotation = DeviceRotation.Natural,
    val concurrentCameraMode: ConcurrentCameraMode = ConcurrentCameraMode.OFF,
    val maxVideoDurationMillis: Long = UNLIMITED_VIDEO_DURATION,
    val lowLightBoostPriority: LowLightBoostPriority = LowLightBoostPriority.PRIORITIZE_AE_MODE,
    val debugSettings: DebugSettings = DebugSettings(),
    /**
     * Pro/manual controls (ISO, shutter, EV, WB, focus, AE/AWB lock). Session-scoped: not
     * persisted across app launches, mirroring Pixel Camera which resets Pro controls on relaunch.
     */
    val manualControls: ManualControls = ManualControls.AUTO,
    /** Whether the Pro controls panel is enabled in the viewfinder (persisted user preference). */
    val isProModeEnabled: Boolean = false,
    /**
     * Vendor extension (Night, Portrait/Bokeh, HDR, Face retouch) bound to the session. Persisted
     * so the user returns to the mode they last used, like Pixel Camera remembers Night Sight.
     */
    val extensionMode: CameraExtensionMode = CameraExtensionMode.NONE,
    /**
     * Viewfinder assistance overlays (composition grid, horizon level, histogram, zebras) and
     * haptic feedback. Persisted.
     */
    val viewfinderAssist: ViewfinderAssistSettings = ViewfinderAssistSettings.DEFAULT,
    /** Self-timer (Off / 3 s / 10 s) applied before photos and video recordings. Persisted. */
    val captureTimer: CaptureTimer = CaptureTimer.OFF
)

fun CameraSystemConstraints.forCurrentLens(
    cameraAppSettings: CameraAppSettings
): CameraConstraints? = perLensConstraints[cameraAppSettings.cameraLensFacing]

/**
 * updates the capture mode based on the preview mode
 */
fun CameraAppSettings.applyExternalCaptureMode(
    externalCaptureMode: ExternalCaptureMode
): CameraAppSettings {
    val requiredCaptureModeOverride = externalCaptureMode.toCaptureMode()
    return if (requiredCaptureModeOverride == null ||
        requiredCaptureModeOverride == this.captureMode
    ) {
        this
    } else {
        this.copy(captureMode = requiredCaptureModeOverride)
    }
}

val DEFAULT_CAMERA_APP_SETTINGS = CameraAppSettings()
