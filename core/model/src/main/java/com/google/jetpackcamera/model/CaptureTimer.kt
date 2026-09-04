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
package com.google.jetpackcamera.model

/**
 * Self-timer applied before a photo is taken or a video recording starts, mirroring the
 * Off / 3 s / 10 s options of Pixel Camera.
 *
 * WARNING: The string representation of this enum is serialized and persisted in Preferences
 * DataStore. Renaming constants will break compatibility with existing saved settings.
 *
 * @property durationSeconds Countdown length in whole seconds; 0 for [OFF].
 */
enum class CaptureTimer(val durationSeconds: Int) {
    OFF(0),
    THREE_SECONDS(3),
    TEN_SECONDS(10);

    /** True when a countdown should run before capturing. */
    val isEnabled: Boolean
        get() = durationSeconds > 0

    /** Countdown length in milliseconds. */
    val durationMillis: Long
        get() = durationSeconds * MILLIS_PER_SECOND

    companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** Options in display order. */
        val ORDERED: List<CaptureTimer> = listOf(OFF, THREE_SECONDS, TEN_SECONDS)
    }
}

/**
 * Snapshot of an in-flight self-timer countdown.
 *
 * @property remainingSeconds Whole seconds left before the capture fires (>= 1 while running).
 * @property totalSeconds Length of the countdown when it started.
 * @property pendingAction What will happen when the countdown reaches zero.
 */
data class TimerCountdown(
    val remainingSeconds: Int,
    val totalSeconds: Int,
    val pendingAction: PendingCaptureAction
) {
    init {
        require(totalSeconds > 0) { "totalSeconds must be positive, was $totalSeconds" }
        require(remainingSeconds in 1..totalSeconds) {
            "remainingSeconds ($remainingSeconds) must be within 1..$totalSeconds"
        }
    }

    /** Fraction of the countdown already elapsed, 0f at the start and approaching 1f. */
    val progress: Float
        get() = (totalSeconds - remainingSeconds).toFloat() / totalSeconds

    companion object {
        /**
         * Creates the initial countdown state for [timer]; null when the timer is
         * [CaptureTimer.OFF].
         */
        fun start(timer: CaptureTimer, pendingAction: PendingCaptureAction): TimerCountdown? =
            if (timer.isEnabled) {
                TimerCountdown(
                    remainingSeconds = timer.durationSeconds,
                    totalSeconds = timer.durationSeconds,
                    pendingAction = pendingAction
                )
            } else {
                null
            }
    }

    /** Returns the state one second later, or null once the countdown has finished. */
    fun tick(): TimerCountdown? =
        if (remainingSeconds > 1) copy(remainingSeconds = remainingSeconds - 1) else null
}

/** The capture that a [TimerCountdown] will trigger when it completes. */
enum class PendingCaptureAction {
    IMAGE,
    VIDEO
}
