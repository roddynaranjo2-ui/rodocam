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
import com.google.jetpackcamera.model.CameraExtensionMode
import com.google.jetpackcamera.model.CaptureMode
import com.google.jetpackcamera.ui.uistate.DisableRationale
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureModeCarouselUiStateTest {

    private object TestRationale : DisableRationale {
        override val reasonTextResId: Int = 0
    }

    private fun allSelectable(
        selected: ShootingMode,
        captureMode: CaptureMode = CaptureMode.STANDARD,
        extension: CameraExtensionMode = CameraExtensionMode.NONE,
        pro: Boolean = false
    ) = CaptureModeCarouselUiState.Available(
        selectedMode = selected,
        modes = ShootingMode.ORDERED.map { SingleSelectableUiState.SelectableUi(it) },
        currentCaptureMode = captureMode,
        currentExtensionMode = extension,
        isProModeEnabled = pro
    )

    @Test
    fun resolveSelectedMode_prioritizesProThenExtensionThenPipeline() {
        assertThat(
            CaptureModeCarouselUiState.resolveSelectedMode(
                CaptureMode.VIDEO_ONLY,
                CameraExtensionMode.NIGHT,
                isProModeEnabled = true
            )
        ).isEqualTo(ShootingMode.PRO)
        assertThat(
            CaptureModeCarouselUiState.resolveSelectedMode(
                CaptureMode.IMAGE_ONLY,
                CameraExtensionMode.BOKEH,
                isProModeEnabled = false
            )
        ).isEqualTo(ShootingMode.PORTRAIT)
        assertThat(
            CaptureModeCarouselUiState.resolveSelectedMode(
                CaptureMode.STANDARD,
                CameraExtensionMode.NIGHT,
                isProModeEnabled = false
            )
        ).isEqualTo(ShootingMode.NIGHT)
        assertThat(
            CaptureModeCarouselUiState.resolveSelectedMode(
                CaptureMode.VIDEO_ONLY,
                CameraExtensionMode.NONE,
                isProModeEnabled = false
            )
        ).isEqualTo(ShootingMode.VIDEO)
        assertThat(
            CaptureModeCarouselUiState.resolveSelectedMode(
                CaptureMode.STANDARD,
                CameraExtensionMode.NONE,
                isProModeEnabled = false
            )
        ).isEqualTo(ShootingMode.PHOTO)
        // HDR / face retouch are not carousel presets: they read as plain photo.
        assertThat(
            CaptureModeCarouselUiState.resolveSelectedMode(
                CaptureMode.IMAGE_ONLY,
                CameraExtensionMode.HDR,
                isProModeEnabled = false
            )
        ).isEqualTo(ShootingMode.PHOTO)
    }

    @Test
    fun available_requiresSelectedModeToBeSelectable() {
        assertThrows(IllegalStateException::class.java) {
            CaptureModeCarouselUiState.Available(
                selectedMode = ShootingMode.NIGHT,
                modes = listOf(
                    SingleSelectableUiState.SelectableUi(ShootingMode.PHOTO),
                    SingleSelectableUiState.Disabled(ShootingMode.NIGHT, TestRationale)
                )
            )
        }
        assertThrows(IllegalStateException::class.java) {
            CaptureModeCarouselUiState.Available(
                selectedMode = ShootingMode.PHOTO,
                modes = emptyList()
            )
        }
    }

    @Test
    fun selectedIndex_and_entryFor_followModeOrder() {
        val state = allSelectable(ShootingMode.PORTRAIT)
        assertThat(state.selectedIndex)
            .isEqualTo(ShootingMode.ORDERED.indexOf(ShootingMode.PORTRAIT))
        assertThat(state.entryFor(ShootingMode.PRO)?.value).isEqualTo(ShootingMode.PRO)
        assertThat(state.isSelectable(ShootingMode.NIGHT)).isTrue()
    }

    @Test
    fun commandsFor_sameOrUnselectableTarget_isEmpty() {
        val state = CaptureModeCarouselUiState.Available(
            selectedMode = ShootingMode.PHOTO,
            modes = listOf(
                SingleSelectableUiState.SelectableUi(ShootingMode.PHOTO),
                SingleSelectableUiState.Disabled(ShootingMode.VIDEO, TestRationale)
            ),
            currentCaptureMode = CaptureMode.IMAGE_ONLY
        )
        assertThat(state.commandsFor(ShootingMode.PHOTO)).isEmpty()
        assertThat(state.commandsFor(ShootingMode.VIDEO)).isEmpty()
        assertThat(state.commandsFor(ShootingMode.NIGHT)).isEmpty()
    }

    @Test
    fun commandsFor_photoToVideo_setsVideoOnly() {
        val state = allSelectable(ShootingMode.PHOTO, captureMode = CaptureMode.STANDARD)
        assertThat(state.commandsFor(ShootingMode.VIDEO)).containsExactly(
            CarouselCommand.SetCaptureMode(CaptureMode.VIDEO_ONLY)
        )
    }

    @Test
    fun commandsFor_videoToPhoto_restoresImageOnly() {
        val state = allSelectable(ShootingMode.VIDEO, captureMode = CaptureMode.VIDEO_ONLY)
        assertThat(state.commandsFor(ShootingMode.PHOTO)).containsExactly(
            CarouselCommand.SetCaptureMode(CaptureMode.IMAGE_ONLY)
        )
    }

    @Test
    fun commandsFor_videoToNight_forcesImageOnlyBeforeExtension() {
        val state = allSelectable(ShootingMode.VIDEO, captureMode = CaptureMode.VIDEO_ONLY)
        assertThat(state.commandsFor(ShootingMode.NIGHT)).containsExactly(
            CarouselCommand.SetCaptureMode(CaptureMode.IMAGE_ONLY),
            CarouselCommand.SetExtensionMode(CameraExtensionMode.NIGHT)
        ).inOrder()
    }

    @Test
    fun commandsFor_portraitToVideo_clearsExtensionThenSwitches() {
        val state = allSelectable(
            ShootingMode.PORTRAIT,
            captureMode = CaptureMode.IMAGE_ONLY,
            extension = CameraExtensionMode.BOKEH
        )
        assertThat(state.commandsFor(ShootingMode.VIDEO)).containsExactly(
            CarouselCommand.SetExtensionMode(CameraExtensionMode.NONE),
            CarouselCommand.SetCaptureMode(CaptureMode.VIDEO_ONLY)
        ).inOrder()
    }

    @Test
    fun commandsFor_nightToPortrait_swapsExtensionOnly() {
        val state = allSelectable(
            ShootingMode.NIGHT,
            captureMode = CaptureMode.IMAGE_ONLY,
            extension = CameraExtensionMode.NIGHT
        )
        assertThat(state.commandsFor(ShootingMode.PORTRAIT)).containsExactly(
            CarouselCommand.SetExtensionMode(CameraExtensionMode.BOKEH)
        )
    }

    @Test
    fun commandsFor_proToggling() {
        val toPro = allSelectable(ShootingMode.PHOTO, captureMode = CaptureMode.STANDARD)
        assertThat(toPro.commandsFor(ShootingMode.PRO)).containsExactly(
            CarouselCommand.SetProMode(true)
        )

        val fromPro =
            allSelectable(ShootingMode.PRO, captureMode = CaptureMode.STANDARD, pro = true)
        assertThat(fromPro.commandsFor(ShootingMode.PHOTO)).containsExactly(
            CarouselCommand.SetProMode(false)
        )
        assertThat(fromPro.commandsFor(ShootingMode.NIGHT)).containsExactly(
            CarouselCommand.SetProMode(false),
            CarouselCommand.SetExtensionMode(CameraExtensionMode.NIGHT)
        ).inOrder()
    }

    @Test
    fun extensionMode_mapping() {
        assertThat(ShootingMode.PORTRAIT.extensionMode).isEqualTo(CameraExtensionMode.BOKEH)
        assertThat(ShootingMode.NIGHT.extensionMode).isEqualTo(CameraExtensionMode.NIGHT)
        assertThat(ShootingMode.PHOTO.extensionMode).isEqualTo(CameraExtensionMode.NONE)
        assertThat(ShootingMode.VIDEO.extensionMode).isEqualTo(CameraExtensionMode.NONE)
        assertThat(ShootingMode.PRO.extensionMode).isEqualTo(CameraExtensionMode.NONE)
    }
}
