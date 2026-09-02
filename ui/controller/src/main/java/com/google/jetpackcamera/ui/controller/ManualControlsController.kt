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
package com.google.jetpackcamera.ui.controller

import com.google.jetpackcamera.model.WhiteBalanceMode

/**
 * Interface for the Pro (manual) camera controls: ISO, shutter, EV, white balance, focus, locks.
 *
 * All setters accept `null` to return that control to automatic.
 */
interface ManualControlsController {
    /** Toggles the Pro panel visibility in the viewfinder. */
    fun toggleProPanel()

    /** Enables/disables Pro mode. Disabling resets every control to auto. */
    fun setProModeEnabled(enabled: Boolean)

    /** Pins the ISO, or `null` for auto. */
    fun setIso(iso: Int?)

    /** Pins the shutter speed in nanoseconds, or `null` for auto. */
    fun setExposureTimeNanos(exposureTimeNanos: Long?)

    /** Sets exposure compensation in device steps (index), or `null`/0 for none. */
    fun setExposureCompensationIndex(index: Int?)

    /**
     * Sets a white balance preset, or `null`/[WhiteBalanceMode.AUTO] for auto. Clears any kelvin
     * value so the preset takes effect.
     */
    fun setWhiteBalance(mode: WhiteBalanceMode?)

    /**
     * Pins the white balance to a colour temperature in kelvin (overrides presets), or `null` to
     * go back to the preset / auto.
     */
    fun setWhiteBalanceKelvin(kelvin: Int?)

    /**
     * Sets the Dual Exposure "shadows" adjustment in `-1f..1f`, or `null`/0 for the HAL default
     * tone curve.
     */
    fun setShadowsBoost(shadows: Float?)

    /** Pins the focus distance in diopters (0 = infinity), or `null` for autofocus. */
    fun setFocusDistance(diopters: Float?)

    /** Locks/unlocks auto-exposure (Pixel: long-press on the viewfinder). */
    fun setAeLock(locked: Boolean)

    /** Locks/unlocks auto white balance. */
    fun setAwbLock(locked: Boolean)

    /** Resets every manual control to auto without leaving Pro mode. */
    fun resetToAuto()
}
