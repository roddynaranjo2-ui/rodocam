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
package com.google.jetpackcamera.ui.uistateadapter.capture

import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.model.FrameStats
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.ui.uistate.capture.ViewfinderAssistUiState

/**
 * Creates a [ViewfinderAssistUiState] from the persisted assist settings and the real-time
 * frame statistics published by the camera session.
 *
 * Returns [ViewfinderAssistUiState.Disabled] when no overlay is enabled and haptics are at
 * their default, so the viewfinder skips the overlay layer entirely.
 */
fun ViewfinderAssistUiState.Companion.from(
    cameraAppSettings: CameraAppSettings,
    cameraState: CameraState,
    systemConstraints: CameraSystemConstraints? = null
): ViewfinderAssistUiState {
    val assist = cameraAppSettings.viewfinderAssist.sanitized()
    // The thermal warning is shown even when no assist is enabled, so throttling never goes
    // unnoticed.
    val isThrottling = cameraState.thermalStatus.isThrottling
    if (!assist.hasAnyOverlay &&
        !assist.isFocusPeakingEnabled &&
        assist.isHapticsEnabled &&
        !isThrottling
    ) {
        return ViewfinderAssistUiState.Disabled
    }
    val lensFacing = cameraAppSettings.cameraLensFacing
    val maxOpticalZoom = systemConstraints?.perLensConstraints?.get(lensFacing)
        ?.physicalLenses
        ?.maxOfOrNull { it.zoomRatio }
        ?.coerceAtLeast(1f)
        ?: 1f
    // Only surface frame stats when an overlay actually consumes them, so the UI state does not
    // recompose ~15x/s for nothing.
    val frameStats = if (assist.needsFrameAnalysis) cameraState.frameStats else FrameStats.UNKNOWN
    return ViewfinderAssistUiState.Enabled(
        grid = assist.grid,
        isLevelEnabled = assist.isLevelEnabled,
        isHistogramEnabled = assist.isHistogramEnabled,
        isZebrasEnabled = assist.isZebrasEnabled,
        zebraThresholdPercent = assist.zebraThresholdPercent,
        frameStats = frameStats,
        isHapticsEnabled = assist.isHapticsEnabled,
        isLowLightScene = cameraState.isLowLightScene,
        isCoachEnabled = assist.isCoachEnabled,
        isFocusPeakingEnabled = assist.isFocusPeakingEnabled,
        isTopShotEnabled = assist.isTopShotEnabled,
        zoomRatio = cameraState.zoomRatios[lensFacing] ?: 1f,
        maxOpticalZoom = maxOpticalZoom,
        thermalStatus = cameraState.thermalStatus
    )
}
