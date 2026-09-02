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

import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.ConcurrentCameraMode
import com.google.jetpackcamera.model.FlashMode
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.settings.model.forCurrentLens
import com.google.jetpackcamera.ui.uistate.capture.ExtensionModeUiState

/**
 * Creates an [ExtensionModeUiState] from the current settings and device constraints.
 *
 * The selector is [ExtensionModeUiState.Unavailable] when the active lens supports no CameraX
 * extension. When at least one is supported, the state is [ExtensionModeUiState.Available] and
 * `isSupported` reflects whether an extension session can currently be bound: extensions are
 * still-image pipelines (Preview + ImageCapture only), so they are disabled in video-only mode,
 * in concurrent (dual) camera mode and while Low Light Boost owns the pipeline.
 *
 * Only the modes advertised by the device are listed, in the fixed order of
 * [CameraExtensionMode.SELECTABLE_MODES], so the row is stable across lens switches.
 */
internal fun ExtensionModeUiState.Companion.from(
    cameraAppSettings: CameraAppSettings,
    systemConstraints: CameraSystemConstraints
): ExtensionModeUiState {
    val supported = systemConstraints.forCurrentLens(cameraAppSettings)
        ?.supportedExtensionModes
        .orEmpty()
    if (supported.isEmpty()) return ExtensionModeUiState.Unavailable

    val isCompatible = cameraAppSettings.captureMode != CaptureMode.VIDEO_ONLY &&
        cameraAppSettings.concurrentCameraMode == ConcurrentCameraMode.OFF &&
        cameraAppSettings.flashMode != FlashMode.LOW_LIGHT_BOOST

    return ExtensionModeUiState.Available(
        selectedMode = if (isCompatible) {
            cameraAppSettings.extensionMode
        } else {
            CameraExtensionMode.NONE
        },
        availableModes = CameraExtensionMode.SELECTABLE_MODES.filter { it in supported },
        isSupported = isCompatible
    )
}
