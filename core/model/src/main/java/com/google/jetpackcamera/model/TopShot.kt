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

import kotlin.math.abs

/** One sharpness sample from the analysis stream. */
data class SharpnessSample(val timestampNanos: Long, val sharpness: Float)

/** Verdict produced by [TopShotTracker] for the frame closest to the shutter press. */
enum class TopShotVerdict {
    /** Not enough history to say anything. */
    UNKNOWN,

    /** The captured frame is (close to) the sharpest in the recent window. */
    SHARP,

    /** A noticeably sharper frame existed shortly before/after the shutter (motion blur). */
    BLURRY
}

/**
 * Result of a Top Shot evaluation.
 *
 * @property verdict Sharpness verdict for the frame at the shutter timestamp.
 * @property capturedSharpness Sharpness of the frame nearest to the shutter.
 * @property bestSharpness Sharpness of the best frame in the window.
 * @property bestOffsetMillis Time offset (best - shutter) in milliseconds; negative means the
 *   sharper moment happened *before* the press.
 */
data class TopShotResult(
    val verdict: TopShotVerdict,
    val capturedSharpness: Float = 0f,
    val bestSharpness: Float = 0f,
    val bestOffsetMillis: Long = 0L
) {
    companion object {
        val UNKNOWN = TopShotResult(TopShotVerdict.UNKNOWN)
    }
}

/**
 * Lightweight "Top Shot": keeps a rolling window of per-frame sharpness values and, when the
 * shutter fires, tells whether the captured frame was sharp compared with its neighbours.
 *
 * Unlike Pixel's Top Shot it does not store frames (no memory/battery cost); it only informs the
 * user so they can retake, and feeds the Camera Coach. The tracker is an immutable value: every
 * [add] returns a new instance, making it trivial to hold in Compose/StateFlow.
 *
 * @property windowNanos How much history to keep (default 1.5 s).
 * @property blurRatio The captured frame is [TopShotVerdict.BLURRY] when its sharpness is below
 *   `bestSharpness * blurRatio`.
 * @property minSamples Minimum number of samples before a verdict other than UNKNOWN is given.
 */
data class TopShotTracker(
    val samples: List<SharpnessSample> = emptyList(),
    val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    val blurRatio: Float = DEFAULT_BLUR_RATIO,
    val minSamples: Int = DEFAULT_MIN_SAMPLES
) {
    init {
        require(windowNanos > 0) { "windowNanos must be > 0" }
        require(blurRatio in 0f..1f) { "blurRatio must be in 0..1" }
        require(minSamples >= 1) { "minSamples must be >= 1" }
    }

    /** Adds a sample (ignoring duplicates/out-of-order timestamps) and prunes old history. */
    fun add(sample: SharpnessSample): TopShotTracker {
        val last = samples.lastOrNull()
        if (last != null && sample.timestampNanos <= last.timestampNanos) return this
        val cutoff = sample.timestampNanos - windowNanos
        val kept = samples.dropWhile { it.timestampNanos < cutoff } + sample
        return copy(samples = kept)
    }

    /** Convenience for feeding [FrameStats] straight from the analyzer. */
    fun add(stats: FrameStats): TopShotTracker = if (stats.timestampNanos == 0L) {
        this
    } else {
        add(SharpnessSample(stats.timestampNanos, stats.sharpness))
    }

    /** Latest sharpness in the window, `0f` if empty. */
    val latestSharpness: Float get() = samples.lastOrNull()?.sharpness ?: 0f

    /** Sharpest sample of the window, `null` if empty. */
    val best: SharpnessSample? get() = samples.maxByOrNull { it.sharpness }

    /**
     * Evaluates the frame nearest to [shutterTimestampNanos] against the whole window.
     * Frames within [toleranceNanos] of the best one are still considered SHARP.
     */
    fun evaluate(
        shutterTimestampNanos: Long,
        toleranceNanos: Long = DEFAULT_TOLERANCE_NANOS
    ): TopShotResult {
        if (samples.size < minSamples) return TopShotResult.UNKNOWN
        val captured = samples.minByOrNull { abs(it.timestampNanos - shutterTimestampNanos) }
            ?: return TopShotResult.UNKNOWN
        val bestSample = best ?: return TopShotResult.UNKNOWN
        val offsetNanos = bestSample.timestampNanos - captured.timestampNanos
        val isBlurry = bestSample.sharpness > 0f &&
            captured.sharpness < bestSample.sharpness * blurRatio &&
            abs(offsetNanos) > toleranceNanos
        return TopShotResult(
            verdict = if (isBlurry) TopShotVerdict.BLURRY else TopShotVerdict.SHARP,
            capturedSharpness = captured.sharpness,
            bestSharpness = bestSample.sharpness,
            bestOffsetMillis = offsetNanos / NANOS_PER_MILLI
        )
    }

    /**
     * Live "is the current frame sharp enough?" signal for a viewfinder badge: true when the
     * latest frame is within [blurRatio] of the best sharpness seen in the window.
     */
    val isLatestSharp: Boolean
        get() {
            if (samples.size < minSamples) return false
            val bestValue = best?.sharpness ?: return false
            return bestValue <= 0f || latestSharpness >= bestValue * blurRatio
        }

    companion object {
        const val DEFAULT_WINDOW_NANOS = 1_500_000_000L
        const val DEFAULT_BLUR_RATIO = 0.7f
        const val DEFAULT_MIN_SAMPLES = 4
        const val DEFAULT_TOLERANCE_NANOS = 40_000_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        val EMPTY = TopShotTracker()
    }
}
