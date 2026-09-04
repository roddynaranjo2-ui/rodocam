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

import com.google.jetpackcamera.model.CaptureTimer
import com.google.jetpackcamera.model.TimerCountdown

/**
 * UI state for the self-timer: the selected duration (Off / 3 s / 10 s) shown in quick settings
 * and the countdown overlay drawn over the viewfinder while a delayed capture is pending.
 */
sealed interface CaptureTimerUiState {
    /**
     * The timer cannot be changed right now (for example while a recording is in progress).
     */
    data object Unavailable : CaptureTimerUiState

    /**
     * The timer selector is available.
     *
     * @param selectedTimer The persisted self-timer duration.
     * @param availableTimers Options in display order.
     * @param countdown The in-flight countdown, or null when no delayed capture is pending.
     */
    data class Available(
        val selectedTimer: CaptureTimer,
        val availableTimers: List<CaptureTimer> = CaptureTimer.ORDERED,
        val countdown: TimerCountdown? = null
    ) : CaptureTimerUiState {
        /** True while a countdown is running and the overlay should be shown. */
        val isCountingDown: Boolean
            get() = countdown != null
    }

    companion object
}

/** The active countdown regardless of the availability state, or null when none is running. */
val CaptureTimerUiState.activeCountdown: TimerCountdown?
    get() = (this as? CaptureTimerUiState.Available)?.countdown
