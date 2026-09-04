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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CaptureTimerTest {

    @Test
    fun off_isNotEnabledAndHasZeroDuration() {
        assertThat(CaptureTimer.OFF.isEnabled).isFalse()
        assertThat(CaptureTimer.OFF.durationSeconds).isEqualTo(0)
        assertThat(CaptureTimer.OFF.durationMillis).isEqualTo(0L)
    }

    @Test
    fun timedValues_areEnabledWithMatchingMillis() {
        assertThat(CaptureTimer.THREE_SECONDS.isEnabled).isTrue()
        assertThat(CaptureTimer.THREE_SECONDS.durationMillis).isEqualTo(3_000L)
        assertThat(CaptureTimer.TEN_SECONDS.isEnabled).isTrue()
        assertThat(CaptureTimer.TEN_SECONDS.durationMillis).isEqualTo(10_000L)
    }

    @Test
    fun ordered_listsEveryValueFromOffToLongest() {
        assertThat(CaptureTimer.ORDERED).containsExactlyElementsIn(CaptureTimer.entries).inOrder()
        assertThat(CaptureTimer.ORDERED.first()).isEqualTo(CaptureTimer.OFF)
        assertThat(CaptureTimer.ORDERED.last()).isEqualTo(CaptureTimer.TEN_SECONDS)
    }

    @Test
    fun start_withOffTimer_returnsNull() {
        assertThat(TimerCountdown.start(CaptureTimer.OFF, PendingCaptureAction.IMAGE)).isNull()
    }

    @Test
    fun start_withEnabledTimer_beginsAtFullDuration() {
        val countdown = TimerCountdown.start(CaptureTimer.THREE_SECONDS, PendingCaptureAction.VIDEO)

        assertThat(countdown).isNotNull()
        assertThat(countdown!!.remainingSeconds).isEqualTo(3)
        assertThat(countdown.totalSeconds).isEqualTo(3)
        assertThat(countdown.pendingAction).isEqualTo(PendingCaptureAction.VIDEO)
        assertThat(countdown.progress).isEqualTo(0f)
    }

    @Test
    fun tick_countsDownAndFinishesWithNull() {
        var countdown: TimerCountdown? =
            TimerCountdown.start(CaptureTimer.THREE_SECONDS, PendingCaptureAction.IMAGE)

        countdown = countdown!!.tick()
        assertThat(countdown!!.remainingSeconds).isEqualTo(2)
        assertThat(countdown.totalSeconds).isEqualTo(3)
        assertThat(countdown.pendingAction).isEqualTo(PendingCaptureAction.IMAGE)

        countdown = countdown.tick()
        assertThat(countdown!!.remainingSeconds).isEqualTo(1)

        assertThat(countdown.tick()).isNull()
    }

    @Test
    fun progress_growsAsSecondsElapse() {
        val start =
            TimerCountdown(remainingSeconds = 10, totalSeconds = 10, PendingCaptureAction.IMAGE)
        val half = start.copy(remainingSeconds = 5)
        val last = start.copy(remainingSeconds = 1)

        assertThat(start.progress).isEqualTo(0f)
        assertThat(half.progress).isEqualTo(0.5f)
        assertThat(last.progress).isWithin(1e-6f).of(0.9f)
    }

    @Test
    fun constructor_rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException::class.java) {
            TimerCountdown(remainingSeconds = 0, totalSeconds = 3, PendingCaptureAction.IMAGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimerCountdown(remainingSeconds = 4, totalSeconds = 3, PendingCaptureAction.IMAGE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimerCountdown(remainingSeconds = 1, totalSeconds = 0, PendingCaptureAction.IMAGE)
        }
    }
}
