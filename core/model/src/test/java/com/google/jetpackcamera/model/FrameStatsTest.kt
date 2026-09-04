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
import org.junit.Test

class FrameStatsTest {

    @Test
    fun fromLuma_binsUniformly() {
        // 0..255 -> four samples per bin when using 64 bins.
        val luma = ByteArray(256) { it.toByte() }
        val histogram = LumaHistogram.fromLuma(luma, binCount = 64)
        assertThat(histogram.binCount).isEqualTo(64)
        assertThat(histogram.sampleCount).isEqualTo(256)
        assertThat(histogram.counts.all { it == 4 }).isTrue()
    }

    @Test
    fun fromLuma_treatsBytesAsUnsigned() {
        val luma = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00)
        val histogram = LumaHistogram.fromLuma(luma, binCount = 64)
        assertThat(histogram.counts[63]).isEqualTo(2)
        assertThat(histogram.counts[0]).isEqualTo(1)
    }

    @Test
    fun fromLuma_honoursStride() {
        val luma = ByteArray(100) { 0x80.toByte() }
        val histogram = LumaHistogram.fromLuma(luma, binCount = 64, stride = 10)
        assertThat(histogram.sampleCount).isEqualTo(10)
        assertThat(histogram.counts[32]).isEqualTo(10)
    }

    @Test
    fun binShift_matchesPowerOfTwoBinCounts() {
        assertThat(LumaHistogram.binShift(256)).isEqualTo(0)
        assertThat(LumaHistogram.binShift(64)).isEqualTo(2)
        assertThat(LumaHistogram.binShift(16)).isEqualTo(4)
        assertThat(LumaHistogram.binShift(1)).isEqualTo(8)
    }

    @Test
    fun normalized_scalesToFullestBin() {
        val histogram = LumaHistogram(intArrayOf(0, 5, 10, 2))
        val normalized = histogram.normalized()
        assertThat(normalized.toList()).containsExactly(0f, 0.5f, 1f, 0.2f).inOrder()
        assertThat(LumaHistogram(IntArray(4)).normalized().toList())
            .containsExactly(0f, 0f, 0f, 0f)
    }

    @Test
    fun meanLuma_usesBinCentres() {
        assertThat(LumaHistogram(intArrayOf(0, 0, 0, 10)).meanLuma).isWithin(1e-5f).of(0.875f)
        assertThat(LumaHistogram(intArrayOf(1, 1, 1, 1)).meanLuma).isWithin(1e-5f).of(0.5f)
        assertThat(LumaHistogram.EMPTY.meanLuma).isEqualTo(0f)
    }

    @Test
    fun fractionAbove_countsWholeAndPartialBins() {
        val histogram = LumaHistogram(intArrayOf(1, 1, 1, 1))
        assertThat(histogram.fractionAbove(75)).isWithin(1e-5f).of(0.25f)
        assertThat(histogram.fractionAbove(50)).isWithin(1e-5f).of(0.5f)
        // Threshold inside the top bin (0.875) -> half of that bin.
        assertThat(histogram.fractionAbove(87)).isWithin(0.02f).of(0.125f)
        assertThat(histogram.fractionAbove(100)).isEqualTo(0f)
        assertThat(histogram.fractionAbove(0)).isEqualTo(1f)
    }

    @Test
    fun fractionBelow_isComplementOfAbove() {
        val histogram = LumaHistogram(intArrayOf(3, 1, 0, 4))
        assertThat(histogram.fractionBelow(50) + histogram.fractionAbove(50))
            .isWithin(1e-5f).of(1f)
    }

    @Test
    fun emptyHistogram_isSafe() {
        assertThat(LumaHistogram.EMPTY.isEmpty).isTrue()
        assertThat(LumaHistogram.EMPTY.fractionAbove(95)).isEqualTo(0f)
        assertThat(LumaHistogram.EMPTY.fractionBelow(2)).isEqualTo(1f)
        assertThat(FrameStats.UNKNOWN.meanLuma).isEqualTo(0f)
    }

    @Test
    fun histogram_isImmutableAndValueEqual() {
        val backing = intArrayOf(1, 2, 3)
        val histogram = LumaHistogram(backing)
        backing[0] = 99
        assertThat(histogram.counts[0]).isEqualTo(1)
        assertThat(histogram).isEqualTo(LumaHistogram(intArrayOf(1, 2, 3)))
        assertThat(histogram.hashCode()).isEqualTo(LumaHistogram(intArrayOf(1, 2, 3)).hashCode())
    }

    @Test(expected = IllegalArgumentException::class)
    fun histogram_rejectsNegativeCounts() {
        LumaHistogram(intArrayOf(1, -1))
    }
}
