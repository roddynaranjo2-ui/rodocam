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
package com.google.jetpackcamera.feature.preview

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DoubleTapZoomTest {

    @Test
    fun isApproximatelyOneX_acceptsSmallDeviations() {
        assertThat(isApproximatelyOneX(1f)).isTrue()
        assertThat(isApproximatelyOneX(1.04f)).isTrue()
        assertThat(isApproximatelyOneX(0.96f)).isTrue()
        assertThat(isApproximatelyOneX(1.06f)).isFalse()
        assertThat(isApproximatelyOneX(0.5f)).isFalse()
        assertThat(isApproximatelyOneX(2f)).isFalse()
    }

    @Test
    fun target_fromOneX_goesToTwoX() {
        assertThat(doubleTapZoomTarget(1f, 0.6f, 10f)).isEqualTo(DOUBLE_TAP_ZOOM_RATIO)
    }

    @Test
    fun target_unknownCurrent_isTreatedAsOneX() {
        assertThat(doubleTapZoomTarget(null, 1f, 8f)).isEqualTo(DOUBLE_TAP_ZOOM_RATIO)
    }

    @Test
    fun target_fromTwoX_returnsToOneX() {
        assertThat(doubleTapZoomTarget(2f, 0.6f, 10f)).isEqualTo(1f)
    }

    @Test
    fun target_fromAnyOtherRatio_returnsToOneX() {
        assertThat(doubleTapZoomTarget(0.6f, 0.6f, 10f)).isEqualTo(1f)
        assertThat(doubleTapZoomTarget(5f, 0.6f, 10f)).isEqualTo(1f)
        assertThat(doubleTapZoomTarget(1.3f, 0.6f, 10f)).isEqualTo(1f)
    }

    @Test
    fun target_disabledWhenLensCannotReachTwoX() {
        assertThat(doubleTapZoomTarget(1f, 1f, 1.9f)).isNull()
        assertThat(doubleTapZoomTarget(1.5f, 1f, 1.9f)).isNull()
    }

    @Test
    fun target_exactlyTwoXMax_isEnabled() {
        assertThat(doubleTapZoomTarget(1f, 1f, 2f)).isEqualTo(2f)
    }

    @Test
    fun target_clampedToMinimumWhenOneXIsBelowRange() {
        // Some tele-only "primary" configurations start above 1x; returning must clamp.
        assertThat(doubleTapZoomTarget(3f, 1.5f, 10f)).isEqualTo(1.5f)
    }
}
