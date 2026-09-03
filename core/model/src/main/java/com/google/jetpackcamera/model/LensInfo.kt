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

import kotlin.math.round
import kotlin.math.sqrt

/**
 * Describes one *physical* lens behind a logical camera (e.g. ultra-wide, wide, telephoto).
 *
 * On a multi-camera device such as the Galaxy S21 FE, the logical BACK camera is composed of
 * several physical cameras. CameraX exposes a single zoom ratio range for the logical camera and
 * switches physical lens automatically; this model lets the UI show the same "0.6x / 1x / 3x"
 * chips as Pixel Camera by computing the zoom ratio at which each physical lens becomes active.
 *
 * @property cameraId Camera2 id of the physical camera (or the logical id if not exposed).
 * @property focalLengthMm Focal length in millimetres (`LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]`).
 * @property sensorWidthMm Physical sensor width in millimetres (`SENSOR_INFO_PHYSICAL_SIZE`).
 * @property sensorHeightMm Physical sensor height in millimetres.
 * @property zoomRatio Zoom ratio relative to the default (1x) lens at which this lens is used.
 * @property kind Human-friendly classification of the lens.
 */
data class LensInfo(
    val cameraId: String,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val zoomRatio: Float,
    val kind: LensKind
) {
    /**
     * 35mm-equivalent focal length. Uses the sensor diagonal as reference (43.27mm for full frame).
     */
    val equivalentFocalLengthMm: Float
        get() {
            val diagonal = diagonal(sensorWidthMm, sensorHeightMm)
            return if (diagonal > 0f) focalLengthMm * FULL_FRAME_DIAGONAL_MM / diagonal else 0f
        }

    companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.27f
    }
}

/** Coarse classification of a physical lens, used for labelling in the UI. */
enum class LensKind {
    ULTRA_WIDE,
    WIDE,
    TELEPHOTO
}

/** Raw physical lens characteristics before zoom ratios are derived. */
data class RawPhysicalLens(
    val cameraId: String,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float
)

/**
 * Converts raw physical lens descriptors into ordered [LensInfo] entries with zoom ratios relative
 * to the logical camera's default lens.
 *
 * The zoom ratio of a lens is `fov(default) / fov(lens)`, which under the small-angle
 * approximation equals `(focal / sensorDiagonal) / (focalDefault / sensorDiagonalDefault)`.
 *
 * @param lenses raw physical lens descriptors.
 * @param defaultFocalLengthMm focal length of the logical camera's default lens.
 * @param defaultSensorWidthMm default lens sensor width.
 * @param defaultSensorHeightMm default lens sensor height.
 * @param zoomRange optional supported zoom range of the logical camera; lenses whose ratio falls
 *   outside of it are dropped because CameraX could never reach them.
 */
fun buildLensInfos(
    lenses: List<RawPhysicalLens>,
    defaultFocalLengthMm: Float,
    defaultSensorWidthMm: Float,
    defaultSensorHeightMm: Float,
    zoomRange: ClosedFloatingPointRange<Float>? = null
): List<LensInfo> {
    if (lenses.isEmpty() || defaultFocalLengthMm <= 0f) return emptyList()
    val defaultDiagonal = diagonal(defaultSensorWidthMm, defaultSensorHeightMm)
    if (defaultDiagonal <= 0f) return emptyList()
    val defaultAngular = defaultFocalLengthMm / defaultDiagonal

    return lenses
        .filter { it.focalLengthMm > 0f && diagonal(it.sensorWidthMm, it.sensorHeightMm) > 0f }
        .map { raw ->
            val angular = raw.focalLengthMm / diagonal(raw.sensorWidthMm, raw.sensorHeightMm)
            val rounded = roundZoomRatio(angular / defaultAngular)
            LensInfo(
                cameraId = raw.cameraId,
                focalLengthMm = raw.focalLengthMm,
                sensorWidthMm = raw.sensorWidthMm,
                sensorHeightMm = raw.sensorHeightMm,
                zoomRatio = rounded,
                kind = when {
                    rounded < 0.95f -> LensKind.ULTRA_WIDE
                    rounded <= 1.05f -> LensKind.WIDE
                    else -> LensKind.TELEPHOTO
                }
            )
        }
        .filter { zoomRange == null || it.zoomRatio in zoomRange }
        // Collapse duplicates (e.g. depth sensors sharing the wide lens' focal length).
        .distinctBy { it.zoomRatio }
        .sortedBy { it.zoomRatio }
}

private fun diagonal(w: Float, h: Float): Float = sqrt(w * w + h * h)

/** Rounds like Pixel's chips: one decimal below 1x (0.6x), halves up to 10x (2.5x), integers after. */
private fun roundZoomRatio(ratio: Float): Float = when {
    ratio < 1f -> round(ratio * 10f) / 10f
    ratio < 10f -> round(ratio * 2f) / 2f
    else -> round(ratio)
}
