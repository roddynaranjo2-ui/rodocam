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

import com.google.jetpackcamera.model.CoachInputs
import com.google.jetpackcamera.model.CompositionGrid
import com.google.jetpackcamera.model.FrameStats
import com.google.jetpackcamera.model.HorizonLevel
import com.google.jetpackcamera.model.ThermalStatus

/**
 * UI state for the viewfinder assists drawn over the camera preview: composition grid,
 * horizon level, luma histogram, zebra (highlight clipping) warning, haptic feedback, Camera
 * Coach hints, focus peaking and Top Shot sharpness badge.
 */
sealed interface ViewfinderAssistUiState {
    /** No assist is enabled; the viewfinder should not draw any overlay. */
    data object Disabled : ViewfinderAssistUiState

    /**
     * At least one assist is enabled.
     *
     * @property grid Composition grid to draw ([CompositionGrid.OFF] draws nothing).
     * @property isLevelEnabled Whether the horizon level indicator is shown (reads the
     *   gravity sensor on the UI side).
     * @property isHistogramEnabled Whether the luma histogram overlay is shown.
     * @property isZebrasEnabled Whether clipped highlights should be flagged.
     * @property zebraThresholdPercent Luma percentage from which pixels count as clipped.
     * @property frameStats Latest frame statistics from the analysis stream, or
     *   [FrameStats.UNKNOWN] when the analysis stream is not bound (e.g. an extension is active
     *   or the device cannot run the extra stream).
     * @property isHapticsEnabled Whether UI actions (shutter, lens flip, zoom snap, level) should
     *   emit haptic feedback.
     * @property isLowLightScene Whether AE reports a dark scene (drives the Night hint).
     * @property isCoachEnabled Whether Camera Coach hints are shown.
     * @property isFocusPeakingEnabled Whether the GPU focus peaking effect is requested (the
     *   effect itself runs in the camera pipeline; the UI only shows a small badge).
     * @property isTopShotEnabled Whether the live sharpness badge is shown.
     * @property zoomRatio Current zoom ratio of the primary lens (for the HIGH_ZOOM coach rule).
     * @property maxOpticalZoom Largest optical (physical lens) zoom ratio available, `1f` when
     *   the device exposes a single lens.
     * @property thermalStatus Device thermal status; from [ThermalStatus.MODERATE] the camera
     *   pipeline is running with the thermal load-shedding policy and the viewfinder shows a
     *   short warning.
     */
    data class Enabled(
        val grid: CompositionGrid = CompositionGrid.OFF,
        val isLevelEnabled: Boolean = false,
        val isHistogramEnabled: Boolean = false,
        val isZebrasEnabled: Boolean = false,
        val zebraThresholdPercent: Int = 95,
        val frameStats: FrameStats = FrameStats.UNKNOWN,
        val isHapticsEnabled: Boolean = true,
        val isLowLightScene: Boolean = false,
        val isCoachEnabled: Boolean = false,
        val isFocusPeakingEnabled: Boolean = false,
        val isTopShotEnabled: Boolean = false,
        val zoomRatio: Float = 1f,
        val maxOpticalZoom: Float = 1f,
        val thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN
    ) : ViewfinderAssistUiState {
        /** Frame statistics are only meaningful when the analysis stream has delivered data. */
        val hasFrameStats: Boolean
            get() = frameStats != FrameStats.UNKNOWN && !frameStats.histogram.isEmpty

        /** Whether the histogram overlay should currently be drawn. */
        val showHistogram: Boolean
            get() = isHistogramEnabled && hasFrameStats

        /** Whether the zebra warning should currently be drawn. */
        val showZebras: Boolean
            get() = isZebrasEnabled && hasFrameStats && frameStats.clippedHighlightsFraction > 0f

        /** Whether the thermal throttling warning should currently be drawn. */
        val showThermalWarning: Boolean
            get() = thermalStatus.isThrottling

        /** Whether the Top Shot sharpness badge should currently be drawn. */
        val showTopShot: Boolean
            get() = isTopShotEnabled && hasFrameStats && frameStats.timestampNanos != 0L

        /**
         * Inputs for the Camera Coach rule engine. [horizonLevel] is supplied by the UI (sensor
         * reading) because it is not part of the camera state.
         */
        fun coachInputs(horizonLevel: HorizonLevel?): CoachInputs = CoachInputs(
            frameStats = if (hasFrameStats) frameStats else FrameStats.UNKNOWN,
            horizonLevel = if (isLevelEnabled) horizonLevel else null,
            isLowLightScene = isLowLightScene,
            zoomRatio = zoomRatio,
            maxOpticalZoom = maxOpticalZoom
        )
    }

    companion object
}

/** Convenience accessor: whether haptics are enabled regardless of the concrete state. */
val ViewfinderAssistUiState.isHapticsEnabled: Boolean
    get() = when (this) {
        is ViewfinderAssistUiState.Enabled -> isHapticsEnabled
        ViewfinderAssistUiState.Disabled -> true
    }
