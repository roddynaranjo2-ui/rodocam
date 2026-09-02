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
import com.google.jetpackcamera.core.camera.VideoRecordingState
import com.google.jetpackcamera.model.ConcurrentCameraMode
import com.google.jetpackcamera.model.ExternalCaptureMode
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.settings.model.forCurrentLens
import com.google.jetpackcamera.ui.uistate.capture.ManualControlsUiState

/**
 * Creates a [ManualControlsUiState] from settings, constraints and real-time camera state.
 *
 * Returns [ManualControlsUiState.Unavailable] when:
 * - the active lens exposes no manual control at all (legacy HAL, external camera), or
 * - dual concurrent camera is active (CameraX does not route Camera2 interop to both cameras), or
 * - the app was launched by an external intent that does not expect a Pro UI.
 *
 * @param isPanelOpen Whether the user has the Pro panel expanded (tracked UI state).
 */
fun ManualControlsUiState.Companion.from(
    cameraAppSettings: CameraAppSettings,
    systemConstraints: CameraSystemConstraints,
    cameraState: CameraState,
    externalCaptureMode: ExternalCaptureMode,
    isPanelOpen: Boolean
): ManualControlsUiState {
    if (externalCaptureMode != ExternalCaptureMode.Standard) {
        return ManualControlsUiState.Unavailable
    }
    if (cameraAppSettings.concurrentCameraMode == ConcurrentCameraMode.DUAL) {
        return ManualControlsUiState.Unavailable
    }
    val capabilities = systemConstraints.forCurrentLens(cameraAppSettings)?.manualCapabilities
        ?: return ManualControlsUiState.Unavailable
    if (!capabilities.supportsAnyManualControl) {
        return ManualControlsUiState.Unavailable
    }

    return ManualControlsUiState.Available(
        isProModeEnabled = cameraAppSettings.isProModeEnabled,
        isPanelOpen = cameraAppSettings.isProModeEnabled && isPanelOpen,
        controls = capabilities.sanitize(cameraAppSettings.manualControls),
        capabilities = capabilities,
        exposureInfo = cameraState.exposureInfo,
        isRecording = cameraState.videoRecordingState !is VideoRecordingState.Inactive
    )
}
