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

import kotlin.math.abs
import kotlin.math.roundToInt

// Pure, Compose-free helpers backing the Pro panel readouts and slider snapping. Kept separate so
// they can be unit-tested on the JVM without Robolectric.

/** Formats a colour temperature as `5500 K`. */
internal fun formatKelvin(kelvin: Int): String = "$kelvin K"

/** Rounds a slider position to the nearest kelvin step. */
internal fun snapKelvin(pos: Float, step: Int): Int = (pos / step).roundToInt() * step

/** Snaps the shadows slider to 0 around the centre and to 0.05 increments elsewhere. */
internal fun snapShadows(pos: Float): Float {
    val snapped = (pos * 20).roundToInt() / 20f
    return if (abs(snapped) < 0.075f) 0f else snapped.coerceIn(-1f, 1f)
}

/** Formats the shadows adjustment as a signed percentage, `0` when neutral. */
internal fun formatShadows(shadows: Float?): String {
    val percent = ((shadows ?: 0f) * 100).roundToInt()
    return when {
        percent == 0 -> "0"
        percent > 0 -> "+$percent"
        else -> "$percent"
    }
}

internal fun formatEv(ev: Float): String {
    val rounded = (ev * 10).roundToInt() / 10f
    return when {
        rounded == 0f -> "0"
        rounded > 0f -> "+$rounded"
        else -> "$rounded"
    }
}

/** Formats a focus distance in diopters as metres, with ∞ at 0 and "Macro" at the near limit. */
internal fun formatFocus(diopters: Float, maxDiopters: Float): String = when {
    diopters <= 0.05f -> "∞"
    diopters >= maxDiopters * 0.98f -> "Macro"
    else -> {
        val metres = 1f / diopters
        if (metres >= 1f) {
            "${(metres * 10).roundToInt() / 10f} m"
        } else {
            "${(metres * 100).roundToInt()} cm"
        }
    }
}

private val ISO_STOPS = intArrayOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
    2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800
)

/** Snaps to standard 1/3-stop ISO values so the readout matches what photographers expect. */
internal fun snapIso(iso: Int): Int = ISO_STOPS.minByOrNull { abs(it - iso) } ?: iso

private val SHUTTER_DENOMINATORS = intArrayOf(
    8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200,
    160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2
)

/** Sub-second shutter candidates in nanoseconds, precomputed once (adds 0.7 s as a 1/1.4 stop). */
private val SHUTTER_CANDIDATES_NANOS: LongArray =
    LongArray(SHUTTER_DENOMINATORS.size + 1) { i ->
        if (i < SHUTTER_DENOMINATORS.size) {
            1_000_000_000L / SHUTTER_DENOMINATORS[i]
        } else {
            700_000_000L
        }
    }

/** Snaps to standard shutter speeds (1/8000 … 1/2, then whole/half seconds). */
internal fun snapShutter(nanos: Long): Long {
    if (nanos >= 1_000_000_000L) {
        return ((nanos + 250_000_000L) / 500_000_000L) * 500_000_000L
    }
    return SHUTTER_CANDIDATES_NANOS.minByOrNull { abs(it - nanos) } ?: nanos
}
