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

import android.util.Range
import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.model.LensInfo
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.ui.uistate.capture.ZoomControlUiState

/**
 * Creates a [ZoomControlUiState] from various camera and application sources.
 *
 * This function is responsible for creating the UI state for the zoom controls (e.g., the 0.5x, 1x,
 * 2x buttons). It determines the available zoom levels based on the hardware's supported zoom
 * range for the currently active lens.
 *
 * If the camera lens does not support zooming (i.e., the zoom range is a single value), this
 * function will return [ZoomControlUiState.Disabled]. Otherwise, it calculates a list of discrete
 * zoom levels to display to the user (e.g., 0.5x, 1x, 2x, 5x) based on the supported range.
 *
 * @param animateZoomState An optional target zoom ratio for an ongoing animation. If non-null, the
 *   UI can use this to show an animation progressing towards the target.
 * @param systemConstraints The capabilities of the device's camera hardware, used to find the
 *   supported zoom range for the current lens.
 * @param cameraAppSettings The current application settings, providing the selected lens facing
 *   and default zoom ratios.
 * @param cameraState The real-time state from the camera, providing the current actual zoom ratio.
 * @return A [ZoomControlUiState] which is either:
 * - [ZoomControlUiState.Enabled] containing the calculated zoom levels, current zoom ratio, and
 *   other parameters for the UI.
 * - [ZoomControlUiState.Disabled] if the current lens does not support zooming.
 */
fun ZoomControlUiState.Companion.from(
    animateZoomState: Float?,
    systemConstraints: CameraSystemConstraints,
    cameraAppSettings: CameraAppSettings,
    cameraState: CameraState
): ZoomControlUiState {
    val lensConstraints = systemConstraints.perLensConstraints[cameraAppSettings.cameraLensFacing]
    val zoomRange = lensConstraints?.supportedZoomRange ?: Range(1f, 1f)

    if (zoomRange.upper == zoomRange.lower) {
        return ZoomControlUiState.Disabled
    }
    val zoomLevels = buildZoomLevels(zoomRange, lensConstraints?.physicalLenses.orEmpty())
    return ZoomControlUiState.Enabled(
        zoomLevels = zoomLevels,
        primaryLensFacing = cameraAppSettings.cameraLensFacing,
        initialZoomRatio = cameraAppSettings.defaultZoomRatios[cameraAppSettings.cameraLensFacing],
        primaryZoomRatio = cameraState.zoomRatios[cameraAppSettings.cameraLensFacing],
        primarySettingZoomRatio = cameraAppSettings
            .defaultZoomRatios[cameraAppSettings.cameraLensFacing],
        animatingToValue = animateZoomState
    )
}

/**
 * Computes the zoom chips to display.
 *
 * Pixel-style behaviour: one chip per *physical* lens (e.g. 0.6x ultra-wide, 1x wide, 3x tele on a
 * Galaxy S21 FE) so tapping a chip switches to the native sensor instead of digitally cropping.
 * When the device does not expose physical lenses (single-camera or legacy HAL), fall back to the
 * generic 0.5x / 1x / 2x / 5x ladder clamped to the supported range. In both cases a 2x chip is
 * offered when there is no lens between 1x and 3x, matching Pixel's "2x optical-quality" shortcut.
 */
internal fun buildZoomLevels(zoomRange: Range<Float>, physicalLenses: List<LensInfo>): List<Float> {
    val fromLenses = physicalLenses
        .map { it.zoomRatio }
        .filter { zoomRange.contains(it) }
        .distinct()
        .sorted()

    val levels = sortedSetOf<Float>()
    if (fromLenses.size >= 2) {
        levels += fromLenses
        // Ensure 1x is always present as the anchor chip.
        if (zoomRange.contains(1f)) levels += 1f
        // Add a 2x shortcut when there is a gap between 1x and the next tele lens.
        val nextTele = fromLenses.firstOrNull { it > 1.05f }
        if (zoomRange.contains(2f) && (nextTele == null || nextTele > 2.5f)) levels += 2f
    } else {
        if (zoomRange.lower < 1f) levels += zoomRange.lower
        if (zoomRange.contains(1f)) levels += 1f
        if (zoomRange.contains(2f)) levels += 2f
        if (zoomRange.contains(5f)) levels += 5f
    }
    // Keep the chip row compact (Pixel shows at most 4-5 chips).
    return levels.toList().let { all ->
        if (all.size <= MAX_ZOOM_CHIPS) all else pickEvenly(all, MAX_ZOOM_CHIPS)
    }
}

private const val MAX_ZOOM_CHIPS = 5

private fun pickEvenly(values: List<Float>, count: Int): List<Float> {
    if (values.size <= count) return values
    val picked = sortedSetOf<Float>()
    picked += values.first()
    picked += values.last()
    if (values.contains(1f)) picked += 1f
    var i = 0
    while (picked.size < count && i < values.size) {
        picked += values[(i * (values.size - 1)) / (count - 1)]
        i++
    }
    return picked.toList()
}
