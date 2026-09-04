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

import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.core.camera.VideoRecordingState
import com.google.jetpackcamera.model.CaptureTimer
import com.google.jetpackcamera.model.TimerCountdown
import com.google.jetpackcamera.settings.model.CameraAppSettings
import com.google.jetpackcamera.ui.uistate.capture.CaptureTimerUiState

/**
 * Builds the [CaptureTimerUiState] for the current settings and camera state.
 *
 * The selector is [CaptureTimerUiState.Unavailable] while a recording is active: changing the
 * timer mid-recording has no effect and Pixel Camera hides the option in that state too. A
 * countdown that is already running is always surfaced so the overlay can be drawn and cancelled.
 *
 * @param cameraAppSettings Source of the persisted [CameraAppSettings.captureTimer].
 * @param cameraState Used to detect an active recording.
 * @param countdown The in-flight countdown tracked by the UI layer, or null when idle.
 */
fun CaptureTimerUiState.Companion.from(
    cameraAppSettings: CameraAppSettings,
    cameraState: CameraState,
    countdown: TimerCountdown?
): CaptureTimerUiState {
    val isRecording = cameraState.videoRecordingState is VideoRecordingState.Active
    return if (isRecording && countdown == null) {
        CaptureTimerUiState.Unavailable
    } else {
        CaptureTimerUiState.Available(
            selectedTimer = cameraAppSettings.captureTimer,
            availableTimers = CaptureTimer.ORDERED,
            countdown = countdown
        )
    }
}
