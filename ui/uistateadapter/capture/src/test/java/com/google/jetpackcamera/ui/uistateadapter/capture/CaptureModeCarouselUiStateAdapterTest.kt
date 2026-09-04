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

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.core.camera.VideoRecordingState
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.ExternalCaptureMode
import com.google.jetpackcamera.model.ManualCapabilities
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.settings.model.TYPICAL_SYSTEM_CONSTRAINTS
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeCarouselUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeUiState
import com.google.jetpackcamera.ui.uistate.capture.ExtensionModeUiState
import com.google.jetpackcamera.ui.uistate.capture.ManualControlsUiState
import com.google.jetpackcamera.ui.uistate.capture.ShootingMode
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
internal class CaptureModeCarouselUiStateAdapterTest {

    private val proCapabilities = ManualCapabilities(
        isoRange = 100..3200,
        exposureTimeRangeNanos = 1_000L..1_000_000_000L,
        isManualSensorSupported = true
    )

    private fun constraints(
        extensions: Set<CameraExtensionMode> = emptySet(),
        manual: ManualCapabilities = ManualCapabilities.NONE
    ): CameraSystemConstraints = TYPICAL_SYSTEM_CONSTRAINTS.copy(
        perLensConstraints = TYPICAL_SYSTEM_CONSTRAINTS.perLensConstraints.mapValues {
            it.value.copy(supportedExtensionModes = extensions, manualCapabilities = manual)
        }
    )

    private fun build(
        settings: CameraAppSettings,
        systemConstraints: CameraSystemConstraints = constraints(),
        cameraState: CameraState = CameraState(),
        externalCaptureMode: ExternalCaptureMode = ExternalCaptureMode.Standard
    ): CaptureModeCarouselUiState = CaptureModeCarouselUiState.from(
        cameraAppSettings = settings,
        cameraState = cameraState,
        externalCaptureMode = externalCaptureMode,
        captureModeUiState = CaptureModeUiState.from(
            systemConstraints,
            settings,
            externalCaptureMode
        ),
        extensionModeUiState = ExtensionModeUiState.from(settings, systemConstraints),
        manualControlsUiState = ManualControlsUiState.from(
            settings,
            systemConstraints,
            cameraState,
            externalCaptureMode,
            isPanelOpen = false
        )
    )

    private fun CaptureModeCarouselUiState.shownModes(): List<ShootingMode> =
        (this as CaptureModeCarouselUiState.Available).modes.map { it.value }

    @Test
    fun recording_hidesCarousel() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.VIDEO_ONLY),
            cameraState = CameraState(videoRecordingState = VideoRecordingState.Starting())
        )
        assertThat(state).isEqualTo(CaptureModeCarouselUiState.Unavailable)
    }

    @Test
    fun externalIntent_hidesCarousel() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.IMAGE_ONLY),
            externalCaptureMode = ExternalCaptureMode.ImageCapture
        )
        assertThat(state).isEqualTo(CaptureModeCarouselUiState.Unavailable)
    }

    @Test
    fun plainDevice_showsOnlyVideoAndPhoto() {
        val state = build(CameraAppSettings(captureMode = CaptureMode.STANDARD))
        assertThat(state.shownModes()).containsExactly(ShootingMode.VIDEO, ShootingMode.PHOTO)
            .inOrder()
        assertThat((state as CaptureModeCarouselUiState.Available).selectedMode)
            .isEqualTo(ShootingMode.PHOTO)
        assertThat(state.currentCaptureMode).isEqualTo(CaptureMode.STANDARD)
    }

    @Test
    fun videoOnly_selectsVideo() {
        val state = build(CameraAppSettings(captureMode = CaptureMode.VIDEO_ONLY))
        assertThat((state as CaptureModeCarouselUiState.Available).selectedMode)
            .isEqualTo(ShootingMode.VIDEO)
    }

    @Test
    fun extensionsAndManual_addPortraitNightPro_inOrder() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.IMAGE_ONLY),
            systemConstraints = constraints(
                extensions = setOf(CameraExtensionMode.NIGHT, CameraExtensionMode.BOKEH),
                manual = proCapabilities
            )
        )
        assertThat(state.shownModes()).containsExactly(
            ShootingMode.VIDEO,
            ShootingMode.PHOTO,
            ShootingMode.PORTRAIT,
            ShootingMode.NIGHT,
            ShootingMode.PRO
        ).inOrder()
        assertThat((state as CaptureModeCarouselUiState.Available).isSelectable(ShootingMode.PRO))
            .isTrue()
    }

    @Test
    fun onlyNightSupported_omitsPortrait() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.IMAGE_ONLY),
            systemConstraints = constraints(extensions = setOf(CameraExtensionMode.NIGHT))
        )
        assertThat(state.shownModes())
            .containsExactly(ShootingMode.VIDEO, ShootingMode.PHOTO, ShootingMode.NIGHT)
            .inOrder()
    }

    @Test
    fun activeNightExtension_selectsNight() {
        val state = build(
            CameraAppSettings(
                captureMode = CaptureMode.IMAGE_ONLY,
                extensionMode = CameraExtensionMode.NIGHT
            ),
            systemConstraints = constraints(extensions = setOf(CameraExtensionMode.NIGHT))
        )
        state as CaptureModeCarouselUiState.Available
        assertThat(state.selectedMode).isEqualTo(ShootingMode.NIGHT)
        assertThat(state.currentExtensionMode).isEqualTo(CameraExtensionMode.NIGHT)
    }

    @Test
    fun proModeEnabled_selectsPro_andIsProModeEnabled() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.STANDARD, isProModeEnabled = true),
            systemConstraints = constraints(manual = proCapabilities)
        )
        state as CaptureModeCarouselUiState.Available
        assertThat(state.selectedMode).isEqualTo(ShootingMode.PRO)
        assertThat(state.isProModeEnabled).isTrue()
    }

    @Test
    fun proModeEnabledWithoutManualSupport_fallsBackToPhoto() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.STANDARD, isProModeEnabled = true)
        )
        state as CaptureModeCarouselUiState.Available
        assertThat(state.selectedMode).isEqualTo(ShootingMode.PHOTO)
        assertThat(state.isProModeEnabled).isFalse()
        assertThat(state.entryFor(ShootingMode.PRO)).isNull()
    }

    @Test
    fun videoOnlyWithExtensions_disablesPortraitNightWithRationale() {
        val state = build(
            CameraAppSettings(captureMode = CaptureMode.VIDEO_ONLY),
            systemConstraints = constraints(extensions = setOf(CameraExtensionMode.BOKEH))
        )
        state as CaptureModeCarouselUiState.Available
        assertThat(state.selectedMode).isEqualTo(ShootingMode.VIDEO)
        val portrait = state.entryFor(ShootingMode.PORTRAIT)
        assertThat(portrait).isInstanceOf(SingleSelectableUiState.Disabled::class.java)
        assertThat((portrait as SingleSelectableUiState.Disabled).disabledReason)
            .isEqualTo(DisabledReason.EXTENSION_UNSUPPORTED_IN_CONFIGURATION)
        assertThat(state.commandsFor(ShootingMode.PORTRAIT)).isEmpty()
    }

    @Test
    fun persistedNightOnLensWithoutSupport_fallsBackToPhoto() {
        val state = build(
            CameraAppSettings(
                captureMode = CaptureMode.IMAGE_ONLY,
                extensionMode = CameraExtensionMode.NIGHT
            )
        )
        state as CaptureModeCarouselUiState.Available
        assertThat(state.selectedMode).isEqualTo(ShootingMode.PHOTO)
        assertThat(state.entryFor(ShootingMode.NIGHT)).isNull()
    }
}
