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
package com.google.jetpackcamera.ui.controller.impl

import com.google.jetpackcamera.core.camera.CameraSystem
import com.google.jetpackcamera.model.ManualControls
import com.google.jetpackcamera.model.WhiteBalanceMode
import com.google.jetpackcamera.ui.controller.ManualControlsController
import com.google.jetpackcamera.ui.uistate.capture.TrackedCaptureUiState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Implementation of [ManualControlsController] backed by [CameraSystem].
 *
 * Each setter reads the latest [ManualControls] from the camera system's current settings and
 * publishes a copy with the single field changed, so concurrent slider updates never clobber
 * each other.
 *
 * @param onProModeEnabledPersist invoked after Pro mode is toggled so the caller can persist the
 * preference (e.g. through the settings repository). Defaults to a no-op.
 */
class ManualControlsControllerImpl(
    private val cameraSystem: CameraSystem,
    private val trackedCaptureUiState: MutableStateFlow<TrackedCaptureUiState>,
    coroutineContext: CoroutineContext,
    private val onProModeEnabledPersist: suspend (Boolean) -> Unit = {}
) : ManualControlsController {
    private val job = Job(parent = coroutineContext[Job.Key])
    private val scope = CoroutineScope(coroutineContext + job)

    private inline fun update(transform: (ManualControls) -> ManualControls) {
        val current = cameraSystem.getCurrentSettings().value?.manualControls ?: ManualControls.AUTO
        cameraSystem.setManualControls(transform(current))
    }

    override fun toggleProPanel() {
        trackedCaptureUiState.update { old -> old.copy(isProPanelOpen = !old.isProPanelOpen) }
    }

    override fun setProModeEnabled(enabled: Boolean) {
        scope.launch {
            cameraSystem.setProModeEnabled(enabled)
            onProModeEnabledPersist(enabled)
        }
        if (!enabled) {
            trackedCaptureUiState.update { old -> old.copy(isProPanelOpen = false) }
        }
    }

    override fun setIso(iso: Int?) = update { it.copy(iso = iso) }

    override fun setExposureTimeNanos(exposureTimeNanos: Long?) =
        update { it.copy(exposureTimeNanos = exposureTimeNanos) }

    override fun setExposureCompensationIndex(index: Int?) =
        update { it.copy(exposureCompensationIndex = index?.takeIf { v -> v != 0 }) }

    override fun setWhiteBalance(mode: WhiteBalanceMode?) = update {
        it.copy(
            whiteBalance = mode?.takeIf { m -> m != WhiteBalanceMode.AUTO },
            whiteBalanceKelvin = null
        )
    }

    override fun setWhiteBalanceKelvin(kelvin: Int?) = update {
        it.copy(
            whiteBalanceKelvin = kelvin?.coerceIn(ManualControls.WHITE_BALANCE_KELVIN_RANGE)
        )
    }

    override fun setShadowsBoost(shadows: Float?) = update {
        it.copy(
            shadowsBoost = shadows?.coerceIn(ManualControls.SHADOWS_RANGE)?.takeIf { s -> s != 0f }
        )
    }

    override fun setFocusDistance(diopters: Float?) =
        update { it.copy(focusDistanceDiopters = diopters) }

    override fun setAeLock(locked: Boolean) = update { it.copy(aeLock = locked) }

    override fun setAwbLock(locked: Boolean) = update { it.copy(awbLock = locked) }

    override fun resetToAuto() = cameraSystem.setManualControls(ManualControls.AUTO)
}
