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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LensInfoTest {

    // Approximate Galaxy S21 FE rear module: 12MP wide (1/1.76"), 12MP ultra-wide, 8MP 3x tele.
    private val wide = RawPhysicalLens("0", focalLengthMm = 5.4f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f)
    private val ultraWide = RawPhysicalLens("2", focalLengthMm = 1.8f, sensorWidthMm = 3.6f, sensorHeightMm = 2.7f)
    private val tele = RawPhysicalLens("3", focalLengthMm = 7.5f, sensorWidthMm = 2.9f, sensorHeightMm = 2.2f)
    private val depth = RawPhysicalLens("4", focalLengthMm = 5.4f, sensorWidthMm = 6.4f, sensorHeightMm = 4.8f)

    @Test
    fun buildLensInfos_sortsAndClassifies() {
        val lenses = buildLensInfos(
            lenses = listOf(tele, wide, ultraWide, depth),
            defaultFocalLengthMm = wide.focalLengthMm,
            defaultSensorWidthMm = wide.sensorWidthMm,
            defaultSensorHeightMm = wide.sensorHeightMm
        )
        assertThat(lenses.map { it.zoomRatio }).isEqualTo(listOf(0.6f, 1f, 3f))
        assertThat(lenses.map { it.kind }).isEqualTo(
            listOf(LensKind.ULTRA_WIDE, LensKind.WIDE, LensKind.TELEPHOTO)
        )
        // Depth sensor collapsed onto the wide lens.
        assertThat(lenses).hasSize(3)
    }

    @Test
    fun buildLensInfos_filtersOutOfZoomRange() {
        val lenses = buildLensInfos(
            lenses = listOf(tele, wide, ultraWide),
            defaultFocalLengthMm = wide.focalLengthMm,
            defaultSensorWidthMm = wide.sensorWidthMm,
            defaultSensorHeightMm = wide.sensorHeightMm,
            zoomRange = 1f..8f
        )
        assertThat(lenses.map { it.zoomRatio }).isEqualTo(listOf(1f, 3f))
    }

    @Test
    fun buildLensInfos_invalidInputsReturnEmpty() {
        assertThat(buildLensInfos(emptyList(), 5f, 6f, 4f)).isEmpty()
        assertThat(buildLensInfos(listOf(wide), 0f, 6f, 4f)).isEmpty()
        assertThat(buildLensInfos(listOf(wide), 5f, 0f, 0f)).isEmpty()
    }

    @Test
    fun equivalentFocalLength_isComputedFromDiagonal() {
        val info = buildLensInfos(listOf(wide), wide.focalLengthMm, wide.sensorWidthMm, wide.sensorHeightMm).single()
        // 5.4mm on an 8mm diagonal ≈ 29mm equivalent.
        assertThat(info.equivalentFocalLengthMm).isWithin(1f).of(29.2f)
    }
}
