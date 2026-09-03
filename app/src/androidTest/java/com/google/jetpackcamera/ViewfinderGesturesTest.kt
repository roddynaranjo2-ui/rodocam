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
package com.google.jetpackcamera

import android.view.ViewConfiguration
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.google.jetpackcamera.ui.components.capture.CAPTURE_BUTTON
import com.google.jetpackcamera.ui.components.capture.FOCUS_LOCK_BADGE_TAG
import com.google.jetpackcamera.ui.components.capture.FOCUS_METERING_INDICATOR_TAG
import com.google.jetpackcamera.ui.components.capture.PREVIEW_DISPLAY
import com.google.jetpackcamera.ui.components.capture.ZOOM_BUTTON_2_TAG
import com.google.jetpackcamera.ui.debug.BTN_DEBUG_HIDE_COMPONENTS_TAG
import com.google.jetpackcamera.ui.debug.ZOOM_RATIO_TAG
import com.google.jetpackcamera.utils.FOCUS_METERING_INDICATOR_TIMEOUT_MILLIS
import com.google.jetpackcamera.utils.TEST_REQUIRED_PERMISSIONS
import com.google.jetpackcamera.utils.debugExtra
import com.google.jetpackcamera.utils.runMainActivityScenarioTest
import com.google.jetpackcamera.utils.wait
import com.google.jetpackcamera.utils.waitForCaptureButton
import com.google.jetpackcamera.utils.waitForNodeWithTag
import com.google.jetpackcamera.utils.waitForNodeWithTagToDisappear
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pixel-style viewfinder gestures:
 *  - double-tap toggles the primary zoom between 1x and 2x,
 *  - long-press locks focus and exposure (lock badge shown on the indicator),
 *  - a subsequent single tap releases the lock.
 */
@RunWith(AndroidJUnit4::class)
class ViewfinderGesturesTest {
    @get:Rule
    val permissionsRule: GrantPermissionRule =
        GrantPermissionRule.grant(*(TEST_REQUIRED_PERMISSIONS).toTypedArray())

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Test
    fun doubleTap_togglesZoomBetween1xAnd2x() = runMainActivityScenarioTest(debugExtra) {
        composeTestRule.waitForCaptureButton()

        // Devices whose primary lens cannot reach 2x expose no "2x" zoom button; skip there.
        val has2xButton =
            composeTestRule.onAllNodesWithTag(ZOOM_BUTTON_2_TAG).fetchSemanticsNodes().isNotEmpty()
        assumeTrue("Primary lens does not reach 2x; double-tap zoom is disabled.", has2xButton)

        composeTestRule.onNodeWithTag(ZOOM_RATIO_TAG).assertTextEquals("1.00x")

        composeTestRule.onNodeWithTag(PREVIEW_DISPLAY).performTouchInput { doubleClick() }
        composeTestRule.waitUntilZoomRatioIs("2.00x")

        composeTestRule.wait(timeoutMillis = 2 * doubleTapTimeoutMillis())

        composeTestRule.onNodeWithTag(PREVIEW_DISPLAY).performTouchInput { doubleClick() }
        composeTestRule.waitUntilZoomRatioIs("1.00x")
    }

    @Test
    fun longPress_locksFocusAndExposure_untilNextTap() =
        runMainActivityScenarioTest(debugExtra) {
            composeTestRule.waitForCaptureButton()

            // Hide all components so we don't accidentally press on them.
            composeTestRule.onNodeWithTag(BTN_DEBUG_HIDE_COMPONENTS_TAG).performClick()
            composeTestRule.waitForNodeWithTagToDisappear(CAPTURE_BUTTON)

            composeTestRule.onNodeWithTag(PREVIEW_DISPLAY).performTouchInput {
                longClick(position = percentOffset(0.5f, 0.5f))
            }

            composeTestRule.waitForNodeWithTag(
                FOCUS_METERING_INDICATOR_TAG,
                FOCUS_METERING_INDICATOR_TIMEOUT_MILLIS
            )
            composeTestRule.waitForNodeWithTag(
                FOCUS_LOCK_BADGE_TAG,
                FOCUS_METERING_INDICATOR_TIMEOUT_MILLIS
            )

            composeTestRule.wait(timeoutMillis = 2 * doubleTapTimeoutMillis())

            // A single tap starts a regular (auto-cancelling) focus run and releases the lock.
            composeTestRule.onNodeWithTag(PREVIEW_DISPLAY).performTouchInput {
                click(position = percentOffset(0.3f, 0.3f))
            }
            composeTestRule.waitForNodeWithTagToDisappear(
                FOCUS_LOCK_BADGE_TAG,
                FOCUS_METERING_INDICATOR_TIMEOUT_MILLIS
            )
        }

    private fun ComposeTestRule.waitUntilZoomRatioIs(expected: String) {
        waitUntil(ZOOM_ANIMATION_TIMEOUT_MILLIS) {
            runCatching { onNodeWithTag(ZOOM_RATIO_TAG).assertTextEquals(expected) }.isSuccess
        }
    }

    private fun doubleTapTimeoutMillis(): Long = ViewConfiguration.getDoubleTapTimeout().toLong()

    private companion object {
        const val ZOOM_ANIMATION_TIMEOUT_MILLIS = 5_000L
    }
}
