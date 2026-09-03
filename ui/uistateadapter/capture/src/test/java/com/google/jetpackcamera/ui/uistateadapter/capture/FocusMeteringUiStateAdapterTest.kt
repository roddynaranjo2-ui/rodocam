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
package com.google.jetpackcamera.ui.uistateadapter.capture

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.core.camera.CameraState
import com.google.jetpackcamera.core.camera.FocusState
import com.google.jetpackcamera.ui.uistate.capture.FocusMeteringUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
internal class FocusMeteringUiStateAdapterTest {

    private fun cameraStateWith(focusState: FocusState) = CameraState(focusState = focusState)

    @Test
    fun from_unspecifiedFocus_returnsUnspecified() {
        val uiState = FocusMeteringUiState.from(cameraStateWith(FocusState.Unspecified))

        assertThat(uiState).isEqualTo(FocusMeteringUiState.Unspecified)
    }

    @Test
    fun from_specifiedFocus_mapsCoordinatesStatusAndLock() {
        val cameraState = cameraStateWith(
            FocusState.Specified(
                x = 120f,
                y = 340f,
                status = FocusState.Status.SUCCESS,
                isLocked = true
            )
        )

        val uiState = FocusMeteringUiState.from(cameraState)

        assertThat(uiState).isEqualTo(
            FocusMeteringUiState.Specified(
                surfaceCoordinates = Offset(120f, 340f),
                status = FocusMeteringUiState.Status.SUCCESS,
                isLocked = true
            )
        )
    }

    @Test
    fun from_specifiedFocus_defaultsToUnlocked() {
        val cameraState = cameraStateWith(
            FocusState.Specified(x = 1f, y = 2f, status = FocusState.Status.RUNNING)
        )

        val uiState = FocusMeteringUiState.from(cameraState) as FocusMeteringUiState.Specified

        assertThat(uiState.isLocked).isFalse()
        assertThat(uiState.status).isEqualTo(FocusMeteringUiState.Status.RUNNING)
    }

    @Test
    fun from_mapsEveryStatus() {
        val expected = mapOf(
            FocusState.Status.RUNNING to FocusMeteringUiState.Status.RUNNING,
            FocusState.Status.SUCCESS to FocusMeteringUiState.Status.SUCCESS,
            FocusState.Status.FAILURE to FocusMeteringUiState.Status.FAILURE,
            FocusState.Status.CANCELLED to FocusMeteringUiState.Status.CANCELLED
        )

        expected.forEach { (coreStatus, uiStatus) ->
            val uiState = FocusMeteringUiState.from(
                cameraStateWith(FocusState.Specified(x = 0f, y = 0f, status = coreStatus))
            ) as FocusMeteringUiState.Specified

            assertThat(uiState.status).isEqualTo(uiStatus)
        }
    }

    @Test
    fun updateFrom_sameState_returnsSameInstance() {
        val focusState = FocusState.Specified(
            x = 10f,
            y = 20f,
            status = FocusState.Status.SUCCESS,
            isLocked = true
        )
        val initial = FocusMeteringUiState.from(cameraStateWith(focusState))

        val updated = initial.updateFrom(cameraStateWith(focusState))

        assertThat(updated).isSameInstanceAs(initial)
    }

    @Test
    fun updateFrom_lockChange_returnsNewState() {
        val unlocked = FocusState.Specified(x = 10f, y = 20f, status = FocusState.Status.SUCCESS)
        val initial = FocusMeteringUiState.from(cameraStateWith(unlocked))

        val updated = initial.updateFrom(cameraStateWith(unlocked.copy(isLocked = true)))

        assertThat(updated).isNotSameInstanceAs(initial)
        assertThat((updated as FocusMeteringUiState.Specified).isLocked).isTrue()
    }

    @Test
    fun updateFrom_unspecifiedToSpecified_andBack() {
        val specified = FocusState.Specified(x = 5f, y = 6f, status = FocusState.Status.RUNNING)

        val unspecified = FocusMeteringUiState.Unspecified
        val toSpecified = unspecified.updateFrom(cameraStateWith(specified))
        val backToUnspecified = toSpecified.updateFrom(cameraStateWith(FocusState.Unspecified))

        assertThat(toSpecified).isInstanceOf(FocusMeteringUiState.Specified::class.java)
        assertThat(backToUnspecified).isEqualTo(FocusMeteringUiState.Unspecified)
        assertThat(unspecified.updateFrom(cameraStateWith(FocusState.Unspecified)))
            .isSameInstanceAs(unspecified)
    }
}
