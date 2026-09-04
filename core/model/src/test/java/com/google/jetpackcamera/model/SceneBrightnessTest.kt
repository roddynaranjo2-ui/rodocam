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

class SceneBrightnessTest {

    @Test
    fun ev100_sunnySixteen() {
        // Sunny 16 rule: f/16, 1/100 s, ISO 100 -> EV100 ~ 14.6.
        val ev = SceneBrightness.ev100(
            iso = 100,
            exposureTimeNanos = 10_000_000L,
            apertureFNumber = 16f
        )
        assertThat(ev).isNotNull()
        assertThat(ev!!).isWithin(0.05f).of(14.64f)
    }

    @Test
    fun ev100_compensatesForIso() {
        val base = SceneBrightness.ev100(100, 10_000_000L, 2f)!!
        val fourTimesIso = SceneBrightness.ev100(400, 10_000_000L, 2f)!!
        // 4x ISO for the same shutter/aperture means the scene is 2 stops darker.
        assertThat(fourTimesIso).isWithin(1e-4f).of(base - 2f)
    }

    @Test
    fun ev100_compensatesForShutter() {
        val fast = SceneBrightness.ev100(100, 1_000_000L, 2f)!!
        val slow = SceneBrightness.ev100(100, 8_000_000L, 2f)!!
        assertThat(fast - slow).isWithin(1e-4f).of(3f)
    }

    @Test
    fun ev100_usesDefaultApertureWhenUnknown() {
        val withDefault = SceneBrightness.ev100(100, 10_000_000L, null)
        val explicit = SceneBrightness.ev100(
            100,
            10_000_000L,
            SceneBrightness.DEFAULT_APERTURE_F_NUMBER
        )
        assertThat(withDefault).isEqualTo(explicit)
        // Non-positive aperture also falls back to the default.
        assertThat(SceneBrightness.ev100(100, 10_000_000L, 0f)).isEqualTo(explicit)
    }

    @Test
    fun ev100_returnsNullOnMissingInputs() {
        assertThat(SceneBrightness.ev100(null, 10_000_000L)).isNull()
        assertThat(SceneBrightness.ev100(100, null)).isNull()
        assertThat(SceneBrightness.ev100(0, 10_000_000L)).isNull()
        assertThat(SceneBrightness.ev100(100, 0L)).isNull()
        assertThat(SceneBrightness.ev100(ExposureInfo.UNKNOWN)).isNull()
    }

    @Test
    fun ev100_fromExposureInfoUsesAperture() {
        val info = ExposureInfo(iso = 200, exposureTimeNanos = 20_000_000L, apertureFNumber = 1.8f)
        assertThat(SceneBrightness.ev100(info))
            .isEqualTo(SceneBrightness.ev100(200, 20_000_000L, 1.8f))
    }

    @Test
    fun approximateLux_doublesPerStop() {
        assertThat(SceneBrightness.approximateLux(0f)).isWithin(1e-4f).of(2.5f)
        assertThat(SceneBrightness.approximateLux(10f)).isWithin(0.1f).of(2560f)
    }

    @Test
    fun lowLightDetector_hasHysteresis() {
        val detector = LowLightDetector(enterEv100 = 4f, exitEv100 = 5.5f)
        assertThat(detector.isLowLight).isFalse()
        // Bright -> stays off.
        assertThat(detector.update(10f)).isFalse()
        // Between thresholds while off -> still off.
        assertThat(detector.update(5f)).isFalse()
        // Below enter -> on.
        assertThat(detector.update(3.9f)).isTrue()
        // Between thresholds while on -> stays on.
        assertThat(detector.update(5f)).isTrue()
        // Above exit -> off.
        assertThat(detector.update(5.6f)).isFalse()
    }

    @Test
    fun lowLightDetector_ignoresUnknownSamples() {
        val detector = LowLightDetector()
        detector.update(1f)
        assertThat(detector.isLowLight).isTrue()
        assertThat(detector.update(null)).isTrue()
        assertThat(detector.update(Float.NaN)).isTrue()
        assertThat(detector.lastEv100).isEqualTo(1f)
    }

    @Test
    fun lowLightDetector_fromExposureInfoAndReset() {
        val detector = LowLightDetector()
        // Dim interior: ISO 3200, 1/30 s, f/1.8 -> EV100 ~ 1.6.
        val dim = ExposureInfo(iso = 3200, exposureTimeNanos = 33_333_333L, apertureFNumber = 1.8f)
        assertThat(detector.update(dim)).isTrue()
        // Daylight: ISO 50, 1/2000 s.
        val bright = ExposureInfo(iso = 50, exposureTimeNanos = 500_000L, apertureFNumber = 1.8f)
        assertThat(detector.update(bright)).isFalse()
        detector.update(dim)
        detector.reset()
        assertThat(detector.isLowLight).isFalse()
        assertThat(detector.lastEv100).isNull()
    }

    @Test(expected = IllegalArgumentException::class)
    fun lowLightDetector_rejectsInvertedThresholds() {
        LowLightDetector(enterEv100 = 6f, exitEv100 = 4f)
    }
}
