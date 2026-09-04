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
package com.google.jetpackcamera.model

/**
 * Composition guides drawn over the viewfinder, mirroring the options offered by Pixel Camera
 * ("Grid type": Off / 3x3 / 4x4 / Golden ratio) plus the diagonals and centre guides that
 * Samsung Camera and Open Camera expose.
 */
enum class CompositionGrid {
    OFF,
    /** Rule of thirds: two vertical and two horizontal lines at 1/3 and 2/3. */
    THIRDS,
    /** Four-by-four grid (lines at 1/4, 1/2, 3/4). */
    FOURTHS,
    /** Golden ratio (phi) grid: lines at 1/phi^2 ~ 0.382 and 1/phi ~ 0.618. */
    GOLDEN_RATIO,
    /** Two corner-to-corner diagonals plus the rule-of-thirds lines. */
    DIAGONALS,
    /** Small centre cross-hair only. */
    CENTER;

    /**
     * Normalised positions (0..1) of the vertical/horizontal guide lines for this grid.
     * Empty for [OFF] and [CENTER] (which are drawn differently).
     */
    val lineFractions: List<Float>
        get() = when (this) {
            OFF, CENTER -> emptyList()
            THIRDS, DIAGONALS -> listOf(1f / 3f, 2f / 3f)
            FOURTHS -> listOf(0.25f, 0.5f, 0.75f)
            GOLDEN_RATIO -> listOf(GOLDEN_SECTION_SMALL, GOLDEN_SECTION_LARGE)
        }

    /** Whether the corner-to-corner diagonals are part of this grid. */
    val hasDiagonals: Boolean
        get() = this == DIAGONALS

    /** Whether a centre cross-hair is drawn. */
    val hasCenterMark: Boolean
        get() = this == CENTER

    companion object {
        /** 1 / phi^2, the lower golden section. */
        const val GOLDEN_SECTION_SMALL = 0.381966f

        /** 1 / phi, the upper golden section. */
        const val GOLDEN_SECTION_LARGE = 0.618034f
    }
}

/**
 * User-facing viewfinder assistance toggles (Pixel/Samsung "Pro assistance" and "Composition"
 * settings). All fields are persisted.
 *
 * @property grid Composition grid drawn over the preview.
 * @property isLevelEnabled Shows the horizon level indicator (line turns highlighted when the
 *   device is level, like Pixel's "Framing hints"). Requires a rotation sensor.
 * @property isHistogramEnabled Shows a live luma histogram in the corner of the viewfinder.
 * @property isZebrasEnabled Highlights clipped highlights (>= [zebraThresholdPercent] of full
 *   scale) with animated stripes.
 * @property zebraThresholdPercent Luma percentage from which pixels count as clipped (95..100).
 * @property isHapticsEnabled Haptic feedback on shutter, lens change, zoom snap and level.
 * @property isCoachEnabled Shows short contextual hints ("Camera Coach": overexposed, tilted,
 *   low light, high digital zoom...) derived from frame statistics and the level sensor.
 * @property isFocusPeakingEnabled Outlines in-focus edges on the preview with a GPU shader
 *   (Pixel/Sony style peaking). Preview only; captures are never affected.
 * @property isTopShotEnabled Tracks per-frame sharpness so the shutter can report whether the
 *   frame was sharp and suggest a better moment ("Top Shot" light).
 */
data class ViewfinderAssistSettings(
    val grid: CompositionGrid = CompositionGrid.OFF,
    val isLevelEnabled: Boolean = false,
    val isHistogramEnabled: Boolean = false,
    val isZebrasEnabled: Boolean = false,
    val zebraThresholdPercent: Int = DEFAULT_ZEBRA_THRESHOLD_PERCENT,
    val isHapticsEnabled: Boolean = true,
    val isCoachEnabled: Boolean = false,
    val isFocusPeakingEnabled: Boolean = false,
    val isTopShotEnabled: Boolean = false
) {
    /**
     * True when any feature needs per-frame image analysis (histogram, zebras, coach or
     * Top Shot sharpness tracking).
     */
    val needsFrameAnalysis: Boolean
        get() = isHistogramEnabled || isZebrasEnabled || isCoachEnabled || isTopShotEnabled

    /**
     * True when the preview needs the GPU shader effect (focus peaking edges and/or per-pixel
     * zebra stripes). The effect only targets the preview stream.
     */
    val needsShaderEffect: Boolean
        get() = isFocusPeakingEnabled || isZebrasEnabled

    /** True when any drawn overlay or hint is active (used to decide if the overlay composes). */
    val hasAnyOverlay: Boolean
        get() = grid != CompositionGrid.OFF ||
            isLevelEnabled ||
            isHistogramEnabled ||
            isZebrasEnabled ||
            isCoachEnabled ||
            isTopShotEnabled

    /** Returns a copy whose [zebraThresholdPercent] is clamped to the supported range. */
    fun sanitized(): ViewfinderAssistSettings = copy(
        zebraThresholdPercent = zebraThresholdPercent.coerceIn(
            MIN_ZEBRA_THRESHOLD_PERCENT,
            MAX_ZEBRA_THRESHOLD_PERCENT
        )
    )

    companion object {
        const val MIN_ZEBRA_THRESHOLD_PERCENT = 90
        const val MAX_ZEBRA_THRESHOLD_PERCENT = 100
        const val DEFAULT_ZEBRA_THRESHOLD_PERCENT = 95
        val DEFAULT = ViewfinderAssistSettings()
    }
}
