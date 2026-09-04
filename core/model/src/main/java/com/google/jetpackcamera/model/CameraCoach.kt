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

import kotlin.math.abs

/**
 * A single, actionable framing/exposure hint shown over the viewfinder, in the spirit of
 * Pixel 10's "Camera Coach" but computed on-device from cheap statistics only (no ML).
 *
 * Hints are ordered by priority: when several apply at once only the most important one is
 * surfaced so the user is never flooded with advice.
 */
enum class CoachHint {
    /** Large fraction of the frame is clipped white: lower EV or move away from the light. */
    OVEREXPOSED,
    /** Most of the frame sits in crushed shadows: raise EV, add light or use Night. */
    UNDEREXPOSED,
    /** AE reports a dark scene: suggest Night mode / keep the phone steady. */
    LOW_LIGHT,
    /** Horizon visibly tilted: straighten the phone. */
    TILTED_HORIZON,
    /** Very high digital zoom: image will be soft, step closer instead. */
    HIGH_ZOOM,
    /** Histogram squashed in the mid-tones: flat, low-contrast scene. */
    LOW_CONTRAST;

    /** Higher values win when several hints apply at once. */
    val priority: Int
        get() = when (this) {
            OVEREXPOSED -> 6
            UNDEREXPOSED -> 5
            LOW_LIGHT -> 4
            TILTED_HORIZON -> 3
            HIGH_ZOOM -> 2
            LOW_CONTRAST -> 1
        }
}

/**
 * Inputs for one coaching evaluation. All fields are optional so callers can feed whatever
 * statistics happen to be available.
 *
 * @property frameStats Latest luma statistics from the analysis stream (or [FrameStats.UNKNOWN]).
 * @property horizonLevel Current horizon level from the gravity sensor, if the level assist is on.
 * @property isLowLightScene AE-derived dark-scene flag (see [SceneBrightness]).
 * @property zoomRatio Current zoom ratio of the primary lens.
 * @property maxOpticalZoom Largest zoom ratio backed by a physical lens; digital zoom beyond it
 *   is where softness kicks in.
 */
data class CoachInputs(
    val frameStats: FrameStats = FrameStats.UNKNOWN,
    val horizonLevel: HorizonLevel? = null,
    val isLowLightScene: Boolean = false,
    val zoomRatio: Float = 1f,
    val maxOpticalZoom: Float = 1f
)

/**
 * Pure rule engine that turns [CoachInputs] into an ordered list of [CoachHint]s.
 *
 * Thresholds are deliberately conservative so the coach stays quiet on ordinary scenes; they
 * are exposed as constants for tests and tuning.
 */
object CameraCoach {
    /** Fraction of clipped highlights from which the scene counts as overexposed. */
    const val OVEREXPOSED_FRACTION = 0.18f

    /** Fraction of crushed shadows from which the scene counts as underexposed. */
    const val UNDEREXPOSED_FRACTION = 0.45f

    /** Mean luma below which crushed shadows are treated as underexposure (not just contrast). */
    const val UNDEREXPOSED_MEAN_LUMA = 0.22f

    /** Roll beyond which the horizon is called out as tilted. */
    const val TILT_DEGREES = 2.5f

    /** Digital zoom factor beyond the last optical lens at which softness is flagged. */
    const val HIGH_DIGITAL_ZOOM_FACTOR = 4f

    /** Fraction of samples inside the central 40 % luma band that marks a flat scene. */
    const val LOW_CONTRAST_MID_FRACTION = 0.92f

    /** Minimum samples for the histogram-based rules to be trusted. */
    const val MIN_HISTOGRAM_SAMPLES = 1_000

    /**
     * Evaluates every rule and returns the applicable hints sorted by descending priority.
     * Returns an empty list when the scene looks fine.
     */
    fun evaluate(inputs: CoachInputs): List<CoachHint> {
        val hints = ArrayList<CoachHint>(3)
        val stats = inputs.frameStats
        val histogramUsable = stats.histogram.sampleCount >= MIN_HISTOGRAM_SAMPLES

        if (histogramUsable) {
            if (stats.clippedHighlightsFraction >= OVEREXPOSED_FRACTION) {
                hints += CoachHint.OVEREXPOSED
            } else if (
                stats.crushedShadowsFraction >= UNDEREXPOSED_FRACTION &&
                stats.meanLuma <= UNDEREXPOSED_MEAN_LUMA
            ) {
                hints += CoachHint.UNDEREXPOSED
            }
        }
        if (inputs.isLowLightScene && CoachHint.UNDEREXPOSED !in hints) {
            hints += CoachHint.LOW_LIGHT
        }
        inputs.horizonLevel?.let { level ->
            if (level.isUsable && abs(level.rollDegrees) >= TILT_DEGREES) {
                hints += CoachHint.TILTED_HORIZON
            }
        }
        val optical = inputs.maxOpticalZoom.coerceAtLeast(1f)
        if (inputs.zoomRatio >= optical * HIGH_DIGITAL_ZOOM_FACTOR) {
            hints += CoachHint.HIGH_ZOOM
        }
        val exposureFlagged = hints.any {
            it == CoachHint.OVEREXPOSED || it == CoachHint.UNDEREXPOSED
        }
        if (histogramUsable && !exposureFlagged &&
            midToneFraction(stats.histogram) >= LOW_CONTRAST_MID_FRACTION
        ) {
            hints += CoachHint.LOW_CONTRAST
        }
        return hints.sortedByDescending { it.priority }
    }

    /** Convenience: the single most important hint, or null when there is nothing to say. */
    fun topHint(inputs: CoachInputs): CoachHint? = evaluate(inputs).firstOrNull()

    /** Fraction of samples whose luma lies in the central band `0.30..0.70`. */
    internal fun midToneFraction(histogram: LumaHistogram): Float {
        if (histogram.isEmpty) return 0f
        val bins = histogram.binCount
        val lo = (bins * 0.30f).toInt()
        val hi = (bins * 0.70f).toInt().coerceAtMost(bins - 1)
        var acc = 0L
        for (i in lo..hi) acc += histogram.counts[i]
        return acc.toFloat() / histogram.sampleCount
    }
}

/**
 * Temporal smoothing for coach hints so a single noisy frame does not flash advice: a hint must
 * persist for [showAfterMillis] before it is shown and must be absent for [hideAfterMillis]
 * before it disappears. Pure and immutable; feed it [update] results back in.
 *
 * @property visibleHint Hint currently shown to the user (null when quiet).
 */
data class CoachHintSmoother(
    val visibleHint: CoachHint? = null,
    private val candidate: CoachHint? = null,
    private val candidateSinceMillis: Long = 0L,
    private val lastSeenVisibleMillis: Long = 0L,
    val showAfterMillis: Long = DEFAULT_SHOW_AFTER_MILLIS,
    val hideAfterMillis: Long = DEFAULT_HIDE_AFTER_MILLIS
) {
    /** Returns the smoother state after observing [rawHint] at [nowMillis]. */
    fun update(rawHint: CoachHint?, nowMillis: Long): CoachHintSmoother {
        var next = this
        if (rawHint != null && rawHint == visibleHint) {
            return copy(lastSeenVisibleMillis = nowMillis, candidate = null)
        }
        // Track how long the (different) raw hint has been stable.
        next = if (rawHint != null && rawHint == candidate) {
            next
        } else {
            next.copy(candidate = rawHint, candidateSinceMillis = nowMillis)
        }
        val candidateStable = rawHint != null &&
            nowMillis - next.candidateSinceMillis >= showAfterMillis
        if (candidateStable) {
            return next.copy(
                visibleHint = rawHint,
                lastSeenVisibleMillis = nowMillis,
                candidate = null
            )
        }
        if (visibleHint != null && nowMillis - lastSeenVisibleMillis >= hideAfterMillis) {
            return next.copy(visibleHint = null)
        }
        return next
    }

    companion object {
        const val DEFAULT_SHOW_AFTER_MILLIS = 600L
        const val DEFAULT_HIDE_AFTER_MILLIS = 1_500L
        val IDLE = CoachHintSmoother()
    }
}
