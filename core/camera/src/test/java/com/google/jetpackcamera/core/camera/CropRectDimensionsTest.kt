/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.jetpackcamera.core.camera

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the crop rect helpers used to publish [VideoQualityInfo].
 *
 * The helpers previously computed width from `top/bottom` and height from `left/right`, which
 * only worked for square crops.
 */
@RunWith(AndroidJUnit4::class)
class CropRectDimensionsTest {

    @Test
    fun widthAndHeight_nullRect_returnsZero() {
        assertThat(getWidthFromCropRect(null)).isEqualTo(0)
        assertThat(getHeightFromCropRect(null)).isEqualTo(0)
    }

    @Test
    fun widthAndHeight_landscapeRect_matchesRectDimensions() {
        // 16:9 crop at an offset
        val rect = Rect(100, 50, 100 + 1920, 50 + 1080)

        assertThat(getWidthFromCropRect(rect)).isEqualTo(1920)
        assertThat(getHeightFromCropRect(rect)).isEqualTo(1080)
    }

    @Test
    fun widthAndHeight_portraitRect_matchesRectDimensions() {
        val rect = Rect(0, 0, 1080, 1920)

        assertThat(getWidthFromCropRect(rect)).isEqualTo(1080)
        assertThat(getHeightFromCropRect(rect)).isEqualTo(1920)
    }

    @Test
    fun widthAndHeight_invertedCoordinates_areAbsolute() {
        // Defensive: an inverted rect should still produce positive dimensions.
        val rect = Rect(1920, 1080, 0, 0)

        assertThat(getWidthFromCropRect(rect)).isEqualTo(1920)
        assertThat(getHeightFromCropRect(rect)).isEqualTo(1080)
    }
}
