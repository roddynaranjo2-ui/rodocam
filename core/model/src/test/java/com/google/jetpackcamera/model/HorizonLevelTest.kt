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
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class HorizonLevelTest {

    private val g = 9.81f

    @Test
    fun upright_isLevel() {
        val level = HorizonLevel.fromGravity(0f, g, 0f)
        assertThat(level.rollDegrees).isWithin(1e-3f).of(0f)
        assertThat(level.pitchDegrees).isWithin(1e-3f).of(0f)
        assertThat(level.isLevel).isTrue()
        assertThat(level.isUsable).isTrue()
    }

    @Test
    fun tiltedClockwise_reportsPositiveRoll() {
        // Rotate gravity 10 degrees in the screen plane.
        val rad = Math.toRadians(10.0)
        val level = HorizonLevel.fromGravity(
            gx = (g * sin(rad)).toFloat(),
            gy = (g * cos(rad)).toFloat(),
            gz = 0f
        )
        assertThat(level.rollDegrees).isWithin(0.01f).of(10f)
        assertThat(level.isLevel).isFalse()
    }

    @Test
    fun landscape_isAlsoLevel() {
        // Gravity along +x = device rotated 90 degrees: still a level horizon.
        val level = HorizonLevel.fromGravity(g, 0f, 0f)
        assertThat(level.rollDegrees).isWithin(1e-3f).of(0f)
        assertThat(level.isLevel).isTrue()
        // Slightly off landscape -> small roll, not ~90.
        val rad = Math.toRadians(87.0)
        val almost = HorizonLevel.fromGravity(
            gx = (g * sin(rad)).toFloat(),
            gy = (g * cos(rad)).toFloat(),
            gz = 0f
        )
        assertThat(almost.rollDegrees).isWithin(0.01f).of(-3f)
    }

    @Test
    fun displayRotation_isCompensated() {
        // Device rotated 90 degrees with the display following it: gravity along +x in sensor
        // coordinates should read as level with a 90-degree display rotation.
        val level = HorizonLevel.fromGravity(g, 0f, 0f, displayRotationDegrees = 90)
        assertThat(level.rollDegrees).isWithin(1e-3f).of(0f)
        val tilt = Math.toRadians(5.0)
        val tilted = HorizonLevel.fromGravity(
            gx = (g * cos(tilt)).toFloat(),
            gy = (g * sin(tilt)).toFloat(),
            gz = 0f,
            displayRotationDegrees = 90
        )
        assertThat(kotlin.math.abs(tilted.rollDegrees)).isWithin(0.01f).of(5f)
    }

    @Test
    fun pointingDown_isUnusable() {
        val level = HorizonLevel.fromGravity(0f, 0.5f, g)
        assertThat(kotlin.math.abs(level.pitchDegrees)).isGreaterThan(80f)
        assertThat(level.isUsable).isFalse()
    }

    @Test
    fun zeroVector_isSafe() {
        assertThat(HorizonLevel.fromGravity(0f, 0f, 0f)).isEqualTo(HorizonLevel.LEVEL)
    }

    @Test
    fun foldToNearestQuadrant() {
        assertThat(HorizonLevel.foldToNearestQuadrant(0f)).isEqualTo(0f)
        assertThat(HorizonLevel.foldToNearestQuadrant(44f)).isEqualTo(44f)
        assertThat(HorizonLevel.foldToNearestQuadrant(46f)).isWithin(1e-4f).of(-44f)
        assertThat(HorizonLevel.foldToNearestQuadrant(90f)).isWithin(1e-4f).of(0f)
        assertThat(HorizonLevel.foldToNearestQuadrant(-92f)).isWithin(1e-4f).of(-2f)
        assertThat(HorizonLevel.foldToNearestQuadrant(179f)).isWithin(1e-4f).of(-1f)
        assertThat(HorizonLevel.foldToNearestQuadrant(361f)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun levelTolerance() {
        assertThat(HorizonLevel(0.9f, 0f).isLevel).isTrue()
        assertThat(HorizonLevel(-1.0f, 0f).isLevel).isTrue()
        assertThat(HorizonLevel(1.2f, 0f).isLevel).isFalse()
    }
}
