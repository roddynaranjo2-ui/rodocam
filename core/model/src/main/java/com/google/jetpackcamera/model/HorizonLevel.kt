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
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Horizon level state derived from the gravity vector, for Pixel-style "framing hints".
 *
 * @property rollDegrees Rotation of the device around the axis pointing out of the screen, in
 *   `-180..180` degrees, relative to the nearest level orientation (portrait or landscape). `0`
 *   means the horizon is level. Positive = clockwise tilt as seen by the user.
 * @property pitchDegrees Tilt towards/away from the user in `-90..90`. `0` = phone upright,
 *   `±90` = pointing straight down/up (level is meaningless there, see [isUsable]).
 * @property isLevel True when [rollDegrees] is within [HorizonLevel.LEVEL_TOLERANCE_DEGREES].
 * @property isUsable False when the device points almost straight up or down: the roll angle is
 *   then ill-defined and the indicator should hide, as Pixel does.
 */
data class HorizonLevel(
    val rollDegrees: Float,
    val pitchDegrees: Float
) {
    val isLevel: Boolean get() = abs(rollDegrees) <= LEVEL_TOLERANCE_DEGREES
    val isUsable: Boolean get() = abs(pitchDegrees) < UNUSABLE_PITCH_DEGREES

    companion object {
        /** Roll tolerance for the level indicator to snap/highlight (Pixel uses about 1 deg). */
        const val LEVEL_TOLERANCE_DEGREES = 1.0f

        /** Beyond this pitch the roll angle becomes unstable and the indicator hides. */
        const val UNUSABLE_PITCH_DEGREES = 70f

        /** Reference for tests/previews: perfectly level, upright phone. */
        val LEVEL = HorizonLevel(rollDegrees = 0f, pitchDegrees = 0f)

        /**
         * Computes the level from a gravity (or low-pass filtered accelerometer) vector in the
         * Android sensor coordinate system (x right, y up, z out of the screen, portrait natural
         * orientation).
         *
         * @param displayRotationDegrees Current display rotation (0/90/180/270) so that the roll
         *   is reported relative to the *screen* orientation, not the device's natural one.
         */
        fun fromGravity(
            gx: Float,
            gy: Float,
            gz: Float,
            displayRotationDegrees: Int = 0
        ): HorizonLevel {
            val norm = sqrt(gx * gx + gy * gy + gz * gz)
            if (norm < 1e-3f) return LEVEL
            // Rotate the in-screen components so "up" follows the display rotation.
            val (sx, sy) = when (((displayRotationDegrees % 360) + 360) % 360) {
                90 -> Pair(-gy, gx)
                180 -> Pair(-gx, -gy)
                270 -> Pair(gy, -gx)
                else -> Pair(gx, gy)
            }
            // Roll: angle between screen "up" (+y) and the projection of gravity on the screen.
            val rollRad = atan2(sx, sy)
            var roll = Math.toDegrees(rollRad.toDouble()).toFloat()
            // Fold to the nearest of the 4 level orientations so landscape is level too.
            roll = foldToNearestQuadrant(roll)
            // Pitch: how far gravity leaves the screen plane.
            val inPlane = sqrt(sx * sx + sy * sy)
            val pitch = Math.toDegrees(atan2(gz, inPlane).toDouble()).toFloat()
            return HorizonLevel(rollDegrees = roll, pitchDegrees = pitch)
        }

        /** Maps any angle to `-45..45` relative to the nearest multiple of 90 degrees. */
        internal fun foldToNearestQuadrant(degrees: Float): Float {
            var d = degrees % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            while (d > 45f) d -= 90f
            while (d < -45f) d += 90f
            return d
        }
    }
}
