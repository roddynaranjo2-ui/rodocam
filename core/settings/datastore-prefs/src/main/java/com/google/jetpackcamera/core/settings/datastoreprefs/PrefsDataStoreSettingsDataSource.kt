/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.jetpackcamera.core.settings.datastoreprefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.jetpackcamera.model.AspectRatio
import com.google.jetpackcamera.model.CameraEffectId
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.CaptureTimer
import com.google.jetpackcamera.model.CompositionGrid
import com.google.jetpackcamera.model.ConcurrentCameraMode
import com.google.jetpackcamera.model.DarkMode
import com.google.jetpackcamera.model.DynamicRange
import com.google.jetpackcamera.model.FlashMode
import com.google.jetpackcamera.model.ImageOutputFormat
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.model.LowLightBoostPriority
import com.google.jetpackcamera.model.NONE_EFFECT_ID
import com.google.jetpackcamera.model.StabilizationMode
import com.google.jetpackcamera.model.TARGET_FPS_AUTO
import com.google.jetpackcamera.model.UNLIMITED_VIDEO_DURATION
import com.google.jetpackcamera.model.VideoQuality
import com.google.jetpackcamera.model.ViewfinderAssistSettings
import com.google.jetpackcamera.settings.SettingsDataSource
import com.google.jetpackcamera.settings.model.CameraAppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Implementation of [SettingsDataSource] with locally stored Preferences DataStore.
 */
class PrefsDataStoreSettingsDataSource(
    private val dataStore: DataStore<Preferences>,
    private val defaultCaptureModeOverride: CaptureMode
) : SettingsDataSource {

    override val defaultCameraAppSettings: Flow<CameraAppSettings> = dataStore.data.map { prefs ->
        CameraAppSettings(
            cameraLensFacing = prefs[PreferenceKeys.KEY_LENS_FACING]
                .toEnumOrDefault(LensFacing.BACK),
            darkMode = prefs[PreferenceKeys.KEY_DARK_MODE].toEnumOrDefault(DarkMode.DARK),
            flashMode = prefs[PreferenceKeys.KEY_FLASH_MODE].toEnumOrDefault(FlashMode.OFF),
            aspectRatio = prefs[PreferenceKeys.KEY_ASPECT_RATIO]
                .toEnumOrDefault(AspectRatio.NINE_SIXTEEN),
            stabilizationMode = prefs[PreferenceKeys.KEY_STABILIZATION_MODE]
                .toEnumOrDefault(StabilizationMode.AUTO),
            targetFrameRate = prefs[PreferenceKeys.KEY_TARGET_FRAME_RATE] ?: TARGET_FPS_AUTO,
            selectedCameraEffect = prefs[PreferenceKeys.KEY_SELECTED_CAMERA_EFFECT]?.let {
                if (it.isEmpty()) NONE_EFFECT_ID else CameraEffectId(it)
            } ?: NONE_EFFECT_ID,
            lowLightBoostPriority = prefs[PreferenceKeys.KEY_LOW_LIGHT_BOOST_PRIORITY]
                .toEnumOrDefault(LowLightBoostPriority.PRIORITIZE_AE_MODE),
            dynamicRange = prefs[PreferenceKeys.KEY_DYNAMIC_RANGE]
                .toEnumOrDefault(DynamicRange.SDR),
            imageFormat = prefs[PreferenceKeys.KEY_IMAGE_FORMAT]
                .toEnumOrDefault(ImageOutputFormat.JPEG),
            maxVideoDurationMillis = prefs[PreferenceKeys.KEY_MAX_VIDEO_DURATION]
                ?: UNLIMITED_VIDEO_DURATION,
            videoQuality = prefs[PreferenceKeys.KEY_VIDEO_QUALITY]
                .toEnumOrDefault(VideoQuality.UNSPECIFIED),
            audioEnabled = prefs[PreferenceKeys.KEY_AUDIO_ENABLED] ?: true,
            concurrentCameraMode = prefs[PreferenceKeys.KEY_CONCURRENT_CAMERA_MODE]
                .toEnumOrDefault(ConcurrentCameraMode.OFF),
            isProModeEnabled = prefs[PreferenceKeys.KEY_PRO_MODE_ENABLED] ?: false,
            extensionMode = prefs[PreferenceKeys.KEY_EXTENSION_MODE]
                .toEnumOrDefault(CameraExtensionMode.NONE),
            viewfinderAssist = ViewfinderAssistSettings(
                grid = prefs[PreferenceKeys.KEY_COMPOSITION_GRID]
                    .toEnumOrDefault(CompositionGrid.OFF),
                isLevelEnabled = prefs[PreferenceKeys.KEY_LEVEL_ENABLED] ?: false,
                isHistogramEnabled = prefs[PreferenceKeys.KEY_HISTOGRAM_ENABLED] ?: false,
                isZebrasEnabled = prefs[PreferenceKeys.KEY_ZEBRAS_ENABLED] ?: false,
                zebraThresholdPercent = prefs[PreferenceKeys.KEY_ZEBRA_THRESHOLD]
                    ?: ViewfinderAssistSettings.DEFAULT_ZEBRA_THRESHOLD_PERCENT,
                isHapticsEnabled = prefs[PreferenceKeys.KEY_HAPTICS_ENABLED] ?: true,
                isCoachEnabled = prefs[PreferenceKeys.KEY_COACH_ENABLED] ?: false,
                isFocusPeakingEnabled =
                prefs[PreferenceKeys.KEY_FOCUS_PEAKING_ENABLED] ?: false,
                isTopShotEnabled = prefs[PreferenceKeys.KEY_TOP_SHOT_ENABLED] ?: false
            ).sanitized(),
            captureTimer = prefs[PreferenceKeys.KEY_CAPTURE_TIMER]
                .toEnumOrDefault(CaptureTimer.OFF),
            captureMode = defaultCaptureModeOverride
        )
    }

    override suspend fun getCurrentDefaultCameraAppSettings(): CameraAppSettings =
        defaultCameraAppSettings.first()

    override suspend fun updateDefaultLensFacing(lensFacing: LensFacing) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_LENS_FACING] = lensFacing.name
        }
    }

    override suspend fun updateDarkModeStatus(darkMode: DarkMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_DARK_MODE] = darkMode.name
        }
    }

    override suspend fun updateFlashModeStatus(flashMode: FlashMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_FLASH_MODE] = flashMode.name
        }
    }

    override suspend fun updateTargetFrameRate(targetFrameRate: Int) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_TARGET_FRAME_RATE] = targetFrameRate
        }
    }

    override suspend fun updateAspectRatio(aspectRatio: AspectRatio) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_ASPECT_RATIO] = aspectRatio.name
        }
    }

    override suspend fun updateSelectedCameraEffect(selectedCameraEffect: CameraEffectId) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_SELECTED_CAMERA_EFFECT] = selectedCameraEffect.value
        }
    }

    override suspend fun updateStabilizationMode(stabilizationMode: StabilizationMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_STABILIZATION_MODE] = stabilizationMode.name
        }
    }

    override suspend fun updateDynamicRange(dynamicRange: DynamicRange) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_DYNAMIC_RANGE] = dynamicRange.name
        }
    }

    override suspend fun updateImageFormat(imageFormat: ImageOutputFormat) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_IMAGE_FORMAT] = imageFormat.name
        }
    }

    override suspend fun updateMaxVideoDuration(durationMillis: Long) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_MAX_VIDEO_DURATION] = durationMillis
        }
    }

    override suspend fun updateVideoQuality(videoQuality: VideoQuality) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_VIDEO_QUALITY] = videoQuality.name
        }
    }

    override suspend fun updateLowLightBoostPriority(lowLightBoostPriority: LowLightBoostPriority) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_LOW_LIGHT_BOOST_PRIORITY] = lowLightBoostPriority.name
        }
    }

    override suspend fun updateAudioEnabled(isAudioEnabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_AUDIO_ENABLED] = isAudioEnabled
        }
    }

    override suspend fun updateConcurrentCameraMode(concurrentCameraMode: ConcurrentCameraMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_CONCURRENT_CAMERA_MODE] = concurrentCameraMode.name
        }
    }

    override suspend fun updateProModeEnabled(isProModeEnabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_PRO_MODE_ENABLED] = isProModeEnabled
        }
    }

    override suspend fun updateExtensionMode(extensionMode: CameraExtensionMode) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_EXTENSION_MODE] = extensionMode.name
        }
    }

    override suspend fun updateViewfinderAssist(viewfinderAssist: ViewfinderAssistSettings) {
        val sanitized = viewfinderAssist.sanitized()
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_COMPOSITION_GRID] = sanitized.grid.name
            prefs[PreferenceKeys.KEY_LEVEL_ENABLED] = sanitized.isLevelEnabled
            prefs[PreferenceKeys.KEY_HISTOGRAM_ENABLED] = sanitized.isHistogramEnabled
            prefs[PreferenceKeys.KEY_ZEBRAS_ENABLED] = sanitized.isZebrasEnabled
            prefs[PreferenceKeys.KEY_ZEBRA_THRESHOLD] = sanitized.zebraThresholdPercent
            prefs[PreferenceKeys.KEY_HAPTICS_ENABLED] = sanitized.isHapticsEnabled
            prefs[PreferenceKeys.KEY_COACH_ENABLED] = sanitized.isCoachEnabled
            prefs[PreferenceKeys.KEY_FOCUS_PEAKING_ENABLED] = sanitized.isFocusPeakingEnabled
            prefs[PreferenceKeys.KEY_TOP_SHOT_ENABLED] = sanitized.isTopShotEnabled
        }
    }

    override suspend fun updateCaptureTimer(captureTimer: CaptureTimer) {
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.KEY_CAPTURE_TIMER] = captureTimer.name
        }
    }

    companion object {
        private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
            if (this == null) return default
            return try {
                enumValueOf<T>(this)
            } catch (e: IllegalArgumentException) {
                default
            }
        }
    }
}
