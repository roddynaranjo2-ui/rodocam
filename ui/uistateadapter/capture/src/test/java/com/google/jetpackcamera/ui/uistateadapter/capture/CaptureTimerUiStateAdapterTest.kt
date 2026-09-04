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
import com.google.jetpackcamera.core.camera.AudioStreamState
import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.core.camera.VideoRecordingState
import com.google.jetpackcamera.model.CaptureTimer
import com.google.jetpackcamera.model.PendingCaptureAction
import com.google.jetpackcamera.model.TimerCountdown
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.ui.uistate.capture.CaptureTimerUiState
import com.google.jetpackcamera.ui.uistate.capture.activeCountdown
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
internal class CaptureTimerUiStateAdapterTest {

    private val idleCameraState = CameraState()

    private val recordingCameraState = CameraState(
        videoRecordingState = VideoRecordingState.Active.Recording(
            maxDurationMillis = 0L,
            audioStreamState = AudioStreamState.Active(0.0),
            elapsedTimeNanos = 0L
        )
    )

    @Test
    fun from_idle_returnsAvailableWithSelectedTimerAndAllOptions() {
        val settings = CameraAppSettings(captureTimer = CaptureTimer.TEN_SECONDS)

        val state = CaptureTimerUiState.from(settings, idleCameraState, countdown = null)

        assertThat(state).isInstanceOf(CaptureTimerUiState.Available::class.java)
        val available = state as CaptureTimerUiState.Available
        assertThat(available.selectedTimer).isEqualTo(CaptureTimer.TEN_SECONDS)
        assertThat(available.availableTimers).isEqualTo(CaptureTimer.ORDERED)
        assertThat(available.countdown).isNull()
        assertThat(available.isCountingDown).isFalse()
        assertThat(state.activeCountdown).isNull()
    }

    @Test
    fun from_defaultSettings_selectsOff() {
        val state = CaptureTimerUiState.from(CameraAppSettings(), idleCameraState, countdown = null)

        assertThat((state as CaptureTimerUiState.Available).selectedTimer)
            .isEqualTo(CaptureTimer.OFF)
    }

    @Test
    fun from_recordingWithoutCountdown_returnsUnavailable() {
        val settings = CameraAppSettings(captureTimer = CaptureTimer.THREE_SECONDS)

        val state = CaptureTimerUiState.from(settings, recordingCameraState, countdown = null)

        assertThat(state).isEqualTo(CaptureTimerUiState.Unavailable)
        assertThat(state.activeCountdown).isNull()
    }

    @Test
    fun from_pausedRecordingWithoutCountdown_returnsUnavailable() {
        val paused = CameraState(
            videoRecordingState = VideoRecordingState.Active.Paused(
                maxDurationMillis = 0L,
                audioStreamState = AudioStreamState.Active(0.0),
                elapsedTimeNanos = 0L
            )
        )

        val state = CaptureTimerUiState.from(CameraAppSettings(), paused, countdown = null)

        assertThat(state).isEqualTo(CaptureTimerUiState.Unavailable)
    }

    @Test
    fun from_activeCountdown_surfacesCountdownEvenWhileRecording() {
        val countdown = TimerCountdown.start(CaptureTimer.THREE_SECONDS, PendingCaptureAction.VIDEO)
        val settings = CameraAppSettings(captureTimer = CaptureTimer.THREE_SECONDS)

        val state = CaptureTimerUiState.from(settings, recordingCameraState, countdown)

        assertThat(state).isInstanceOf(CaptureTimerUiState.Available::class.java)
        val available = state as CaptureTimerUiState.Available
        assertThat(available.isCountingDown).isTrue()
        assertThat(available.countdown).isEqualTo(countdown)
        assertThat(state.activeCountdown).isEqualTo(countdown)
    }

    @Test
    fun activeCountdown_onUnavailable_isNull() {
        assertThat(CaptureTimerUiState.Unavailable.activeCountdown).isNull()
    }
}
