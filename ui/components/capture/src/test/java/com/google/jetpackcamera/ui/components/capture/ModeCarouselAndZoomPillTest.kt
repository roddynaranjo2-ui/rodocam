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
package com.google.jetpackcamera.ui.components.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.ui.uistate.DisableRationale
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeCarouselUiState
import com.google.jetpackcamera.ui.uistate.capture.ShootingMode
import com.google.jetpackcamera.ui.uistate.capture.ZoomControlUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric component tests for the Pixel-style [ModeCarousel] and [ZoomPill]. */
@RunWith(RobolectricTestRunner::class)
class ModeCarouselAndZoomPillTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private object Rationale : DisableRationale {
        override val reasonTextResId: Int = R.string.capture_mode_image_capture_content_description
    }

    private val carouselState = CaptureModeCarouselUiState.Available(
        selectedMode = ShootingMode.PHOTO,
        modes = listOf(
            SingleSelectableUiState.SelectableUi(ShootingMode.VIDEO),
            SingleSelectableUiState.SelectableUi(ShootingMode.PHOTO),
            SingleSelectableUiState.Disabled(ShootingMode.PORTRAIT, Rationale),
            SingleSelectableUiState.SelectableUi(ShootingMode.NIGHT),
            SingleSelectableUiState.SelectableUi(ShootingMode.PRO)
        )
    )

    // ---- ModeCarousel ---------------------------------------------------------------------

    @Test
    fun modeCarousel_rendersNothingWhenUnavailable() {
        composeTestRule.setContent {
            ModeCarousel(
                uiState = CaptureModeCarouselUiState.Unavailable,
                onSelectMode = {},
                onModeDisabled = {}
            )
        }
        composeTestRule.onNodeWithTag(MODE_CAROUSEL_TAG).assertDoesNotExist()
    }

    @Test
    fun modeCarousel_showsAllChips_withSelectionAndDisabledSemantics() {
        composeTestRule.setContent {
            ModeCarousel(uiState = carouselState, onSelectMode = {}, onModeDisabled = {})
        }
        composeTestRule.onNodeWithTag(MODE_CAROUSEL_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(modeChipTestTag(ShootingMode.PHOTO)).assertIsSelected()
        composeTestRule.onNodeWithTag(modeChipTestTag(ShootingMode.VIDEO)).assertIsNotSelected()
        composeTestRule.onNodeWithTag(modeChipTestTag(ShootingMode.PORTRAIT))
            .assertIsNotEnabled()
        composeTestRule.onNodeWithText("NIGHT", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("PRO", useUnmergedTree = true).assertExists()
    }

    @Test
    fun modeCarousel_tapSelectsMode_andIgnoresCurrent() {
        val selected = mutableListOf<ShootingMode>()
        composeTestRule.setContent {
            ModeCarousel(
                uiState = carouselState,
                onSelectMode = { selected += it },
                onModeDisabled = {}
            )
        }
        composeTestRule.onNodeWithTag(modeChipTestTag(ShootingMode.PHOTO)).performClick()
        composeTestRule.onNodeWithTag(modeChipTestTag(ShootingMode.NIGHT)).performClick()
        assertThat(selected).containsExactly(ShootingMode.NIGHT)
    }

    @Test
    fun modeCarousel_tapOnDisabledChip_reportsRationale() {
        var rationale: DisableRationale? = null
        composeTestRule.setContent {
            ModeCarousel(
                uiState = carouselState,
                onSelectMode = {},
                onModeDisabled = { rationale = it }
            )
        }
        composeTestRule.onNodeWithTag(modeChipTestTag(ShootingMode.PORTRAIT)).performClick()
        assertThat(rationale).isEqualTo(Rationale)
    }

    @Test
    fun modeCarousel_swipeMovesSelection_skippingDisabled() {
        val selected = mutableListOf<ShootingMode>()
        composeTestRule.setContent {
            ModeCarousel(
                uiState = carouselState,
                onSelectMode = { selected += it },
                onModeDisabled = {}
            )
        }
        // Swipe left = next mode on the right (Portrait is disabled, so Night).
        composeTestRule.onNodeWithTag(MODE_CAROUSEL_TAG).performTouchInput { swipeLeft() }
        // Swipe right = previous mode (Video).
        composeTestRule.onNodeWithTag(MODE_CAROUSEL_TAG).performTouchInput { swipeRight() }
        assertThat(selected).containsExactly(ShootingMode.NIGHT, ShootingMode.VIDEO).inOrder()
    }

    // ---- ZoomPill -------------------------------------------------------------------------

    private fun zoomState(ratio: Float) = ZoomControlUiState.Enabled(
        zoomLevels = listOf(0.5f, 1f, 2f, 5f),
        primaryLensFacing = LensFacing.BACK,
        initialZoomRatio = 1f,
        primaryZoomRatio = ratio
    )

    @Test
    fun zoomPill_hiddenWhenUnavailable() {
        composeTestRule.setContent {
            ZoomPill(zoomControlUiState = ZoomControlUiState.Unavailable, onChangeZoom = {})
        }
        composeTestRule.onNodeWithTag(ZOOM_PILL_TAG).assertDoesNotExist()
    }

    @Test
    fun zoomPill_showsLiveRatioOnSelectedChip_andTargetsOnOthers() {
        composeTestRule.setContent {
            ZoomPill(zoomControlUiState = zoomState(2.3f), onChangeZoom = {})
        }
        composeTestRule.onNodeWithTag(ZOOM_PILL_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("2.3x", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("0.5", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("5", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(getZoomButtonTestTag(2f)).assertIsSelected()
        composeTestRule.onNodeWithTag(getZoomButtonTestTag(1f)).assertIsNotSelected()
    }

    @Test
    fun zoomPill_tapRequestsZoomLevel() {
        val requested = mutableListOf<Float>()
        composeTestRule.setContent {
            ZoomPill(zoomControlUiState = zoomState(1f), onChangeZoom = { requested += it })
        }
        composeTestRule.onNodeWithTag(getZoomButtonTestTag(5f)).performClick()
        composeTestRule.onNodeWithTag(getZoomButtonTestTag(0.5f)).performClick()
        assertThat(requested).containsExactly(5f, 0.5f).inOrder()
    }
}
