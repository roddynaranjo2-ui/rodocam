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
package com.google.jetpackcamera.feature.preview

import kotlin.math.abs

/** Zoom ratio toggled by a double tap on the viewfinder (Pixel behaviour). */
internal const val DOUBLE_TAP_ZOOM_RATIO = 2f

/** Duration of the animated zoom triggered by a double tap. */
internal const val DOUBLE_TAP_ZOOM_ANIMATION_MILLIS = 300

/** Zoom ratios within ±5 % of 1x count as "1x" for the double-tap toggle. */
internal const val ONE_X_TOLERANCE = 0.05f

internal fun isApproximatelyOneX(zoomRatio: Float): Boolean = abs(zoomRatio - 1f) <= ONE_X_TOLERANCE

/**
 * Resolves the zoom ratio a double tap should animate to.
 *
 * Mirrors the Pixel camera: from (approximately) 1x jump to [DOUBLE_TAP_ZOOM_RATIO]; from any other
 * ratio return to 1x. The gesture is disabled (returns `null`) when the lens cannot reach
 * [DOUBLE_TAP_ZOOM_RATIO], so a double tap never does anything surprising on narrow-range lenses.
 *
 * @param currentZoomRatio the current primary-lens zoom ratio, `null` when unknown (treated as 1x).
 * @param minZoomRatio lower bound of the supported zoom range.
 * @param maxZoomRatio upper bound of the supported zoom range.
 * @return the target zoom ratio clamped to the supported range, or `null` when disabled.
 */
internal fun doubleTapZoomTarget(
    currentZoomRatio: Float?,
    minZoomRatio: Float,
    maxZoomRatio: Float
): Float? {
    if (maxZoomRatio < DOUBLE_TAP_ZOOM_RATIO) return null
    val current = currentZoomRatio ?: 1f
    val target = if (isApproximatelyOneX(current)) DOUBLE_TAP_ZOOM_RATIO else 1f
    return target.coerceIn(minZoomRatio, maxZoomRatio)
}
