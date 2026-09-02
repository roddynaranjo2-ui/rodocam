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
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.model.ConcurrentCameraMode
import com.google.jetpackcamera.model.FlashMode
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.settings.model.CameraConstraints
import com.google.jetpackcamera.settings.model.CameraSystemConstraints
import com.google.jetpackcamera.ui.uistate.capture.ExtensionModeUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
internal class ExtensionModeUiStateAdapterTest {

    private val emptyCameraConstraints = CameraConstraints(
        supportedStabilizationModes = emptySet(),
        supportedFixedFrameRates = emptySet(),
        supportedDynamicRanges = emptySet(),
        supportedVideoQualitiesMap = emptyMap(),
        supportedImageFormatsMap = emptyMap(),
        supportedIlluminants = emptySet(),
        supportedFlashModes = emptySet(),
        supportedZoomRange = null,
        unsupportedStabilizationFpsMap = emptyMap(),
        supportedTestPatterns = emptySet()
    )

    private val defaultSettings = CameraAppSettings(captureMode = CaptureMode.IMAGE_ONLY)

    private fun constraintsWith(
        modes: Set<CameraExtensionMode>,
        lens: LensFacing = defaultSettings.cameraLensFacing
    ) = CameraSystemConstraints(
        perLensConstraints = mapOf(
            lens to emptyCameraConstraints.copy(supportedExtensionModes = modes)
        )
    )

    @Test
    fun from_noSupportedExtensions_returnsUnavailable() {
        val state = ExtensionModeUiState.from(defaultSettings, constraintsWith(emptySet()))

        assertThat(state).isEqualTo(ExtensionModeUiState.Unavailable)
    }

    @Test
    fun from_missingLensConstraints_returnsUnavailable() {
        val constraints = constraintsWith(
            modes = setOf(CameraExtensionMode.NIGHT),
            lens = LensFacing.FRONT
        )
        val settings = defaultSettings.copy(cameraLensFacing = LensFacing.BACK)

        assertThat(ExtensionModeUiState.from(settings, constraints))
            .isEqualTo(ExtensionModeUiState.Unavailable)
    }

    @Test
    fun from_supportedModes_returnsAvailableInDisplayOrder() {
        val constraints = constraintsWith(
            setOf(CameraExtensionMode.HDR, CameraExtensionMode.NIGHT, CameraExtensionMode.BOKEH)
        )
        val settings = defaultSettings.copy(extensionMode = CameraExtensionMode.BOKEH)

        val state = ExtensionModeUiState.from(settings, constraints)

        assertThat(state).isEqualTo(
            ExtensionModeUiState.Available(
                selectedMode = CameraExtensionMode.BOKEH,
                availableModes = listOf(
                    CameraExtensionMode.NIGHT,
                    CameraExtensionMode.BOKEH,
                    CameraExtensionMode.HDR
                ),
                isSupported = true
            )
        )
    }

    @Test
    fun from_videoOnly_isNotSupportedAndReportsOff() {
        val constraints = constraintsWith(setOf(CameraExtensionMode.NIGHT))
        val settings = defaultSettings.copy(
            captureMode = CaptureMode.VIDEO_ONLY,
            extensionMode = CameraExtensionMode.NIGHT
        )

        val state = ExtensionModeUiState.from(settings, constraints)

        assertThat(state).isInstanceOf(ExtensionModeUiState.Available::class.java)
        state as ExtensionModeUiState.Available
        assertThat(state.isSupported).isFalse()
        assertThat(state.selectedMode).isEqualTo(CameraExtensionMode.NONE)
    }

    @Test
    fun from_dualConcurrentCamera_isNotSupported() {
        val constraints = constraintsWith(setOf(CameraExtensionMode.NIGHT))
        val settings = defaultSettings.copy(concurrentCameraMode = ConcurrentCameraMode.DUAL)

        val state = ExtensionModeUiState.from(settings, constraints)
            as ExtensionModeUiState.Available

        assertThat(state.isSupported).isFalse()
    }

    @Test
    fun from_lowLightBoostFlash_isNotSupported() {
        val constraints = constraintsWith(setOf(CameraExtensionMode.NIGHT))
        val settings = defaultSettings.copy(flashMode = FlashMode.LOW_LIGHT_BOOST)

        val state = ExtensionModeUiState.from(settings, constraints)
            as ExtensionModeUiState.Available

        assertThat(state.isSupported).isFalse()
    }

    @Test
    fun from_standardCaptureMode_isSupported() {
        val constraints = constraintsWith(setOf(CameraExtensionMode.FACE_RETOUCH))
        val settings = defaultSettings.copy(captureMode = CaptureMode.STANDARD)

        val state = ExtensionModeUiState.from(settings, constraints)
            as ExtensionModeUiState.Available

        assertThat(state.isSupported).isTrue()
        assertThat(state.availableModes).containsExactly(CameraExtensionMode.FACE_RETOUCH)
    }
}
