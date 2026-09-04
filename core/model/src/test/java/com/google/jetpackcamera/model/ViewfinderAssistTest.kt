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

class ViewfinderAssistTest {

    @Test
    fun grid_lineFractions() {
        assertThat(CompositionGrid.OFF.lineFractions).isEmpty()
        assertThat(CompositionGrid.CENTER.lineFractions).isEmpty()
        assertThat(CompositionGrid.THIRDS.lineFractions).hasSize(2)
        assertThat(CompositionGrid.FOURTHS.lineFractions).containsExactly(0.25f, 0.5f, 0.75f)
        val golden = CompositionGrid.GOLDEN_RATIO.lineFractions
        assertThat(golden[0] + golden[1]).isWithin(1e-5f).of(1f)
        assertThat(golden[1] / golden[0]).isWithin(1e-3f).of(1.618f)
        assertThat(CompositionGrid.DIAGONALS.hasDiagonals).isTrue()
        assertThat(CompositionGrid.THIRDS.hasDiagonals).isFalse()
        assertThat(CompositionGrid.CENTER.hasCenterMark).isTrue()
    }

    @Test
    fun grid_allFractionsInsideUnitInterval() {
        CompositionGrid.values().forEach { grid ->
            grid.lineFractions.forEach { f ->
                assertThat(f).isGreaterThan(0f)
                assertThat(f).isLessThan(1f)
            }
        }
    }

    @Test
    fun settings_needsFrameAnalysis() {
        assertThat(ViewfinderAssistSettings.DEFAULT.needsFrameAnalysis).isFalse()
        assertThat(ViewfinderAssistSettings(isHistogramEnabled = true).needsFrameAnalysis).isTrue()
        assertThat(ViewfinderAssistSettings(isZebrasEnabled = true).needsFrameAnalysis).isTrue()
        assertThat(
            ViewfinderAssistSettings(isLevelEnabled = true, grid = CompositionGrid.THIRDS)
                .needsFrameAnalysis
        ).isFalse()
    }

    @Test
    fun settings_needsShaderEffect() {
        assertThat(ViewfinderAssistSettings.DEFAULT.needsShaderEffect).isFalse()
        assertThat(ViewfinderAssistSettings(isFocusPeakingEnabled = true).needsShaderEffect)
            .isTrue()
        assertThat(ViewfinderAssistSettings(isZebrasEnabled = true).needsShaderEffect).isTrue()
        assertThat(
            ViewfinderAssistSettings(isHistogramEnabled = true, isCoachEnabled = true)
                .needsShaderEffect
        ).isFalse()
    }

    @Test
    fun settings_sanitizedClampsThreshold() {
        assertThat(ViewfinderAssistSettings(zebraThresholdPercent = 50).sanitized().zebraThresholdPercent)
            .isEqualTo(ViewfinderAssistSettings.MIN_ZEBRA_THRESHOLD_PERCENT)
        assertThat(ViewfinderAssistSettings(zebraThresholdPercent = 150).sanitized().zebraThresholdPercent)
            .isEqualTo(ViewfinderAssistSettings.MAX_ZEBRA_THRESHOLD_PERCENT)
        assertThat(ViewfinderAssistSettings(zebraThresholdPercent = 97).sanitized().zebraThresholdPercent)
            .isEqualTo(97)
    }

    @Test
    fun settings_defaults() {
        val defaults = ViewfinderAssistSettings.DEFAULT
        assertThat(defaults.grid).isEqualTo(CompositionGrid.OFF)
        assertThat(defaults.isHapticsEnabled).isTrue()
        assertThat(defaults.zebraThresholdPercent)
            .isEqualTo(ViewfinderAssistSettings.DEFAULT_ZEBRA_THRESHOLD_PERCENT)
    }
}
