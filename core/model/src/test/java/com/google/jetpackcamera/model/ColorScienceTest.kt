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

class ColorScienceTest {

    @Test
    fun kelvinToRgb_neutralAtDaylight_andWarmAtLowKelvin() {
        val daylight = kelvinToRgb(6500)
        // Planckian 6500 K is close to D65: all channels near unity.
        assertThat(daylight[0]).isWithin(0.08f).of(1f)
        assertThat(daylight[1]).isWithin(0.08f).of(1f)
        assertThat(daylight[2]).isWithin(0.08f).of(1f)

        val candle = kelvinToRgb(2000)
        assertThat(candle[0]).isGreaterThan(candle[1])
        assertThat(candle[2]).isLessThan(0.1f)

        val sky = kelvinToRgb(10000)
        assertThat(sky[2]).isGreaterThan(sky[1])
        assertThat(sky[0]).isLessThan(sky[2])
    }

    @Test
    fun kelvinToRggbGains_greenIsUnity_andGainsAreMonotonic() {
        var previousRed = 0f
        var previousBlue = Float.MAX_VALUE
        for (kelvin in 2000..10000 step 500) {
            val gains = kelvinToRggbGains(kelvin)
            assertThat(gains).hasLength(4)
            assertThat(gains[1]).isEqualTo(1f)
            assertThat(gains[2]).isEqualTo(1f)
            // Warm light is red-rich: cut red, boost blue. Cooler light => the opposite.
            assertThat(gains[0]).isAtLeast(previousRed)
            assertThat(gains[3]).isAtMost(previousBlue)
            gains.forEach { assertThat(it).isIn(com.google.common.collect.Range.closed(0.25f, 4f)) }
            previousRed = gains[0]
            previousBlue = gains[3]
        }
    }

    @Test
    fun kelvinToRggbGains_neutralNearDaylight_andExtremesAreCorrectDirection() {
        val gains = kelvinToRggbGains(6500)
        assertThat(gains[0]).isWithin(0.1f).of(1f)
        assertThat(gains[3]).isWithin(0.1f).of(1f)
        val warm = kelvinToRggbGains(2700)
        assertThat(warm[0]).isLessThan(1f)
        assertThat(warm[3]).isGreaterThan(1f)
        val cool = kelvinToRggbGains(9000)
        assertThat(cool[0]).isGreaterThan(1f)
        assertThat(cool[3]).isLessThan(1f)
    }

    @Test
    fun approximateKelvin_isOrderedWarmToCool() {
        assertThat(WhiteBalanceMode.INCANDESCENT.approximateKelvin())
            .isLessThan(WhiteBalanceMode.FLUORESCENT.approximateKelvin())
        assertThat(WhiteBalanceMode.FLUORESCENT.approximateKelvin())
            .isLessThan(WhiteBalanceMode.DAYLIGHT.approximateKelvin())
        assertThat(WhiteBalanceMode.DAYLIGHT.approximateKelvin())
            .isLessThan(WhiteBalanceMode.SHADE.approximateKelvin())
        assertThat(WhiteBalanceMode.AUTO.approximateKelvin())
            .isEqualTo(ManualControls.WHITE_BALANCE_KELVIN_NEUTRAL)
        WhiteBalanceMode.entries.forEach {
            assertThat(it.approximateKelvin()).isIn(
                com.google.common.collect.Range.closed(
                    ManualControls.WHITE_BALANCE_KELVIN_RANGE.first,
                    ManualControls.WHITE_BALANCE_KELVIN_RANGE.last
                )
            )
        }
    }

    @Test
    fun buildShadowsTonemapCurve_anchorsEndpoints_andIsMonotonic() {
        for (shadows in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val curve = buildShadowsTonemapCurve(shadows, 64)
            assertThat(curve).hasLength(128)
            assertThat(curve[0]).isEqualTo(0f)
            assertThat(curve[1]).isEqualTo(0f)
            assertThat(curve[126]).isEqualTo(1f)
            assertThat(curve[127]).isEqualTo(1f)
            for (i in 1 until 64) {
                assertThat(curve[i * 2]).isGreaterThan(curve[(i - 1) * 2])
                assertThat(curve[i * 2 + 1]).isAtLeast(curve[(i - 1) * 2 + 1])
            }
        }
    }

    @Test
    fun buildShadowsTonemapCurve_positiveLiftsAndNegativeDeepens() {
        val neutral = buildShadowsTonemapCurve(0f, 33)
        val lifted = buildShadowsTonemapCurve(1f, 33)
        val deepened = buildShadowsTonemapCurve(-1f, 33)
        // Compare the output at a dark input (~0.125).
        val idx = 4 * 2 + 1
        assertThat(lifted[idx]).isGreaterThan(neutral[idx])
        assertThat(deepened[idx]).isLessThan(neutral[idx])
        // Neutral reproduces sRGB-like gamma (1/2.2): 0.125^(1/2.2) ~= 0.389.
        assertThat(neutral[idx]).isWithin(0.02f).of(0.389f)
        // Fully deepened is linear.
        assertThat(deepened[idx]).isWithin(0.001f).of(0.125f)
    }

    @Test
    fun buildShadowsTonemapCurve_clampsInputs() {
        val tiny = buildShadowsTonemapCurve(5f, 1)
        assertThat(tiny).hasLength(4)
        assertThat(tiny[0]).isEqualTo(0f)
        assertThat(tiny[2]).isEqualTo(1f)
        assertThat(tiny[3]).isEqualTo(1f)
    }
}
