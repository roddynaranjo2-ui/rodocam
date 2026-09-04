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

class ThermalPolicyTest {

    private val allRates = setOf(TARGET_FPS_15, TARGET_FPS_24, TARGET_FPS_30, TARGET_FPS_60)

    @Test
    fun status_fromPlatformStatus_mapsAllValues() {
        assertThat(ThermalStatus.fromPlatformStatus(0)).isEqualTo(ThermalStatus.NONE)
        assertThat(ThermalStatus.fromPlatformStatus(1)).isEqualTo(ThermalStatus.LIGHT)
        assertThat(ThermalStatus.fromPlatformStatus(2)).isEqualTo(ThermalStatus.MODERATE)
        assertThat(ThermalStatus.fromPlatformStatus(3)).isEqualTo(ThermalStatus.SEVERE)
        assertThat(ThermalStatus.fromPlatformStatus(4)).isEqualTo(ThermalStatus.CRITICAL)
        assertThat(ThermalStatus.fromPlatformStatus(5)).isEqualTo(ThermalStatus.EMERGENCY)
        assertThat(ThermalStatus.fromPlatformStatus(6)).isEqualTo(ThermalStatus.SHUTDOWN)
        assertThat(ThermalStatus.fromPlatformStatus(-1)).isEqualTo(ThermalStatus.UNKNOWN)
        assertThat(ThermalStatus.fromPlatformStatus(42)).isEqualTo(ThermalStatus.UNKNOWN)
    }

    @Test
    fun status_severityFlags() {
        assertThat(ThermalStatus.UNKNOWN.isThrottling).isFalse()
        assertThat(ThermalStatus.NONE.isThrottling).isFalse()
        assertThat(ThermalStatus.LIGHT.isThrottling).isFalse()
        assertThat(ThermalStatus.MODERATE.isThrottling).isTrue()
        assertThat(ThermalStatus.MODERATE.isHot).isFalse()
        assertThat(ThermalStatus.SEVERE.isHot).isTrue()
        assertThat(ThermalStatus.SHUTDOWN.isHot).isTrue()
    }

    @Test
    fun policy_coolStatuses_doNotRestrict() {
        listOf(ThermalStatus.UNKNOWN, ThermalStatus.NONE, ThermalStatus.LIGHT).forEach { status ->
            val policy = ThermalPolicy.forStatus(status)
            assertThat(policy.isRestricting).isFalse()
            assertThat(policy.applyTargetFrameRate(TARGET_FPS_60, allRates))
                .isEqualTo(TARGET_FPS_60)
            assertThat(policy.applyVideoQuality(VideoQuality.UHD)).isEqualTo(VideoQuality.UHD)
            val assist = ViewfinderAssistSettings(isFocusPeakingEnabled = true)
            assertThat(policy.applyViewfinderAssist(assist)).isSameInstanceAs(assist)
        }
    }

    @Test
    fun policy_moderate_dropsShaderAndCapsFpsOnly() {
        val policy = ThermalPolicy.forStatus(ThermalStatus.MODERATE)
        assertThat(policy.isRestricting).isTrue()
        assertThat(policy.allowShaderEffect).isFalse()
        assertThat(policy.maxTargetFrameRate).isEqualTo(TARGET_FPS_30)
        assertThat(policy.maxVideoQuality).isNull()
        assertThat(policy.applyVideoQuality(VideoQuality.UHD)).isEqualTo(VideoQuality.UHD)
    }

    @Test
    fun policy_severe_capsVideoToFhd() {
        val policy = ThermalPolicy.forStatus(ThermalStatus.SEVERE)
        assertThat(policy.applyVideoQuality(VideoQuality.UHD)).isEqualTo(VideoQuality.FHD)
        assertThat(policy.applyVideoQuality(VideoQuality.HD)).isEqualTo(VideoQuality.HD)
        assertThat(policy.applyVideoQuality(VideoQuality.UNSPECIFIED))
            .isEqualTo(VideoQuality.UNSPECIFIED)
    }

    @Test
    fun policy_criticalAndAbove_capsVideoToHdAndFpsTo24() {
        listOf(ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN).forEach {
            val policy = ThermalPolicy.forStatus(it)
            assertThat(policy.maxTargetFrameRate).isEqualTo(TARGET_FPS_24)
            assertThat(policy.applyVideoQuality(VideoQuality.FHD)).isEqualTo(VideoQuality.HD)
            assertThat(policy.applyTargetFrameRate(TARGET_FPS_60, allRates))
                .isEqualTo(TARGET_FPS_24)
        }
    }

    @Test
    fun applyTargetFrameRate_keepsAutoAndRatesBelowCap() {
        val policy = ThermalPolicy.forStatus(ThermalStatus.MODERATE)
        assertThat(policy.applyTargetFrameRate(TARGET_FPS_AUTO, allRates))
            .isEqualTo(TARGET_FPS_AUTO)
        assertThat(policy.applyTargetFrameRate(TARGET_FPS_24, allRates)).isEqualTo(TARGET_FPS_24)
        assertThat(policy.applyTargetFrameRate(TARGET_FPS_30, allRates)).isEqualTo(TARGET_FPS_30)
        assertThat(policy.applyTargetFrameRate(TARGET_FPS_120, allRates)).isEqualTo(TARGET_FPS_30)
    }

    @Test
    fun applyTargetFrameRate_fallsBackToHighestSupportedUnderCapOrAuto() {
        val policy = ThermalPolicy.forStatus(ThermalStatus.MODERATE)
        // 30 not supported: pick the highest supported rate under the cap.
        assertThat(policy.applyTargetFrameRate(TARGET_FPS_60, setOf(TARGET_FPS_24, TARGET_FPS_60)))
            .isEqualTo(TARGET_FPS_24)
        // Nothing under the cap: let the camera pick.
        assertThat(policy.applyTargetFrameRate(TARGET_FPS_60, setOf(TARGET_FPS_60)))
            .isEqualTo(TARGET_FPS_AUTO)
    }

    @Test
    fun applyViewfinderAssist_disablesOnlyShaderFeatures() {
        val policy = ThermalPolicy.forStatus(ThermalStatus.SEVERE)
        val settings = ViewfinderAssistSettings(
            grid = CompositionGrid.THIRDS,
            isHistogramEnabled = true,
            isZebrasEnabled = true,
            isFocusPeakingEnabled = true
        )
        val result = policy.applyViewfinderAssist(settings)
        assertThat(result.isZebrasEnabled).isFalse()
        assertThat(result.isFocusPeakingEnabled).isFalse()
        assertThat(result.needsShaderEffect).isFalse()
        assertThat(result.grid).isEqualTo(CompositionGrid.THIRDS)
        assertThat(result.isHistogramEnabled).isTrue()
        // Nothing to disable: same instance.
        val plain = ViewfinderAssistSettings(grid = CompositionGrid.THIRDS)
        assertThat(policy.applyViewfinderAssist(plain)).isSameInstanceAs(plain)
    }

    @Test
    fun policyNone_isUnrestricted() {
        assertThat(ThermalPolicy.NONE.isRestricting).isFalse()
        assertThat(ThermalPolicy.NONE.status).isEqualTo(ThermalStatus.NONE)
    }
}
