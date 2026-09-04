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

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.ui.uistate.DisableRationale
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeCarouselUiState
import com.google.jetpackcamera.ui.uistate.capture.ShootingMode
import org.junit.Test

class ModeCarouselHelpersTest {

    private object Rationale : DisableRationale {
        override val reasonTextResId: Int = 0
    }

    private val state = CaptureModeCarouselUiState.Available(
        selectedMode = ShootingMode.PHOTO,
        modes = listOf(
            SingleSelectableUiState.SelectableUi(ShootingMode.VIDEO),
            SingleSelectableUiState.SelectableUi(ShootingMode.PHOTO),
            SingleSelectableUiState.Disabled(ShootingMode.PORTRAIT, Rationale),
            SingleSelectableUiState.SelectableUi(ShootingMode.NIGHT),
            SingleSelectableUiState.SelectableUi(ShootingMode.PRO)
        )
    )

    @Test
    fun nextSelectable_skipsDisabledEntries() {
        assertThat(nextSelectable(state, step = 1)).isEqualTo(ShootingMode.NIGHT)
        assertThat(nextSelectable(state, step = -1)).isEqualTo(ShootingMode.VIDEO)
    }

    @Test
    fun nextSelectable_returnsNullAtEdges() {
        val atStart = state.copy(selectedMode = ShootingMode.VIDEO)
        assertThat(nextSelectable(atStart, step = -1)).isNull()
        val atEnd = state.copy(selectedMode = ShootingMode.PRO)
        assertThat(nextSelectable(atEnd, step = 1)).isNull()
    }

    @Test
    fun rowShiftFor_centersSelectedChip() {
        // 5 chips of 84dp: centre chip (index 2) needs no shift.
        assertThat(rowShiftFor(5, 2, 84.dp)).isEqualTo(0.dp)
        // First chip must move right by two chip widths.
        assertThat(rowShiftFor(5, 0, 84.dp)).isEqualTo(168.dp)
        // Last chip must move left by two chip widths.
        assertThat(rowShiftFor(5, 4, 84.dp)).isEqualTo((-168).dp)
        // Even count: 2 chips, selecting the first shifts by half a chip.
        assertThat(rowShiftFor(2, 0, 84.dp)).isEqualTo(42.dp)
    }

    @Test
    fun modeChipTestTag_isUniquePerMode() {
        val tags = ShootingMode.entries.map { modeChipTestTag(it) }
        assertThat(tags).containsNoDuplicates()
        assertThat(tags).doesNotContain(MODE_CAROUSEL_TAG)
    }
}
