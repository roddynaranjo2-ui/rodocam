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
package com.google.jetpackcamera.ui.components.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ManualControlsFormatTest {

    @Test
    fun formatKelvin_appendsUnit() {
        assertThat(formatKelvin(5500)).isEqualTo("5500 K")
    }

    @Test
    fun snapKelvin_roundsToStep() {
        assertThat(snapKelvin(5549f, 100)).isEqualTo(5500)
        assertThat(snapKelvin(5550f, 100)).isEqualTo(5600)
        assertThat(snapKelvin(2000f, 100)).isEqualTo(2000)
    }

    @Test
    fun snapShadows_deadZoneAroundZero_andFiveHundredthsSteps() {
        assertThat(snapShadows(0.02f)).isEqualTo(0f)
        assertThat(snapShadows(-0.07f)).isEqualTo(0f)
        assertThat(snapShadows(0.1f)).isEqualTo(0.1f)
        assertThat(snapShadows(0.126f)).isEqualTo(0.15f)
        assertThat(snapShadows(-0.98f)).isEqualTo(-1f)
        assertThat(snapShadows(1.4f)).isEqualTo(1f)
    }

    @Test
    fun formatShadows_signedPercent() {
        assertThat(formatShadows(null)).isEqualTo("0")
        assertThat(formatShadows(0f)).isEqualTo("0")
        assertThat(formatShadows(0.25f)).isEqualTo("+25")
        assertThat(formatShadows(-0.5f)).isEqualTo("-50")
    }

    @Test
    fun formatEv_signedOneDecimal() {
        assertThat(formatEv(0f)).isEqualTo("0")
        assertThat(formatEv(0.04f)).isEqualTo("0")
        assertThat(formatEv(0.33f)).isEqualTo("+0.3")
        assertThat(formatEv(-1.67f)).isEqualTo("-1.7")
        assertThat(formatEv(2f)).isEqualTo("+2.0")
    }

    @Test
    fun formatFocus_infinityMacroAndDistances() {
        val maxDiopters = 10f
        assertThat(formatFocus(0f, maxDiopters)).isEqualTo("∞")
        assertThat(formatFocus(0.04f, maxDiopters)).isEqualTo("∞")
        assertThat(formatFocus(9.9f, maxDiopters)).isEqualTo("Macro")
        assertThat(formatFocus(0.5f, maxDiopters)).isEqualTo("2.0 m")
        assertThat(formatFocus(1f, maxDiopters)).isEqualTo("1.0 m")
        assertThat(formatFocus(4f, maxDiopters)).isEqualTo("25 cm")
    }

    @Test
    fun snapIso_snapsToThirdStops() {
        assertThat(snapIso(100)).isEqualTo(100)
        assertThat(snapIso(110)).isEqualTo(100)
        assertThat(snapIso(140)).isEqualTo(125)
        assertThat(snapIso(3000)).isEqualTo(3200)
        assertThat(snapIso(20000)).isEqualTo(12800)
    }

    @Test
    fun snapShutter_subSecond_snapsToStandardSpeeds() {
        assertThat(snapShutter(1_000_000_000L / 125)).isEqualTo(1_000_000_000L / 125)
        assertThat(snapShutter(8_300_000L)).isEqualTo(1_000_000_000L / 125)
        assertThat(snapShutter(125_000L)).isEqualTo(1_000_000_000L / 8000)
        assertThat(snapShutter(680_000_000L)).isEqualTo(700_000_000L)
    }

    @Test
    fun snapShutter_wholeSeconds_snapsToHalfSeconds() {
        assertThat(snapShutter(1_000_000_000L)).isEqualTo(1_000_000_000L)
        assertThat(snapShutter(1_300_000_000L)).isEqualTo(1_500_000_000L)
        assertThat(snapShutter(2_200_000_000L)).isEqualTo(2_000_000_000L)
    }
}
