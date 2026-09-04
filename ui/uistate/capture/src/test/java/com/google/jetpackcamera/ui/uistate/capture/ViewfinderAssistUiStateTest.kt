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

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.FrameStats
import com.google.jetpackcamera.model.LumaHistogram
import com.google.jetpackcamera.model.ThermalStatus
import org.junit.Test

class ViewfinderAssistUiStateTest {

    private val liveStats = FrameStats(
        histogram = LumaHistogram(IntArray(LumaHistogram.DEFAULT_BIN_COUNT) { 10 }),
        clippedHighlightsFraction = 0.05f,
        crushedShadowsFraction = 0f,
        width = 320,
        height = 240,
        timestampNanos = 1L
    )

    @Test
    fun histogram_hidden_without_frame_stats() {
        val state = ViewfinderAssistUiState.Enabled(isHistogramEnabled = true)
        assertThat(state.hasFrameStats).isFalse()
        assertThat(state.showHistogram).isFalse()
    }

    @Test
    fun histogram_shown_with_frame_stats() {
        val state = ViewfinderAssistUiState.Enabled(
            isHistogramEnabled = true,
            frameStats = liveStats
        )
        assertThat(state.hasFrameStats).isTrue()
        assertThat(state.showHistogram).isTrue()
    }

    @Test
    fun zebras_require_enabled_stats_and_clipping() {
        val noClip = liveStats.copy(clippedHighlightsFraction = 0f)
        assertThat(
            ViewfinderAssistUiState.Enabled(isZebrasEnabled = true, frameStats = noClip).showZebras
        ).isFalse()
        assertThat(
            ViewfinderAssistUiState.Enabled(isZebrasEnabled = false, frameStats = liveStats)
                .showZebras
        ).isFalse()
        assertThat(
            ViewfinderAssistUiState.Enabled(isZebrasEnabled = true, frameStats = liveStats)
                .showZebras
        ).isTrue()
    }

    @Test
    fun thermalWarning_shownFromModerate() {
        assertThat(ViewfinderAssistUiState.Enabled().showThermalWarning).isFalse()
        assertThat(
            ViewfinderAssistUiState.Enabled(thermalStatus = ThermalStatus.LIGHT).showThermalWarning
        ).isFalse()
        assertThat(
            ViewfinderAssistUiState.Enabled(thermalStatus = ThermalStatus.MODERATE)
                .showThermalWarning
        ).isTrue()
        assertThat(
            ViewfinderAssistUiState.Enabled(thermalStatus = ThermalStatus.CRITICAL)
                .showThermalWarning
        ).isTrue()
    }

    @Test
    fun haptics_accessor_defaults_to_true_when_disabled() {
        assertThat(ViewfinderAssistUiState.Disabled.isHapticsEnabled).isTrue()
        assertThat(
            (ViewfinderAssistUiState.Enabled(isHapticsEnabled = false) as ViewfinderAssistUiState)
                .isHapticsEnabled
        ).isFalse()
    }
}
