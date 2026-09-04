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
 * Luma histogram of a preview frame.
 *
 * Bins cover the 8-bit luma range uniformly: bin `i` holds the number of sampled pixels whose
 * luma `Y` satisfies `i * 256 / binCount <= Y < (i + 1) * 256 / binCount`.
 *
 * Instances are immutable; the backing array is defensively copied on construction.
 *
 * @property counts Per-bin pixel counts, size [binCount].
 * @property sampleCount Total number of sampled pixels (sum of [counts]).
 */
class LumaHistogram(counts: IntArray) {
    val counts: IntArray = counts.copyOf()
    val binCount: Int get() = counts.size
    val sampleCount: Int = counts.sum()

    init {
        require(counts.isNotEmpty()) { "Histogram needs at least one bin" }
        require(counts.all { it >= 0 }) { "Histogram counts must be non-negative" }
    }

    val isEmpty: Boolean get() = sampleCount == 0

    /** Bin counts normalised to `0..1` relative to the fullest bin (all zeros if empty). */
    fun normalized(): FloatArray {
        val max = counts.maxOrNull() ?: 0
        if (max <= 0) return FloatArray(binCount)
        return FloatArray(binCount) { counts[it].toFloat() / max }
    }

    /** Mean luma in `0..1` (bin centres weighted by count); `0f` for an empty histogram. */
    val meanLuma: Float
        get() {
            if (isEmpty) return 0f
            var acc = 0.0
            for (i in counts.indices) {
                acc += counts[i].toDouble() * ((i + 0.5) / binCount)
            }
            return (acc / sampleCount).toFloat()
        }

    /**
     * Fraction (`0..1`) of pixels whose luma is at or above `thresholdPercent` of full scale.
     * Bins that straddle the threshold are counted proportionally.
     */
    fun fractionAbove(thresholdPercent: Int): Float =
        fractionAboveLuma(thresholdPercent.coerceIn(0, 100) / 100f)

    /** Fraction (`0..1`) of pixels whose luma is strictly below `thresholdPercent` of full scale. */
    fun fractionBelow(thresholdPercent: Int): Float =
        1f - fractionAboveLuma(thresholdPercent.coerceIn(0, 100) / 100f)

    private fun fractionAboveLuma(threshold: Float): Float {
        if (isEmpty) return 0f
        val binWidth = 1f / binCount
        var acc = 0.0
        for (i in counts.indices) {
            val lo = i * binWidth
            val hi = lo + binWidth
            val covered = when {
                threshold <= lo -> 1f
                threshold >= hi -> 0f
                else -> (hi - threshold) / binWidth
            }
            acc += counts[i] * covered.toDouble()
        }
        return (acc / sampleCount).toFloat().coerceIn(0f, 1f)
    }

    override fun equals(other: Any?): Boolean =
        other is LumaHistogram && counts.contentEquals(other.counts)

    override fun hashCode(): Int = counts.contentHashCode()

    override fun toString(): String = "LumaHistogram(bins=$binCount, samples=$sampleCount)"

    companion object {
        /** Bin count used by the viewfinder histogram (Pixel/Lightroom style, 64 bars). */
        const val DEFAULT_BIN_COUNT = 64

        /** Convenience: an empty 64-bin histogram. */
        val EMPTY = LumaHistogram(IntArray(DEFAULT_BIN_COUNT))

        /**
         * Builds a histogram from raw 8-bit luma samples.
         *
         * @param luma Luma bytes (Y plane), treated as unsigned.
         * @param stride Sample every `stride`-th byte (>= 1) to keep analysis cheap.
         */
        fun fromLuma(
            luma: ByteArray,
            binCount: Int = DEFAULT_BIN_COUNT,
            stride: Int = 1
        ): LumaHistogram {
            require(binCount in 1..256) { "binCount must be in 1..256" }
            require(stride >= 1) { "stride must be >= 1" }
            val counts = IntArray(binCount)
            val shift = binShift(binCount)
            var i = 0
            while (i < luma.size) {
                counts[(luma[i].toInt() and 0xFF) shr shift]++
                i += stride
            }
            return LumaHistogram(counts)
        }

        /**
         * Number of right-shifts that maps an 8-bit value to a bin index. Only power-of-two bin
         * counts are exact; other counts round down to the nearest power of two.
         */
        fun binShift(binCount: Int): Int {
            var bits = 0
            var n = binCount
            while (n > 1) {
                n = n shr 1
                bits++
            }
            return (8 - bits).coerceIn(0, 8)
        }
    }
}

/**
 * Lightweight per-frame statistics published by the analysis pipeline for the viewfinder
 * overlays (histogram, zebras, low-light detection, coaching hints).
 *
 * @property histogram Luma histogram of the analysed frame.
 * @property clippedHighlightsFraction Fraction of pixels above the zebra threshold in effect.
 * @property crushedShadowsFraction Fraction of pixels below ~2% luma.
 * @property width Analysed frame width in pixels.
 * @property height Analysed frame height in pixels.
 * @property timestampNanos Sensor timestamp of the frame (monotonic), `0L` if unknown.
 * @property sharpness Mean absolute horizontal Laplacian of the sampled luma, normalised to
 *   `0..1` (0 = flat/blurred, higher = more high-frequency detail). Only comparable between
 *   frames of the same scene; consumed by Top Shot. `0f` when not computed.
 */
data class FrameStats(
    val histogram: LumaHistogram = LumaHistogram.EMPTY,
    val clippedHighlightsFraction: Float = 0f,
    val crushedShadowsFraction: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val timestampNanos: Long = 0L,
    val sharpness: Float = 0f
) {
    val meanLuma: Float get() = histogram.meanLuma

    companion object {
        val UNKNOWN = FrameStats()

        /** Shadows are considered crushed below this luma percentage. */
        const val CRUSHED_SHADOWS_PERCENT = 2

        /**
         * Sharpness metric over a row of 8-bit luma samples: mean `|y[i-1] - 2*y[i] + y[i+1]|`
         * divided by 255. Cheap, allocation-free and monotonic with focus quality for a fixed
         * scene. Returns `0f` for rows with fewer than three samples.
         */
        fun rowSharpness(row: ByteArray, length: Int = row.size): Float {
            if (length < 3) return 0f
            var acc = 0L
            var prev = row[0].toInt() and 0xFF
            var cur = row[1].toInt() and 0xFF
            var i = 2
            while (i < length) {
                val next = row[i].toInt() and 0xFF
                val lap = prev - 2 * cur + next
                acc += if (lap < 0) -lap else lap
                prev = cur
                cur = next
                i++
            }
            return (acc.toDouble() / ((length - 2) * 255.0)).toFloat().coerceIn(0f, 1f)
        }
    }
}
