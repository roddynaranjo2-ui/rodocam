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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.model.CaptureTimer
import com.google.jetpackcamera.model.CoachInputs
import com.google.jetpackcamera.model.HorizonLevel
import com.google.jetpackcamera.model.PendingCaptureAction
import com.google.jetpackcamera.model.SharpnessSample
import com.google.jetpackcamera.model.ThermalStatus
import com.google.jetpackcamera.model.TimerCountdown
import com.google.jetpackcamera.model.TopShotTracker
import com.google.jetpackcamera.ui.uistate.capture.CaptureTimerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Screenshot-style component tests (Robolectric + Compose test rule) for the viewfinder overlays
 * added in Fase 3: self-timer countdown, Camera Coach chip, Top Shot badge, focus peaking badge
 * and the stateless level indicator. They verify visibility rules, semantics and callbacks
 * without a device.
 */
@RunWith(RobolectricTestRunner::class)
class ViewfinderOverlayComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---- TimerCountdownOverlay -------------------------------------------------------------

    @Test
    fun timerCountdown_hiddenWithoutCountdown() {
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                TimerCountdownOverlay(
                    uiState = CaptureTimerUiState.Available(CaptureTimer.THREE_SECONDS),
                    onCancel = {}
                )
            }
        }
        composeTestRule.onNodeWithTag(TIMER_COUNTDOWN_OVERLAY).assertDoesNotExist()
    }

    @Test
    fun timerCountdown_showsRemainingSeconds_andCancelsOnTap() {
        var cancelled = false
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                TimerCountdownOverlay(
                    uiState = CaptureTimerUiState.Available(
                        selectedTimer = CaptureTimer.THREE_SECONDS,
                        countdown = TimerCountdown(
                            remainingSeconds = 2,
                            totalSeconds = 3,
                            pendingAction = PendingCaptureAction.IMAGE
                        )
                    ),
                    onCancel = { cancelled = true }
                )
            }
        }
        composeTestRule.onNodeWithTag(TIMER_COUNTDOWN_OVERLAY).assertIsDisplayed()
        composeTestRule.onNodeWithText("2", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(TIMER_COUNTDOWN_OVERLAY).performClick()
        assertThat(cancelled).isTrue()
    }

    // ---- CoachHintChip --------------------------------------------------------------------

    @Test
    fun coachHint_quietOnNeutralScene() {
        composeTestRule.setContent {
            CoachHintChip(inputs = CoachInputs(), nowMillis = { 0L })
        }
        composeTestRule.onNodeWithTag(VIEWFINDER_COACH_HINT).assertDoesNotExist()
    }

    @Test
    fun coachHint_appearsAfterShowDelay_forTiltedHorizon() {
        var now = 0L
        val tilted = CoachInputs(horizonLevel = HorizonLevel(rollDegrees = 8f, pitchDegrees = 0f))

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CoachHintChip(inputs = tilted, nowMillis = { now })
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        // Candidate seen at t=0 but not yet shown (debounce).
        composeTestRule.onNodeWithTag(VIEWFINDER_COACH_HINT).assertDoesNotExist()

        now = 1_000L
        composeTestRule.mainClock.advanceTimeBy(2_000L)
        composeTestRule.onNodeWithTag(VIEWFINDER_COACH_HINT).assertExists()
    }

    // ---- TopShotBadge ---------------------------------------------------------------------

    private fun trackerWith(vararg sharpness: Float): TopShotTracker {
        var tracker = TopShotTracker.EMPTY
        sharpness.forEachIndexed { i, s ->
            tracker = tracker.add(
                SharpnessSample(timestampNanos = (i + 1) * 100_000_000L, sharpness = s)
            )
        }
        return tracker
    }

    @Test
    fun topShotBadge_hiddenUntilEnoughSamples() {
        composeTestRule.setContent {
            TopShotBadge(tracker = trackerWith(0.2f, 0.2f))
        }
        composeTestRule.onNodeWithTag(VIEWFINDER_TOP_SHOT_BADGE).assertDoesNotExist()
    }

    @Test
    fun topShotBadge_showsSharpWhenLatestMatchesBest() {
        composeTestRule.setContent {
            TopShotBadge(tracker = trackerWith(0.10f, 0.12f, 0.11f, 0.12f))
        }
        composeTestRule.onNodeWithTag(VIEWFINDER_TOP_SHOT_BADGE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sharp", useUnmergedTree = true).assertExists()
    }

    @Test
    fun topShotBadge_showsSoftWhenLatestDropsBelowRatio() {
        composeTestRule.setContent {
            TopShotBadge(tracker = trackerWith(0.30f, 0.32f, 0.31f, 0.05f))
        }
        composeTestRule.onNodeWithTag(VIEWFINDER_TOP_SHOT_BADGE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Soft", useUnmergedTree = true).assertExists()
    }

    // ---- FocusPeakingBadge / HorizonLevelIndicator -----------------------------------------

    @Test
    fun focusPeakingBadge_isDisplayed() {
        composeTestRule.setContent { FocusPeakingBadge() }
        composeTestRule.onNodeWithTag(VIEWFINDER_FOCUS_PEAKING_BADGE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Peaking", useUnmergedTree = true).assertExists()
    }

    // ---- ThermalWarningChip ----------------------------------------------------------------

    @Test
    fun thermalWarning_moderate_saysWarm() {
        composeTestRule.setContent { ThermalWarningChip(ThermalStatus.MODERATE) }
        composeTestRule.onNodeWithTag(VIEWFINDER_THERMAL_WARNING).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Device warm", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun thermalWarning_severe_saysHotAndReduced() {
        composeTestRule.setContent { ThermalWarningChip(ThermalStatus.SEVERE) }
        composeTestRule
            .onNodeWithText("Device hot", substring = true, useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onNodeWithText("quality reduced", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun horizonLevel_hidesWhenPitchIsUnusable() {
        var level by mutableStateOf(HorizonLevel(rollDegrees = 0f, pitchDegrees = 0f))
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize()) {
                HorizonLevelIndicator(level = level, isHapticsEnabled = false)
            }
        }
        composeTestRule.onNodeWithTag(VIEWFINDER_LEVEL_INDICATOR).assertExists()

        level = HorizonLevel(rollDegrees = 0f, pitchDegrees = 85f)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(VIEWFINDER_LEVEL_INDICATOR).assertDoesNotExist()
    }
}
