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

import kotlin.math.ln
import kotlin.math.pow

/**
 * Scene brightness estimation from the camera's auto-exposure readout.
 *
 * The exposure value at ISO 100 (`EV100`) is a device-independent measure of scene luminance:
 *
 * ```
 * EV100 = log2(N^2 / t) - log2(ISO / 100)
 * ```
 *
 * where `N` is the f-number and `t` the exposure time in seconds. Typical values: sunlight
 * ~15, overcast ~12, bright interior ~7, dim interior ~4, candle-lit ~1. Pixel Camera suggests
 * Night Sight and enables the torch for video around EV100 3–4.
 */
object SceneBrightness {
    /** Aperture assumed when the HAL does not report `LENS_APERTURE` (common phone lens). */
    const val DEFAULT_APERTURE_F_NUMBER = 1.8f

    /** Below this EV100 the scene is considered "low light" (enter threshold). */
    const val LOW_LIGHT_ENTER_EV100 = 4.0f

    /** Above this EV100 the scene stops being "low light" (exit threshold, hysteresis). */
    const val LOW_LIGHT_EXIT_EV100 = 5.5f

    /**
     * Computes EV100 from the exposure triangle. Returns `null` when ISO or exposure time are
     * unknown or non-positive.
     */
    fun ev100(
        iso: Int?,
        exposureTimeNanos: Long?,
        apertureFNumber: Float? = null
    ): Float? {
        if (iso == null || iso <= 0 || exposureTimeNanos == null || exposureTimeNanos <= 0) {
            return null
        }
        val n = apertureFNumber?.takeIf { it > 0f } ?: DEFAULT_APERTURE_F_NUMBER
        val t = exposureTimeNanos / 1_000_000_000.0
        val ev = log2(n.toDouble().pow(2) / t) - log2(iso / 100.0)
        return ev.toFloat()
    }

    /** Convenience overload reading from a live [ExposureInfo]. */
    fun ev100(exposureInfo: ExposureInfo): Float? =
        ev100(exposureInfo.iso, exposureInfo.exposureTimeNanos, exposureInfo.apertureFNumber)

    /**
     * Approximate scene illuminance in lux for an EV100 value (incident-light convention,
     * calibration constant 250): `lux = 2.5 * 2^EV`.
     */
    fun approximateLux(ev100: Float): Float = (2.5 * 2.0.pow(ev100.toDouble())).toFloat()

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}

/**
 * Stateful low-light detector with hysteresis so the torch / Night hint does not flicker when
 * the scene hovers around the threshold.
 *
 * Feed it successive exposure readouts with [update]; read [isLowLight].
 */
class LowLightDetector(
    private val enterEv100: Float = SceneBrightness.LOW_LIGHT_ENTER_EV100,
    private val exitEv100: Float = SceneBrightness.LOW_LIGHT_EXIT_EV100
) {
    init {
        require(exitEv100 >= enterEv100) { "exit threshold must be >= enter threshold" }
    }

    var isLowLight: Boolean = false
        private set

    /** Last EV100 fed to the detector, or `null` if none/unknown. */
    var lastEv100: Float? = null
        private set

    /**
     * Updates the detector with a new EV100 sample. Unknown (`null`) samples leave the state
     * unchanged. Returns the new [isLowLight] value.
     */
    fun update(ev100: Float?): Boolean {
        if (ev100 == null || ev100.isNaN()) return isLowLight
        lastEv100 = ev100
        isLowLight = if (isLowLight) ev100 < exitEv100 else ev100 < enterEv100
        return isLowLight
    }

    /** Convenience: derive EV100 from [exposureInfo] and update. */
    fun update(exposureInfo: ExposureInfo): Boolean = update(SceneBrightness.ev100(exposureInfo))

    fun reset() {
        isLowLight = false
        lastEv100 = null
    }
}
