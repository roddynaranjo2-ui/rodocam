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
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState

/**
 * User-facing shooting modes shown in the Pixel-style mode carousel above the shutter.
 *
 * Each mode is a *preset* over the underlying settings: [CaptureMode] (photo/video pipeline),
 * [CameraExtensionMode] (vendor Night / Portrait pipelines) and the Pro (manual controls) flag.
 * The mapping in both directions lives in [CaptureModeCarouselUiState].
 */
enum class ShootingMode {
    PHOTO,
    VIDEO,
    PORTRAIT,
    NIGHT,
    PRO;

    companion object {
        /** Display order of the carousel, left to right. */
        val ORDERED: List<ShootingMode> = listOf(VIDEO, PHOTO, PORTRAIT, NIGHT, PRO)
    }
}

/** The vendor extension a preset requires ([CameraExtensionMode.NONE] for plain modes). */
val ShootingMode.extensionMode: CameraExtensionMode
    get() = when (this) {
        ShootingMode.PORTRAIT -> CameraExtensionMode.BOKEH
        ShootingMode.NIGHT -> CameraExtensionMode.NIGHT
        ShootingMode.PHOTO, ShootingMode.VIDEO, ShootingMode.PRO -> CameraExtensionMode.NONE
    }

/**
 * A single settings mutation the carousel asks its controllers to perform. Emitted in order by
 * [CaptureModeCarouselUiState.Available.commandsFor]; the UI layer routes each one to the
 * matching controller (quick settings / manual controls).
 */
sealed interface CarouselCommand {
    data class SetCaptureMode(val captureMode: CaptureMode) : CarouselCommand
    data class SetExtensionMode(val extensionMode: CameraExtensionMode) : CarouselCommand
    data class SetProMode(val enabled: Boolean) : CarouselCommand
}

/** UI state of the mode carousel. */
sealed interface CaptureModeCarouselUiState {
    /**
     * The carousel is hidden: video is recording, or the camera was launched by an external
     * intent that fixes the capture mode (the legacy photo/video toggle is shown instead).
     */
    data object Unavailable : CaptureModeCarouselUiState

    /**
     * The carousel is shown.
     *
     * @property selectedMode The preset that matches the current settings.
     * @property modes Chips to display, in order. Disabled entries carry the rationale to show
     *   when the user taps them.
     * @property currentCaptureMode The live [CaptureMode] (needed to compute transitions).
     * @property currentExtensionMode The live vendor extension.
     * @property isProModeEnabled Whether Pro (manual) mode is currently on.
     */
    data class Available(
        val selectedMode: ShootingMode,
        val modes: List<SingleSelectableUiState<ShootingMode>>,
        val currentCaptureMode: CaptureMode = CaptureMode.STANDARD,
        val currentExtensionMode: CameraExtensionMode = CameraExtensionMode.NONE,
        val isProModeEnabled: Boolean = false
    ) : CaptureModeCarouselUiState {
        init {
            check(modes.isNotEmpty()) { "Mode carousel needs at least one mode" }
            check(
                modes.any {
                    it is SingleSelectableUiState.SelectableUi && it.value == selectedMode
                }
            ) {
                "Selected mode $selectedMode is not a selectable entry of $modes"
            }
        }

        /** Index of [selectedMode] within [modes]. */
        val selectedIndex: Int
            get() = modes.indexOfFirst { it.value == selectedMode }

        /** Whether [mode] is present and enabled. */
        fun isSelectable(mode: ShootingMode): Boolean =
            modes.any { it is SingleSelectableUiState.SelectableUi && it.value == mode }

        /** The entry for [mode], or null when the chip is not shown at all. */
        fun entryFor(mode: ShootingMode): SingleSelectableUiState<ShootingMode>? =
            modes.firstOrNull { it.value == mode }

        /**
         * Minimal, ordered list of settings changes that move the camera from the current
         * configuration to [target]. Returns an empty list when [target] is already active or
         * is not selectable.
         *
         * Rules:
         * - Pro is turned off when leaving [ShootingMode.PRO]; vendor extensions are cleared
         *   when leaving Portrait/Night.
         * - Night and Portrait are still-image pipelines: they force [CaptureMode.IMAGE_ONLY]
         *   when video-only was active.
         * - Video clears any extension and switches to [CaptureMode.VIDEO_ONLY].
         * - Photo restores [CaptureMode.IMAGE_ONLY] only when coming from video; a
         *   [CaptureMode.STANDARD] configuration is left untouched.
         */
        fun commandsFor(target: ShootingMode): List<CarouselCommand> {
            if (target == selectedMode || !isSelectable(target)) return emptyList()
            val commands = mutableListOf<CarouselCommand>()
            val wantedExtension = target.extensionMode
            if (isProModeEnabled && target != ShootingMode.PRO) {
                commands += CarouselCommand.SetProMode(false)
            }
            if (wantedExtension == CameraExtensionMode.NONE &&
                currentExtensionMode != CameraExtensionMode.NONE
            ) {
                commands += CarouselCommand.SetExtensionMode(CameraExtensionMode.NONE)
            }
            when (target) {
                ShootingMode.VIDEO -> {
                    if (currentCaptureMode != CaptureMode.VIDEO_ONLY) {
                        commands += CarouselCommand.SetCaptureMode(CaptureMode.VIDEO_ONLY)
                    }
                }

                ShootingMode.PHOTO, ShootingMode.PORTRAIT, ShootingMode.NIGHT -> {
                    if (currentCaptureMode == CaptureMode.VIDEO_ONLY) {
                        commands += CarouselCommand.SetCaptureMode(CaptureMode.IMAGE_ONLY)
                    }
                }

                ShootingMode.PRO -> Unit
            }
            if (wantedExtension != CameraExtensionMode.NONE &&
                currentExtensionMode != wantedExtension
            ) {
                commands += CarouselCommand.SetExtensionMode(wantedExtension)
            }
            if (target == ShootingMode.PRO && !isProModeEnabled) {
                commands += CarouselCommand.SetProMode(true)
            }
            return commands
        }
    }

    companion object {
        /**
         * The preset that describes a configuration. Pro wins over everything (it is an explicit
         * user toggle), then an active still-image extension, then the photo/video pipeline.
         */
        fun resolveSelectedMode(
            captureMode: CaptureMode,
            extensionMode: CameraExtensionMode,
            isProModeEnabled: Boolean
        ): ShootingMode = when {
            isProModeEnabled -> ShootingMode.PRO
            extensionMode == CameraExtensionMode.BOKEH -> ShootingMode.PORTRAIT
            extensionMode == CameraExtensionMode.NIGHT -> ShootingMode.NIGHT
            captureMode == CaptureMode.VIDEO_ONLY -> ShootingMode.VIDEO
            else -> ShootingMode.PHOTO
        }
    }
}
