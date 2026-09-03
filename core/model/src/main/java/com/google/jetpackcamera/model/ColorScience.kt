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

import kotlin.math.pow

/**
 * Pure-Kotlin colour helpers shared by the camera pipeline and the Pro UI.
 *
 * They intentionally avoid Android types so that they can be unit tested on the JVM; the
 * camera layer wraps the results into `RggbChannelVector` / `TonemapCurve`.
 */

/** Lower bound of the temperature range for which the locus approximation is defined. */
private const val KELVIN_MIN = 1667

/** Upper bound of the temperature range for which the locus approximation is defined. */
private const val KELVIN_MAX = 25000

/** Gains below this value would make a channel almost black; clamp to keep the image usable. */
private const val MIN_CHANNEL_GAIN = 0.25f

/** Gains above this value amplify noise excessively; clamp for stability. */
private const val MAX_CHANNEL_GAIN = 4f

/**
 * CIE 1931 chromaticity `(x, y)` of a Planckian (black-body) radiator at [kelvin], using the
 * cubic spline approximation by Kim et al. (2002), valid for 1667 K – 25000 K.
 */
private fun planckianChromaticity(kelvin: Int): Pair<Double, Double> {
    val t = kelvin.coerceIn(KELVIN_MIN, KELVIN_MAX).toDouble()
    val t2 = t * t
    val t3 = t2 * t
    val x = if (t <= 4000.0) {
        -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
    } else {
        -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
    }
    val x2 = x * x
    val x3 = x2 * x
    val y = when {
        t <= 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
        t <= 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
        else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
    }
    return x to y
}

/**
 * Converts a colour temperature to the *linear* sRGB colour of a black-body radiator with unit
 * luminance (`Y = 1`). Values are not clamped to `0..1`: red exceeds 1 for warm light and blue
 * exceeds 1 for cool light, which is exactly what white-balance gains must compensate.
 *
 * @return `[r, g, b]` linear, strictly positive.
 */
fun kelvinToRgb(kelvin: Int): FloatArray {
    val (x, y) = planckianChromaticity(kelvin)
    val bigX = x / y
    val bigZ = (1.0 - x - y) / y
    // XYZ (D65) -> linear sRGB.
    val r = 3.2406 * bigX - 1.5372 - 0.4986 * bigZ
    val g = -0.9689 * bigX + 1.8758 + 0.0415 * bigZ
    val b = 0.0557 * bigX - 0.2040 + 1.0570 * bigZ
    return floatArrayOf(
        r.coerceAtLeast(1e-3).toFloat(),
        g.coerceAtLeast(1e-3).toFloat(),
        b.coerceAtLeast(1e-3).toFloat()
    )
}

/**
 * Computes the per-channel gains that neutralise a scene illuminated by a light source of the
 * given colour temperature, in Camera2 `RggbChannelVector` order: `[R, G_even, G_odd, B]`.
 *
 * The gains are the inverse of the illuminant colour (normalised so that green is `1f`): a warm
 * (low kelvin) light is rich in red and poor in blue, so the red gain is cut and the blue gain is
 * boosted to make whites neutral, and vice versa for cool light. Gains are clamped to
 * `[MIN_CHANNEL_GAIN, MAX_CHANNEL_GAIN]` for HAL stability.
 *
 * The mapping is monotonic: red gain rises and blue gain falls as the temperature rises.
 */
fun kelvinToRggbGains(kelvin: Int): FloatArray {
    val (r, g, b) = kelvinToRgb(kelvin)
    val redGain = (g / r).coerceIn(MIN_CHANNEL_GAIN, MAX_CHANNEL_GAIN)
    val blueGain = (g / b).coerceIn(MIN_CHANNEL_GAIN, MAX_CHANNEL_GAIN)
    return floatArrayOf(redGain, 1f, 1f, blueGain)
}

/**
 * Maps a [WhiteBalanceMode] preset to an approximate colour temperature, used to pre-position the
 * kelvin slider when the user switches from a preset to manual kelvin.
 */
fun WhiteBalanceMode.approximateKelvin(): Int = when (this) {
    WhiteBalanceMode.AUTO -> ManualControls.WHITE_BALANCE_KELVIN_NEUTRAL
    WhiteBalanceMode.INCANDESCENT -> 2700
    WhiteBalanceMode.WARM_FLUORESCENT -> 3000
    WhiteBalanceMode.FLUORESCENT -> 4000
    WhiteBalanceMode.DAYLIGHT -> 5500
    WhiteBalanceMode.CLOUDY_DAYLIGHT -> 6500
    WhiteBalanceMode.TWILIGHT -> 7500
    WhiteBalanceMode.SHADE -> 8000
}

/** Maximum gamma exponent (deepest shadows) reachable when [ManualControls.shadowsBoost] = -1. */
private const val SHADOWS_MAX_GAMMA = 2.2f

/** sRGB-like reference gamma used to build the "neutral" tone curve. */
private const val SHADOWS_BASE_GAMMA = 1f / 2.2f

/**
 * Builds a monotonic tone-mapping curve implementing the "shadows" half of Pixel's Dual Exposure.
 *
 * The curve is a power function `out = in ^ gamma` sampled at [pointCount] evenly spaced input
 * values, encoded as the interleaved `[in0, out0, in1, out1, ...]` array Camera2's `TonemapCurve`
 * expects for a single channel. A neutral curve (`shadows = 0`) reproduces the sRGB-ish gamma the
 * HAL would apply by default (`1/2.2`); positive values lower the exponent (lifting shadows) and
 * negative values raise it (deepening shadows). Highlights are anchored: `f(0) = 0`, `f(1) = 1`.
 *
 * @param shadows Shadows adjustment in `-1f..1f`; out-of-range values are clamped.
 * @param pointCount Number of `(in, out)` points; clamped to at least 2.
 */
fun buildShadowsTonemapCurve(shadows: Float, pointCount: Int): FloatArray {
    val s = shadows.coerceIn(ManualControls.SHADOWS_RANGE)
    val points = pointCount.coerceAtLeast(ManualCapabilities.MIN_TONEMAP_CURVE_POINTS)
    // Interpolate the exponent between the base gamma and a lifted/deepened one.
    val rawGamma = if (s >= 0f) {
        // Lift: exponent goes from 1/2.2 down to ~1/4.4 (brighter mid-shadows).
        SHADOWS_BASE_GAMMA * (1f - s * 0.5f)
    } else {
        // Deepen: exponent goes from 1/2.2 up to 1.0 (linear = darker shadows).
        SHADOWS_BASE_GAMMA + (1f - SHADOWS_BASE_GAMMA) * (-s)
    }
    val gamma = rawGamma.coerceIn(1f / (SHADOWS_MAX_GAMMA * 2f), 1f)
    val curve = FloatArray(points * 2)
    for (i in 0 until points) {
        val input = i.toFloat() / (points - 1)
        val output = if (i == points - 1) 1f else input.pow(gamma)
        curve[i * 2] = input
        curve[i * 2 + 1] = output.coerceIn(0f, 1f)
    }
    return curve
}
