/*
 * Copyright (C) 2024 The Android Open Source Project
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
import org.junit.Test

class TopShotTrackerTest {

    private val ms = 1_000_000L

    private fun tracker(vararg pairs: Pair<Long, Float>): TopShotTracker =
        pairs.fold(TopShotTracker.EMPTY) { acc, (t, s) -> acc.add(SharpnessSample(t * ms, s)) }

    @Test
    fun evaluate_withTooFewSamples_isUnknown() {
        val t = tracker(0L to 0.5f, 33L to 0.5f)
        assertThat(t.evaluate(33 * ms).verdict).isEqualTo(TopShotVerdict.UNKNOWN)
        assertThat(t.isLatestSharp).isFalse()
    }

    @Test
    fun add_ignoresOutOfOrderAndDuplicateTimestamps() {
        val t = tracker(0L to 0.1f, 33L to 0.2f, 33L to 0.9f, 10L to 0.9f)
        assertThat(t.samples).hasSize(2)
        assertThat(t.latestSharpness).isEqualTo(0.2f)
    }

    @Test
    fun add_prunesSamplesOlderThanWindow() {
        val t = tracker(0L to 0.1f, 500L to 0.2f, 1_000L to 0.3f, 1_600L to 0.4f)
        // 0 ms is older than 1600 - 1500 = 100 ms and must be dropped.
        assertThat(t.samples.map { it.timestampNanos / ms })
            .containsExactly(500L, 1_000L, 1_600L)
    }

    @Test
    fun add_frameStatsWithoutTimestamp_isIgnored() {
        val t = TopShotTracker.EMPTY.add(FrameStats(sharpness = 0.5f))
        assertThat(t.samples).isEmpty()
        val t2 = t.add(FrameStats(sharpness = 0.5f, timestampNanos = 10 * ms))
        assertThat(t2.samples).hasSize(1)
    }

    @Test
    fun evaluate_capturedFrameIsBest_isSharp() {
        val t = tracker(0L to 0.3f, 33L to 0.4f, 66L to 0.6f, 99L to 0.61f, 132L to 0.5f)
        val r = t.evaluate(99 * ms)
        assertThat(r.verdict).isEqualTo(TopShotVerdict.SHARP)
        assertThat(r.capturedSharpness).isEqualTo(0.61f)
        assertThat(r.bestSharpness).isEqualTo(0.61f)
        assertThat(r.bestOffsetMillis).isEqualTo(0L)
    }

    @Test
    fun evaluate_capturedFrameMuchBlurrierThanEarlierFrame_isBlurryWithNegativeOffset() {
        val t = tracker(0L to 0.8f, 33L to 0.7f, 66L to 0.3f, 99L to 0.2f, 132L to 0.25f)
        val r = t.evaluate(99 * ms)
        assertThat(r.verdict).isEqualTo(TopShotVerdict.BLURRY)
        assertThat(r.bestOffsetMillis).isEqualTo(-99L)
        assertThat(r.bestSharpness).isEqualTo(0.8f)
    }

    @Test
    fun evaluate_bestFrameWithinTolerance_isStillSharp() {
        // Best frame is 20 ms away (< 40 ms tolerance): treat as the same instant.
        val t = tracker(0L to 0.2f, 40L to 0.2f, 80L to 0.2f, 100L to 0.9f, 120L to 0.2f)
        assertThat(t.evaluate(120 * ms).verdict).isEqualTo(TopShotVerdict.SHARP)
    }

    @Test
    fun evaluate_picksNearestFrameToShutter() {
        val t = tracker(0L to 0.9f, 33L to 0.9f, 66L to 0.9f, 99L to 0.1f, 132L to 0.9f)
        // Shutter at 105 ms -> nearest is 99 ms (blurry).
        assertThat(t.evaluate(105 * ms).verdict).isEqualTo(TopShotVerdict.BLURRY)
        // Shutter at 120 ms -> nearest is 132 ms (sharp).
        assertThat(t.evaluate(120 * ms).verdict).isEqualTo(TopShotVerdict.SHARP)
    }

    @Test
    fun isLatestSharp_reflectsRatioAgainstBest() {
        val sharp = tracker(0L to 0.5f, 33L to 0.5f, 66L to 0.5f, 99L to 0.45f)
        assertThat(sharp.isLatestSharp).isTrue()
        val blurry = tracker(0L to 0.5f, 33L to 0.5f, 66L to 0.5f, 99L to 0.1f)
        assertThat(blurry.isLatestSharp).isFalse()
    }

    @Test
    fun isLatestSharp_flatSceneWithZeroSharpness_isTrue() {
        val t = tracker(0L to 0f, 33L to 0f, 66L to 0f, 99L to 0f)
        assertThat(t.isLatestSharp).isTrue()
    }

    @Test
    fun rowSharpness_flatRowIsZero_edgeRowIsPositive() {
        val flat = ByteArray(64) { 100 }
        assertThat(FrameStats.rowSharpness(flat)).isEqualTo(0f)
        val edge = ByteArray(64) { if (it < 32) 0 else 255.toByte() }
        assertThat(FrameStats.rowSharpness(edge)).isGreaterThan(0f)
        val blurredEdge = ByteArray(64) { (it * 4).coerceAtMost(255).toByte() }
        assertThat(FrameStats.rowSharpness(edge))
            .isGreaterThan(FrameStats.rowSharpness(blurredEdge))
    }

    @Test
    fun rowSharpness_tooShortRow_isZero() {
        assertThat(FrameStats.rowSharpness(ByteArray(2) { 50 })).isEqualTo(0f)
        assertThat(FrameStats.rowSharpness(ByteArray(10), length = 2)).isEqualTo(0f)
    }

    @Test
    fun constructor_rejectsInvalidConfiguration() {
        assertThrows { TopShotTracker(windowNanos = 0) }
        assertThrows { TopShotTracker(blurRatio = 1.5f) }
        assertThrows { TopShotTracker(minSamples = 0) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
