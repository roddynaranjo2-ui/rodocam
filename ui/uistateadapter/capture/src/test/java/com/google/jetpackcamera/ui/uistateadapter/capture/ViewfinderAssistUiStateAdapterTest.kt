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
package com.google.jetpackcamera.ui.uistateadapter.capture

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.model.CameraCoach
import com.google.jetpackcamera.model.CoachHint
import com.google.jetpackcamera.model.CompositionGrid
import com.google.jetpackcamera.model.FrameStats
import com.google.jetpackcamera.model.HorizonLevel
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.model.LensInfo
import com.google.jetpackcamera.model.LensKind
import com.google.jetpackcamera.model.LumaHistogram
import com.google.jetpackcamera.model.ThermalStatus
import com.google.jetpackcamera.model.ViewfinderAssistSettings
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.TYPICAL_SYSTEM_CONSTRAINTS
import com.google.jetpackcamera.ui.uistate.capture.ViewfinderAssistUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
internal class ViewfinderAssistUiStateAdapterTest {

    private val stats = FrameStats(
        histogram = LumaHistogram(IntArray(64) { if (it in 10..50) 100 else 0 }),
        width = 320,
        height = 240,
        timestampNanos = 123L,
        sharpness = 0.2f
    )

    private fun settings(assist: ViewfinderAssistSettings) =
        CameraAppSettings(cameraLensFacing = LensFacing.BACK, viewfinderAssist = assist)

    @Test
    fun from_nothingEnabled_isDisabled() {
        val state =
            ViewfinderAssistUiState.from(settings(ViewfinderAssistSettings()), CameraState())
        assertThat(state).isEqualTo(ViewfinderAssistUiState.Disabled)
    }

    @Test
    fun from_coachEnabled_isEnabledWithFrameStats() {
        val state = ViewfinderAssistUiState.from(
            settings(ViewfinderAssistSettings(isCoachEnabled = true)),
            CameraState(frameStats = stats)
        ) as ViewfinderAssistUiState.Enabled

        assertThat(state.isCoachEnabled).isTrue()
        assertThat(state.frameStats).isEqualTo(stats)
        assertThat(state.hasFrameStats).isTrue()
    }

    @Test
    fun from_focusPeakingOnly_isEnabledWithoutFrameStats() {
        val state = ViewfinderAssistUiState.from(
            settings(ViewfinderAssistSettings(isFocusPeakingEnabled = true)),
            CameraState(frameStats = stats)
        ) as ViewfinderAssistUiState.Enabled

        assertThat(state.isFocusPeakingEnabled).isTrue()
        // Peaking runs on the GPU; no CPU stats are needed nor surfaced.
        assertThat(state.frameStats).isEqualTo(FrameStats.UNKNOWN)
        assertThat(state.showTopShot).isFalse()
    }

    @Test
    fun from_topShotEnabled_showsBadgeOnlyWithTimestampedStats() {
        val assist = ViewfinderAssistSettings(isTopShotEnabled = true)
        val withStats = ViewfinderAssistUiState.from(
            settings(assist),
            CameraState(frameStats = stats)
        ) as ViewfinderAssistUiState.Enabled
        assertThat(withStats.showTopShot).isTrue()

        val noStats = ViewfinderAssistUiState.from(
            settings(assist),
            CameraState()
        ) as ViewfinderAssistUiState.Enabled
        assertThat(noStats.showTopShot).isFalse()
    }

    @Test
    fun from_zoomAndOpticalZoom_areForwardedFromStateAndConstraints() {
        val tele = LensInfo("2", 12f, 4f, 3f, zoomRatio = 3f, kind = LensKind.TELEPHOTO)
        val wide = LensInfo("0", 6f, 6f, 4.5f, zoomRatio = 1f, kind = LensKind.WIDE)
        val constraints = TYPICAL_SYSTEM_CONSTRAINTS.copy(
            perLensConstraints = TYPICAL_SYSTEM_CONSTRAINTS.perLensConstraints.mapValues {
                it.value.copy(physicalLenses = listOf(wide, tele))
            }
        )
        val state = ViewfinderAssistUiState.from(
            settings(ViewfinderAssistSettings(isCoachEnabled = true)),
            CameraState(zoomRatios = mapOf(LensFacing.BACK to 8f)),
            constraints
        ) as ViewfinderAssistUiState.Enabled

        assertThat(state.zoomRatio).isEqualTo(8f)
        assertThat(state.maxOpticalZoom).isEqualTo(3f)
    }

    @Test
    fun from_noConstraints_defaultsOpticalZoomToOne() {
        val state = ViewfinderAssistUiState.from(
            settings(ViewfinderAssistSettings(grid = CompositionGrid.THIRDS)),
            CameraState()
        ) as ViewfinderAssistUiState.Enabled
        assertThat(state.maxOpticalZoom).isEqualTo(1f)
        assertThat(state.zoomRatio).isEqualTo(1f)
    }

    @Test
    fun from_throttling_isEnabledEvenWithoutAssists() {
        val state = ViewfinderAssistUiState.from(
            settings(ViewfinderAssistSettings()),
            CameraState(thermalStatus = ThermalStatus.SEVERE)
        )
        assertThat(state).isInstanceOf(ViewfinderAssistUiState.Enabled::class.java)
        state as ViewfinderAssistUiState.Enabled
        assertThat(state.thermalStatus).isEqualTo(ThermalStatus.SEVERE)
        assertThat(state.showThermalWarning).isTrue()
    }

    @Test
    fun from_lightThermal_staysDisabledWithoutAssists() {
        val state = ViewfinderAssistUiState.from(
            settings(ViewfinderAssistSettings()),
            CameraState(thermalStatus = ThermalStatus.LIGHT)
        )
        assertThat(state).isEqualTo(ViewfinderAssistUiState.Disabled)
    }

    @Test
    fun coachInputs_onlyPassesLevelWhenLevelEnabled() {
        val level = HorizonLevel(rollDegrees = 5f, pitchDegrees = 0f)
        val withLevel =
            ViewfinderAssistUiState.Enabled(isLevelEnabled = true, isCoachEnabled = true)
        val withoutLevel = ViewfinderAssistUiState.Enabled(isCoachEnabled = true)

        assertThat(withLevel.coachInputs(level).horizonLevel).isEqualTo(level)
        assertThat(withoutLevel.coachInputs(level).horizonLevel).isNull()
    }

    @Test
    fun coachInputs_highDigitalZoom_producesHighZoomHint() {
        val state = ViewfinderAssistUiState.Enabled(
            isCoachEnabled = true,
            zoomRatio = 10f,
            maxOpticalZoom = 2f
        )
        val hints = CameraCoach.evaluate(state.coachInputs(null))
        assertThat(hints).contains(CoachHint.HIGH_ZOOM)
    }
}
