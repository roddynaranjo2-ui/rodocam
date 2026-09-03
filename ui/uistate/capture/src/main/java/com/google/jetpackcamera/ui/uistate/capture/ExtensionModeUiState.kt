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

import com.google.jetpackcamera.model.CameraExtensionMode

/**
 * Defines the UI state for the CameraX Extensions selector (Night, Portrait, HDR, Face Retouch).
 *
 * Extensions are vendor-implemented still-capture pipelines. They are only offered when the
 * device advertises support for at least one of them on the active lens and the current
 * configuration (photo capture, single camera) is compatible with them.
 */
sealed interface ExtensionModeUiState {
    /**
     * No extension can be selected right now.
     * Either the active lens supports none of them, or the current capture mode/configuration
     * (video only, dual camera, low light boost) is incompatible with extension sessions.
     */
    data object Unavailable : ExtensionModeUiState

    /**
     * The extension selector is available.
     *
     * @param selectedMode The currently active extension mode ([CameraExtensionMode.NONE] = off).
     * @param availableModes Extension modes supported by the active lens, in display order.
     * @param isSupported Whether the selector is interactive in the current configuration.
     */
    data class Available(
        val selectedMode: CameraExtensionMode,
        val availableModes: List<CameraExtensionMode>,
        val isSupported: Boolean = true
    ) : ExtensionModeUiState

    companion object
}
