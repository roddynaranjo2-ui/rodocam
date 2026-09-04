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
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CameraCoachTest {

    /** Histogram with [total] samples spread over bins [from, to]. */
    private fun histogram(from: Int, to: Int, total: Int = 10_000): LumaHistogram {
        val counts = IntArray(LumaHistogram.DEFAULT_BIN_COUNT)
        val span = to - from + 1
        for (i in from..to) counts[i] = total / span
        return LumaHistogram(counts)
    }

    private fun stats(
        histogram: LumaHistogram = histogram(4, 60),
        clipped: Float = 0f,
        crushed: Float = 0f
    ) = FrameStats(
        histogram = histogram,
        clippedHighlightsFraction = clipped,
        crushedShadowsFraction = crushed,
        width = 320,
        height = 240
    )

    @Test
    fun evaluate_ordinaryScene_isQuiet() {
        val inputs = CoachInputs(frameStats = stats(histogram = histogram(4, 60)))

        assertThat(CameraCoach.evaluate(inputs)).isEmpty()
        assertThat(CameraCoach.topHint(inputs)).isNull()
    }

    @Test
    fun evaluate_unknownStats_ignoresHistogramRules() {
        val inputs = CoachInputs(frameStats = FrameStats.UNKNOWN)

        assertThat(CameraCoach.evaluate(inputs)).isEmpty()
    }

    @Test
    fun evaluate_tooFewSamples_ignoresHistogramRules() {
        val tiny = histogram(60, 63, total = 400)
        val inputs = CoachInputs(frameStats = stats(histogram = tiny, clipped = 0.9f))

        assertThat(CameraCoach.evaluate(inputs)).isEmpty()
    }

    @Test
    fun evaluate_clippedHighlights_reportsOverexposed() {
        val inputs = CoachInputs(frameStats = stats(histogram = histogram(40, 63), clipped = 0.3f))

        assertThat(CameraCoach.topHint(inputs)).isEqualTo(CoachHint.OVEREXPOSED)
    }

    @Test
    fun evaluate_crushedShadowsWithDarkMean_reportsUnderexposed() {
        val inputs = CoachInputs(frameStats = stats(histogram = histogram(0, 6), crushed = 0.6f))

        assertThat(CameraCoach.topHint(inputs)).isEqualTo(CoachHint.UNDEREXPOSED)
    }

    @Test
    fun evaluate_crushedShadowsWithBrightMean_isNotUnderexposed() {
        // High-contrast scene: lots of black but mean luma high -> not an exposure problem.
        val counts = IntArray(LumaHistogram.DEFAULT_BIN_COUNT)
        counts[0] = 5_000
        counts[60] = 5_000
        val inputs = CoachInputs(
            frameStats = stats(histogram = LumaHistogram(counts), crushed = 0.5f)
        )

        assertThat(CameraCoach.evaluate(inputs)).doesNotContain(CoachHint.UNDEREXPOSED)
    }

    @Test
    fun evaluate_lowLightScene_reportsLowLightUnlessUnderexposed() {
        val lowLight = CoachInputs(frameStats = stats(), isLowLightScene = true)
        assertThat(CameraCoach.topHint(lowLight)).isEqualTo(CoachHint.LOW_LIGHT)

        val both = CoachInputs(
            frameStats = stats(histogram = histogram(0, 6), crushed = 0.6f),
            isLowLightScene = true
        )
        val hints = CameraCoach.evaluate(both)
        assertThat(hints).contains(CoachHint.UNDEREXPOSED)
        assertThat(hints).doesNotContain(CoachHint.LOW_LIGHT)
    }

    @Test
    fun evaluate_tiltedHorizon_reportsTiltOnlyWhenUsable() {
        val tilted = CoachInputs(
            frameStats = stats(),
            horizonLevel = HorizonLevel(rollDegrees = 4f, pitchDegrees = 10f)
        )
        assertThat(CameraCoach.topHint(tilted)).isEqualTo(CoachHint.TILTED_HORIZON)

        val slightlyOff = tilted.copy(horizonLevel = HorizonLevel(1.5f, 10f))
        assertThat(CameraCoach.evaluate(slightlyOff)).isEmpty()

        val pointingDown = tilted.copy(horizonLevel = HorizonLevel(4f, 85f))
        assertThat(CameraCoach.evaluate(pointingDown)).isEmpty()
    }

    @Test
    fun evaluate_highDigitalZoom_reportsHighZoom() {
        val digital = CoachInputs(frameStats = stats(), zoomRatio = 8f, maxOpticalZoom = 1f)
        assertThat(CameraCoach.topHint(digital)).isEqualTo(CoachHint.HIGH_ZOOM)

        val tele = CoachInputs(frameStats = stats(), zoomRatio = 8f, maxOpticalZoom = 3f)
        assertThat(CameraCoach.evaluate(tele)).isEmpty()
    }

    @Test
    fun evaluate_flatHistogram_reportsLowContrast() {
        val flat = CoachInputs(frameStats = stats(histogram = histogram(22, 42)))

        assertThat(CameraCoach.topHint(flat)).isEqualTo(CoachHint.LOW_CONTRAST)
        assertThat(CameraCoach.midToneFraction(histogram(22, 42))).isWithin(1e-3f).of(1f)
    }

    @Test
    fun evaluate_multipleIssues_sortedByPriority() {
        val inputs = CoachInputs(
            frameStats = stats(histogram = histogram(40, 63), clipped = 0.3f),
            horizonLevel = HorizonLevel(5f, 0f),
            zoomRatio = 10f
        )

        assertThat(CameraCoach.evaluate(inputs))
            .containsExactly(CoachHint.OVEREXPOSED, CoachHint.TILTED_HORIZON, CoachHint.HIGH_ZOOM)
            .inOrder()
    }

    @Test
    fun smoother_showsHintOnlyAfterItIsStable() {
        var s = CoachHintSmoother(showAfterMillis = 600, hideAfterMillis = 1_500)

        s = s.update(CoachHint.OVEREXPOSED, 0)
        assertThat(s.visibleHint).isNull()
        s = s.update(CoachHint.OVEREXPOSED, 300)
        assertThat(s.visibleHint).isNull()
        s = s.update(CoachHint.OVEREXPOSED, 600)
        assertThat(s.visibleHint).isEqualTo(CoachHint.OVEREXPOSED)
    }

    @Test
    fun smoother_flickerDoesNotShow() {
        var s = CoachHintSmoother(showAfterMillis = 600, hideAfterMillis = 1_500)

        s = s.update(CoachHint.TILTED_HORIZON, 0)
        s = s.update(null, 300)
        s = s.update(CoachHint.TILTED_HORIZON, 400)
        s = s.update(CoachHint.TILTED_HORIZON, 900)
        assertThat(s.visibleHint).isNull()
        s = s.update(CoachHint.TILTED_HORIZON, 1_000)
        assertThat(s.visibleHint).isEqualTo(CoachHint.TILTED_HORIZON)
    }

    @Test
    fun smoother_hidesAfterHintGoesAway() {
        var s = CoachHintSmoother(showAfterMillis = 600, hideAfterMillis = 1_500)
        s = s.update(CoachHint.LOW_LIGHT, 0).update(CoachHint.LOW_LIGHT, 600)
        assertThat(s.visibleHint).isEqualTo(CoachHint.LOW_LIGHT)

        s = s.update(null, 1_000)
        assertThat(s.visibleHint).isEqualTo(CoachHint.LOW_LIGHT)
        s = s.update(null, 2_000)
        assertThat(s.visibleHint).isEqualTo(CoachHint.LOW_LIGHT)
        s = s.update(null, 2_100)
        assertThat(s.visibleHint).isNull()
    }

    @Test
    fun smoother_switchesToNewStableHint() {
        var s = CoachHintSmoother(showAfterMillis = 600, hideAfterMillis = 1_500)
        s = s.update(CoachHint.LOW_CONTRAST, 0).update(CoachHint.LOW_CONTRAST, 600)
        assertThat(s.visibleHint).isEqualTo(CoachHint.LOW_CONTRAST)

        s = s.update(CoachHint.OVEREXPOSED, 700)
        assertThat(s.visibleHint).isEqualTo(CoachHint.LOW_CONTRAST)
        s = s.update(CoachHint.OVEREXPOSED, 1_300)
        assertThat(s.visibleHint).isEqualTo(CoachHint.OVEREXPOSED)
    }
}
