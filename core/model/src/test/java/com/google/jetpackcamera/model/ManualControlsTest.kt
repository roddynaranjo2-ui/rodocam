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
import org.junit.Test

class ManualControlsTest {

    private val fullCapabilities = ManualCapabilities(
        isoRange = 50..3200,
        exposureTimeRangeNanos = 100_000L..1_000_000_000L,
        exposureCompensationRange = -12..12,
        exposureCompensationStep = 1f / 6f,
        minimumFocusDistanceDiopters = 10f,
        supportedWhiteBalanceModes = setOf(WhiteBalanceMode.AUTO, WhiteBalanceMode.DAYLIGHT),
        isManualSensorSupported = true,
        isAeLockSupported = true,
        isAwbLockSupported = true
    )

    @Test
    fun auto_hasNoOverrides() {
        assertThat(ManualControls.AUTO.hasOverrides).isFalse()
        assertThat(ManualControls.AUTO.isManualExposure).isFalse()
        assertThat(ManualControls.AUTO.isManualFocus).isFalse()
        assertThat(ManualControls.AUTO.isManualWhiteBalance).isFalse()
    }

    @Test
    fun isoOnly_isManualExposure_andResolvesShutterFromAuto() {
        val controls = ManualControls(iso = 400)
        assertThat(controls.isManualExposure).isTrue()
        assertThat(controls.resolvedIso(lastAutoIso = 100)).isEqualTo(400)
        assertThat(controls.resolvedExposureTimeNanos(20_000_000L)).isEqualTo(20_000_000L)
    }

    @Test
    fun autoExposure_resolvesToNull() {
        val controls = ManualControls(focusDistanceDiopters = 1f)
        assertThat(controls.resolvedIso(100)).isNull()
        assertThat(controls.resolvedExposureTimeNanos(1L)).isNull()
    }

    @Test
    fun sanitize_clampsToRanges() {
        val result = fullCapabilities.sanitize(
            ManualControls(
                iso = 12800,
                exposureTimeNanos = 1L,
                exposureCompensationIndex = 40,
                focusDistanceDiopters = 99f,
                whiteBalance = WhiteBalanceMode.DAYLIGHT,
                aeLock = true,
                awbLock = true
            )
        )
        assertThat(result.iso).isEqualTo(3200)
        assertThat(result.exposureTimeNanos).isEqualTo(100_000L)
        assertThat(result.exposureCompensationIndex).isEqualTo(12)
        assertThat(result.focusDistanceDiopters).isEqualTo(10f)
        assertThat(result.whiteBalance).isEqualTo(WhiteBalanceMode.DAYLIGHT)
        assertThat(result.aeLock).isTrue()
        assertThat(result.awbLock).isTrue()
    }

    @Test
    fun sanitize_dropsUnsupportedControls() {
        val result = ManualCapabilities.NONE.sanitize(
            ManualControls(
                iso = 100,
                exposureTimeNanos = 1_000L,
                exposureCompensationIndex = 2,
                focusDistanceDiopters = 1f,
                whiteBalance = WhiteBalanceMode.SHADE,
                aeLock = true,
                awbLock = true
            )
        )
        assertThat(result).isEqualTo(ManualControls.AUTO)
    }

    @Test
    fun capabilities_flags() {
        assertThat(fullCapabilities.supportsManualExposure).isTrue()
        assertThat(fullCapabilities.supportsManualFocus).isTrue()
        assertThat(fullCapabilities.supportsExposureCompensation).isTrue()
        assertThat(fullCapabilities.supportsManualWhiteBalance).isTrue()
        assertThat(ManualCapabilities.NONE.supportsAnyManualControl).isFalse()
        // MANUAL_SENSOR missing => no manual exposure even with ranges present.
        assertThat(
            fullCapabilities.copy(isManualSensorSupported = false).supportsManualExposure
        ).isFalse()
    }

    @Test
    fun kelvinAndShadows_requirePostProcessingCapabilities() {
        assertThat(fullCapabilities.supportsWhiteBalanceKelvin).isFalse()
        assertThat(fullCapabilities.supportsShadowsBoost).isFalse()
        val postCaps = fullCapabilities.copy(
            isManualPostProcessingSupported = true,
            isAwbOffSupported = true,
            isTonemapCurveSupported = true,
            maxTonemapCurvePoints = 64
        )
        assertThat(postCaps.supportsWhiteBalanceKelvin).isTrue()
        assertThat(postCaps.supportsShadowsBoost).isTrue()
        // AWB_MODE_OFF missing => no kelvin even with post-processing.
        assertThat(postCaps.copy(isAwbOffSupported = false).supportsWhiteBalanceKelvin).isFalse()
        // A one-point curve is useless.
        assertThat(postCaps.copy(maxTonemapCurvePoints = 1).supportsShadowsBoost).isFalse()
        // Kelvin alone makes WB "manual" even without presets.
        assertThat(
            postCaps.copy(supportedWhiteBalanceModes = setOf(WhiteBalanceMode.AUTO))
                .supportsManualWhiteBalance
        ).isTrue()
    }

    @Test
    fun sanitize_dropsKelvinAndShadowsWhenUnsupported() {
        val result = fullCapabilities.sanitize(
            ManualControls(whiteBalanceKelvin = 4000, shadowsBoost = 0.5f)
        )
        assertThat(result.whiteBalanceKelvin).isNull()
        assertThat(result.shadowsBoost).isNull()
        assertThat(result.isManualWhiteBalance).isFalse()
        assertThat(result.isShadowsAdjusted).isFalse()
    }

    @Test
    fun sanitize_clampsKelvinAndShadows_andNormalisesNeutralShadows() {
        val postCaps = fullCapabilities.copy(
            isManualPostProcessingSupported = true,
            isAwbOffSupported = true,
            isTonemapCurveSupported = true,
            maxTonemapCurvePoints = 64
        )
        val clamped = postCaps.sanitize(
            ManualControls(whiteBalanceKelvin = 50_000, shadowsBoost = 3f)
        )
        assertThat(clamped.whiteBalanceKelvin)
            .isEqualTo(ManualControls.WHITE_BALANCE_KELVIN_RANGE.last)
        assertThat(clamped.shadowsBoost).isEqualTo(1f)
        val low = postCaps.sanitize(ManualControls(whiteBalanceKelvin = 100, shadowsBoost = -9f))
        assertThat(low.whiteBalanceKelvin)
            .isEqualTo(ManualControls.WHITE_BALANCE_KELVIN_RANGE.first)
        assertThat(low.shadowsBoost).isEqualTo(-1f)
        // 0 shadows collapses to null so equality with AUTO holds.
        val neutral = postCaps.sanitize(ManualControls(shadowsBoost = 0f))
        assertThat(neutral.shadowsBoost).isNull()
        assertThat(neutral.isFullyAuto).isTrue()
    }

    @Test
    fun kelvin_isManualWhiteBalance_andTakesPrecedenceFlag() {
        val controls = ManualControls(whiteBalanceKelvin = 3200)
        assertThat(controls.isManualWhiteBalance).isTrue()
        assertThat(controls.isKelvinWhiteBalance).isTrue()
        assertThat(controls.hasOverrides).isTrue()
        assertThat(ManualControls(whiteBalance = WhiteBalanceMode.DAYLIGHT).isKelvinWhiteBalance)
            .isFalse()
    }

    @Test
    fun formatShutterSpeed_formats() {
        assertThat(formatShutterSpeed(4_000_000L)).isEqualTo("1/250")
        assertThat(formatShutterSpeed(125_000_000L)).isEqualTo("1/8")
        assertThat(formatShutterSpeed(500_000_000L)).isEqualTo("0.5\"")
        assertThat(formatShutterSpeed(2_000_000_000L)).isEqualTo("2\"")
        assertThat(formatShutterSpeed(0L)).isEqualTo("—")
    }
}
