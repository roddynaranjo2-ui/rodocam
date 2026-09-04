/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.google.jetpackcamera.ui.components.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-logic tests for the zoom pill helpers (no Compose runtime needed). */
class ZoomPillFormatTest {

    private val pixelLevels = listOf(0.5f, 1f, 2f, 5f)

    @Test
    fun selectedZoomIndex_picksLastLevelAtOrBelowRatio() {
        assertThat(selectedZoomIndex(pixelLevels, 1f)).isEqualTo(1)
        assertThat(selectedZoomIndex(pixelLevels, 1.7f)).isEqualTo(1)
        assertThat(selectedZoomIndex(pixelLevels, 2f)).isEqualTo(2)
        assertThat(selectedZoomIndex(pixelLevels, 2.3f)).isEqualTo(2)
        assertThat(selectedZoomIndex(pixelLevels, 4.99f)).isEqualTo(2)
        assertThat(selectedZoomIndex(pixelLevels, 5f)).isEqualTo(3)
        assertThat(selectedZoomIndex(pixelLevels, 30f)).isEqualTo(3)
    }

    @Test
    fun selectedZoomIndex_belowEveryLevel_selectsFirstChip() {
        assertThat(selectedZoomIndex(pixelLevels, 0.4f)).isEqualTo(0)
        assertThat(selectedZoomIndex(listOf(1f, 2f), 0.6f)).isEqualTo(0)
    }

    @Test
    fun selectedZoomIndex_toleratesFloatNoise() {
        // 1.999.. from an animation should still light the "2" chip.
        assertThat(selectedZoomIndex(pixelLevels, 1.998f)).isEqualTo(2)
    }

    @Test
    fun selectedZoomIndex_emptyList_isZero() {
        assertThat(selectedZoomIndex(emptyList(), 3f)).isEqualTo(0)
    }

    @Test
    fun formatZoomLevel_isCompact() {
        assertThat(formatZoomLevel(0.5f)).isEqualTo("0.5")
        assertThat(formatZoomLevel(0.6f)).isEqualTo("0.6")
        assertThat(formatZoomLevel(0.55f)).isEqualTo("0.6") // sub-1x rounds up
        assertThat(formatZoomLevel(1f)).isEqualTo("1")
        assertThat(formatZoomLevel(2f)).isEqualTo("2")
        assertThat(formatZoomLevel(3.3f)).isEqualTo("3.3")
        assertThat(formatZoomLevel(3.39f)).isEqualTo("3.3") // >= 1x rounds down
        assertThat(formatZoomLevel(10f)).isEqualTo("10")
    }

    @Test
    fun formatZoomRatio_alwaysOneDecimalWithSuffix() {
        assertThat(formatZoomRatio(1f)).isEqualTo("1.0x")
        assertThat(formatZoomRatio(2.34f)).isEqualTo("2.3x")
        assertThat(formatZoomRatio(0.6f)).isEqualTo("0.6x")
        assertThat(formatZoomRatio(10f)).isEqualTo("10.0x")
        assertThat(formatZoomRatio(-1f)).isEqualTo("0.0x")
    }

    @Test
    fun zoomChipLabel_selectedShowsLiveRatio_othersShowTarget() {
        assertThat(zoomChipLabel(2f, 2.3f, isSelected = true)).isEqualTo("2.3x")
        assertThat(zoomChipLabel(2f, 2.3f, isSelected = false)).isEqualTo("2")
        assertThat(zoomChipLabel(0.5f, 0.5f, isSelected = true)).isEqualTo("0.5x")
        assertThat(zoomChipLabel(5f, 1f, isSelected = false)).isEqualTo("5")
    }
}
