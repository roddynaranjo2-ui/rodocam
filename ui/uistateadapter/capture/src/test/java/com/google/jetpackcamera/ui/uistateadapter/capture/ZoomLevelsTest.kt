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
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.LensInfo
import com.google.jetpackcamera.model.LensKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZoomLevelsTest {

    private fun lens(ratio: Float, kind: LensKind) =
        LensInfo("id$ratio", 5f, 6f, 4f, ratio, kind)

    @Test
    fun noPhysicalLenses_fallsBackToGenericLadder() {
        val levels = buildZoomLevels(Range(0.5f, 10f), emptyList())
        assertThat(levels).isEqualTo(listOf(0.5f, 1f, 2f, 5f))
    }

    @Test
    fun noPhysicalLenses_frontCameraRange() {
        val levels = buildZoomLevels(Range(1f, 4f), emptyList())
        assertThat(levels).isEqualTo(listOf(1f, 2f))
    }

    @Test
    fun s21fe_tripleCamera_showsLensChipsPlusTwoX() {
        val lenses = listOf(
            lens(0.6f, LensKind.ULTRA_WIDE),
            lens(1f, LensKind.WIDE),
            lens(3f, LensKind.TELEPHOTO)
        )
        val levels = buildZoomLevels(Range(0.6f, 30f), lenses)
        assertThat(levels).isEqualTo(listOf(0.6f, 1f, 2f, 3f))
    }

    @Test
    fun teleAtTwoX_doesNotDuplicateTwoXChip() {
        val lenses = listOf(lens(1f, LensKind.WIDE), lens(2f, LensKind.TELEPHOTO))
        val levels = buildZoomLevels(Range(1f, 8f), lenses)
        assertThat(levels).isEqualTo(listOf(1f, 2f))
    }

    @Test
    fun lensesOutsideZoomRange_areIgnored() {
        val lenses = listOf(
            lens(0.6f, LensKind.ULTRA_WIDE),
            lens(1f, LensKind.WIDE),
            lens(5f, LensKind.TELEPHOTO)
        )
        // Range starts at 1x, so the ultra-wide is unreachable and the ladder is used.
        val levels = buildZoomLevels(Range(1f, 4f), lenses)
        assertThat(levels).isEqualTo(listOf(1f, 2f))
    }

    @Test
    fun singleLens_usesGenericLadder() {
        val levels = buildZoomLevels(Range(1f, 10f), listOf(lens(1f, LensKind.WIDE)))
        assertThat(levels).isEqualTo(listOf(1f, 2f, 5f))
    }
}
