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
package com.google.jetpackcamera.ui.uistate.capture

import com.google.jetpackcamera.model.ExposureInfo
import com.google.jetpackcamera.model.ManualCapabilities
import com.google.jetpackcamera.model.ManualControls

/**
 * UI state for the Pro (manual) controls panel: ISO, shutter, EV, white balance, focus, locks.
 */
sealed interface ManualControlsUiState {
    /** The current lens exposes no manual control, or Pro mode is disabled by an external intent. */
    data object Unavailable : ManualControlsUiState

    /**
     * Pro controls are available.
     *
     * @property isProModeEnabled Whether the Pro panel is toggled on by the user.
     * @property isPanelOpen Whether the panel is currently expanded over the viewfinder.
     * @property controls The user's current manual overrides (already clamped to [capabilities]).
     * @property capabilities Ranges/support flags of the active lens.
     * @property exposureInfo Live ISO/shutter/focus readout for the overlay.
     * @property isRecording Pro controls are read-only while recording video (Camera2 does not
     *   allow switching AE mode mid-stream on many HALs without glitches).
     */
    data class Available(
        val isProModeEnabled: Boolean,
        val isPanelOpen: Boolean,
        val controls: ManualControls,
        val capabilities: ManualCapabilities,
        val exposureInfo: ExposureInfo,
        val isRecording: Boolean = false
    ) : ManualControlsUiState {
        /** The ISO to show: manual value if pinned, else the live AE readout. */
        val displayIso: Int?
            get() = controls.iso ?: exposureInfo.iso

        /** The shutter speed to show: manual value if pinned, else the live AE readout. */
        val displayExposureTimeNanos: Long?
            get() = controls.exposureTimeNanos ?: exposureInfo.exposureTimeNanos

        /** Exposure compensation in EV, derived from the index and the device step. */
        val displayExposureCompensationEv: Float
            get() = (controls.exposureCompensationIndex ?: 0) * capabilities.exposureCompensationStep
    }

    companion object
}
