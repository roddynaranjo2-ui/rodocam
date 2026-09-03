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
package com.google.jetpackcamera.ui.controller.impl

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.core.camera.testing.FakeCameraSystem
import com.google.jetpackcamera.model.ManualControls
import com.google.jetpackcamera.model.WhiteBalanceMode
import com.google.jetpackcamera.ui.uistate.capture.TrackedCaptureUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
internal class ManualControlsControllerImplTest {
    private val testScope = TestScope()
    private val testDispatcher = StandardTestDispatcher(testScope.testScheduler)

    private val cameraSystem = FakeCameraSystem()
    private val trackedCaptureUiState = MutableStateFlow(TrackedCaptureUiState())
    private lateinit var controller: ManualControlsControllerImpl

    private val controls: ManualControls
        get() = cameraSystem.getCurrentSettings().value?.manualControls ?: ManualControls.AUTO

    @Before
    fun setup() {
        controller = ManualControlsControllerImpl(
            cameraSystem = cameraSystem,
            trackedCaptureUiState = trackedCaptureUiState,
            coroutineContext = testDispatcher
        )
    }

    @Test
    fun toggleProPanel_mutatesUiState() = testScope.runTest {
        val initial = trackedCaptureUiState.value.isProPanelOpen
        controller.toggleProPanel()
        assertThat(trackedCaptureUiState.value.isProPanelOpen).isEqualTo(!initial)
    }

    @Test
    fun setProModeEnabled_persistsThroughCallback_andClosesPanelWhenDisabled() =
        testScope.runTest {
            var persisted: Boolean? = null
            controller = ManualControlsControllerImpl(
                cameraSystem = cameraSystem,
                trackedCaptureUiState = trackedCaptureUiState,
                coroutineContext = testDispatcher,
                onProModeEnabledPersist = { persisted = it }
            )
            trackedCaptureUiState.value = TrackedCaptureUiState(isProPanelOpen = true)
            controller.setProModeEnabled(false)
            advanceUntilIdle()
            assertThat(persisted).isFalse()
            assertThat(trackedCaptureUiState.value.isProPanelOpen).isFalse()
        }

    @Test
    fun setIso_onlyChangesIso() = testScope.runTest {
        controller.setFocusDistance(2f)
        controller.setIso(800)
        assertThat(controls.iso).isEqualTo(800)
        assertThat(controls.focusDistanceDiopters).isEqualTo(2f)
        controller.setIso(null)
        assertThat(controls.iso).isNull()
    }

    @Test
    fun setExposureCompensationIndex_zeroCollapsesToAuto() = testScope.runTest {
        controller.setExposureCompensationIndex(3)
        assertThat(controls.exposureCompensationIndex).isEqualTo(3)
        controller.setExposureCompensationIndex(0)
        assertThat(controls.exposureCompensationIndex).isNull()
    }

    @Test
    fun setWhiteBalance_autoCollapsesToNull_andClearsKelvin() = testScope.runTest {
        controller.setWhiteBalanceKelvin(4000)
        assertThat(controls.whiteBalanceKelvin).isEqualTo(4000)
        controller.setWhiteBalance(WhiteBalanceMode.DAYLIGHT)
        assertThat(controls.whiteBalance).isEqualTo(WhiteBalanceMode.DAYLIGHT)
        assertThat(controls.whiteBalanceKelvin).isNull()
        controller.setWhiteBalance(WhiteBalanceMode.AUTO)
        assertThat(controls.whiteBalance).isNull()
        assertThat(controls.isManualWhiteBalance).isFalse()
    }

    @Test
    fun setWhiteBalanceKelvin_clampsToRange_andKeepsPresetForFallback() = testScope.runTest {
        controller.setWhiteBalance(WhiteBalanceMode.SHADE)
        controller.setWhiteBalanceKelvin(99_999)
        assertThat(controls.whiteBalanceKelvin)
            .isEqualTo(ManualControls.WHITE_BALANCE_KELVIN_RANGE.last)
        assertThat(controls.whiteBalance).isEqualTo(WhiteBalanceMode.SHADE)
        assertThat(controls.isKelvinWhiteBalance).isTrue()
        controller.setWhiteBalanceKelvin(null)
        assertThat(controls.whiteBalanceKelvin).isNull()
        assertThat(controls.whiteBalance).isEqualTo(WhiteBalanceMode.SHADE)
    }

    @Test
    fun setShadowsBoost_clampsAndNormalisesZero() = testScope.runTest {
        controller.setShadowsBoost(0.4f)
        assertThat(controls.shadowsBoost).isEqualTo(0.4f)
        controller.setShadowsBoost(5f)
        assertThat(controls.shadowsBoost).isEqualTo(1f)
        controller.setShadowsBoost(-5f)
        assertThat(controls.shadowsBoost).isEqualTo(-1f)
        controller.setShadowsBoost(0f)
        assertThat(controls.shadowsBoost).isNull()
        assertThat(controls.isShadowsAdjusted).isFalse()
    }

    @Test
    fun setAeLock_andAwbLock_toggle() = testScope.runTest {
        controller.setAeLock(true)
        controller.setAwbLock(true)
        assertThat(controls.aeLock).isTrue()
        assertThat(controls.awbLock).isTrue()
        controller.setAeLock(false)
        assertThat(controls.aeLock).isFalse()
    }

    @Test
    fun resetToAuto_clearsEverything() = testScope.runTest {
        controller.setIso(400)
        controller.setWhiteBalanceKelvin(3200)
        controller.setShadowsBoost(0.5f)
        controller.setAeLock(true)
        assertThat(controls.hasOverrides).isTrue()
        controller.resetToAuto()
        assertThat(controls).isEqualTo(ManualControls.AUTO)
    }
}
