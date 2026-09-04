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
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.ExternalCaptureMode
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeCarouselUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeUiState
import com.google.jetpackcamera.ui.uistate.capture.ExtensionModeUiState
import com.google.jetpackcamera.ui.uistate.capture.ManualControlsUiState
import com.google.jetpackcamera.ui.uistate.capture.ShootingMode
import com.google.jetpackcamera.ui.uistate.capture.extensionMode

/**
 * Builds the Pixel-style mode carousel state from the already-computed sub states.
 *
 * - Hidden ([CaptureModeCarouselUiState.Unavailable]) while recording or when launched by an
 *   external image/video intent (the capture mode is fixed by the caller).
 * - Photo / Video chips follow the photo-vs-video availability of [CaptureModeUiState] (HDR,
 *   concurrent camera and Ultra HDR rules), including the disabled rationale.
 * - Portrait / Night chips are shown only when the active lens advertises the matching CameraX
 *   extension ([ExtensionModeUiState.Available.availableModes]); they are disabled with a
 *   rationale when extensions cannot currently be bound (dual camera, Low Light Boost).
 * - Pro is shown only when the lens exposes manual controls ([ManualControlsUiState.Available]).
 *
 * The selected chip is resolved from the live settings via
 * [CaptureModeCarouselUiState.resolveSelectedMode]. If that preset is not selectable in the
 * current configuration (e.g. Night persisted but lens switched), the carousel falls back to
 * Photo/Video so the state invariant holds.
 */
fun CaptureModeCarouselUiState.Companion.from(
    cameraAppSettings: CameraAppSettings,
    cameraState: CameraState,
    externalCaptureMode: ExternalCaptureMode,
    captureModeUiState: CaptureModeUiState,
    extensionModeUiState: ExtensionModeUiState,
    manualControlsUiState: ManualControlsUiState
): CaptureModeCarouselUiState {
    if (cameraState.videoRecordingState !is VideoRecordingState.Inactive) {
        return CaptureModeCarouselUiState.Unavailable
    }
    if (externalCaptureMode != ExternalCaptureMode.Standard) {
        return CaptureModeCarouselUiState.Unavailable
    }
    val captureModes = (captureModeUiState as? CaptureModeUiState.Available)
        ?.availableCaptureModes
        ?: return CaptureModeCarouselUiState.Unavailable

    fun captureModeEntry(
        shootingMode: ShootingMode,
        captureMode: CaptureMode
    ): SingleSelectableUiState<ShootingMode> {
        val entry = captureModes.firstOrNull { it.value == captureMode }
        return when (entry) {
            is SingleSelectableUiState.SelectableUi -> SingleSelectableUiState.SelectableUi(
                shootingMode
            )

            is SingleSelectableUiState.Disabled -> SingleSelectableUiState.Disabled(
                shootingMode,
                entry.disabledReason
            )

            null -> SingleSelectableUiState.Disabled(
                shootingMode,
                DisabledReason.HDR_SIMULTANEOUS_IMAGE_VIDEO_UNSUPPORTED
            )
        }
    }

    val extension = extensionModeUiState as? ExtensionModeUiState.Available
    val modes = buildList {
        for (mode in ShootingMode.ORDERED) {
            when (mode) {
                ShootingMode.VIDEO -> add(captureModeEntry(mode, CaptureMode.VIDEO_ONLY))
                ShootingMode.PHOTO -> add(captureModeEntry(mode, CaptureMode.IMAGE_ONLY))
                ShootingMode.PORTRAIT, ShootingMode.NIGHT -> {
                    if (extension != null && mode.extensionMode in extension.availableModes) {
                        val imageAvailable = captureModeEntry(mode, CaptureMode.IMAGE_ONLY)
                        add(
                            when {
                                imageAvailable is SingleSelectableUiState.Disabled ->
                                    imageAvailable

                                extension.isSupported -> SingleSelectableUiState.SelectableUi(
                                    mode
                                )

                                else -> SingleSelectableUiState.Disabled(
                                    mode,
                                    DisabledReason.EXTENSION_UNSUPPORTED_IN_CONFIGURATION
                                )
                            }
                        )
                    }
                }

                ShootingMode.PRO -> {
                    if (manualControlsUiState is ManualControlsUiState.Available) {
                        add(SingleSelectableUiState.SelectableUi(mode))
                    }
                }
            }
        }
    }

    val resolved = CaptureModeCarouselUiState.resolveSelectedMode(
        captureMode = cameraAppSettings.captureMode,
        extensionMode = if (extension?.isSupported == true) {
            cameraAppSettings.extensionMode
        } else {
            CameraExtensionMode.NONE
        },
        isProModeEnabled = manualControlsUiState is ManualControlsUiState.Available &&
            manualControlsUiState.isProModeEnabled
    )
    val isSelectable = { m: ShootingMode ->
        modes.any { it is SingleSelectableUiState.SelectableUi && it.value == m }
    }
    val selected = when {
        isSelectable(resolved) -> resolved
        cameraAppSettings.captureMode == CaptureMode.VIDEO_ONLY &&
            isSelectable(ShootingMode.VIDEO) -> ShootingMode.VIDEO

        isSelectable(ShootingMode.PHOTO) -> ShootingMode.PHOTO
        isSelectable(ShootingMode.VIDEO) -> ShootingMode.VIDEO
        else -> return CaptureModeCarouselUiState.Unavailable
    }

    return CaptureModeCarouselUiState.Available(
        selectedMode = selected,
        modes = modes,
        currentCaptureMode = cameraAppSettings.captureMode,
        currentExtensionMode = cameraAppSettings.extensionMode,
        isProModeEnabled = manualControlsUiState is ManualControlsUiState.Available &&
            manualControlsUiState.isProModeEnabled
    )
}
