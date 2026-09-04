/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.google.jetpackcamera.core.camera.effects

import com.google.common.truth.Truth.assertThat
import com.google.jetpackcamera.core.camera.ViewfinderAssistAwareEffectProvider
import com.google.jetpackcamera.model.CameraEffectTarget
import com.google.jetpackcamera.model.VIEWFINDER_ASSIST_EFFECT_ID
import com.google.jetpackcamera.model.ViewfinderAssistSettings
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [ViewfinderAssistEffectProvider], [ViewfinderAssistEffectKey],
 * [ViewfinderAssistEffectConfig], [FocusPeakingConfig], [ZebraConfig] and the zebra phase
 * helper of [ShaderCopy]. Everything here is pure JVM (no GL context is created).
 */
class ViewfinderAssistEffectProviderTest {

    // ---- provider / key ---------------------------------------------------------------------

    @Test
    fun provider_targetsPreviewOnly() {
        val provider = ViewfinderAssistEffectProvider()
        assertThat(provider.targets).containsExactly(CameraEffectTarget.PREVIEW)
    }

    @Test
    fun provider_isViewfinderAssistAware() {
        val provider = ViewfinderAssistEffectProvider()
        assertThat(provider).isInstanceOf(ViewfinderAssistAwareEffectProvider::class.java)
    }

    @Test
    fun key_usesViewfinderAssistIdDistinctFromSingleStream() {
        assertThat(ViewfinderAssistEffectKey.id).isEqualTo(VIEWFINDER_ASSIST_EFFECT_ID)
        assertThat(ViewfinderAssistEffectKey.id.value).isEqualTo("viewfinder_assist")
        assertThat(ViewfinderAssistEffectKey.id).isNotEqualTo(SingleStreamEffectKey.id)
    }

    // ---- ViewfinderAssistEffectConfig.from --------------------------------------------------

    @Test
    fun configFrom_nothingEnabled_returnsNull() {
        assertThat(ViewfinderAssistEffectConfig.from(ViewfinderAssistSettings.DEFAULT)).isNull()
        assertThat(
            ViewfinderAssistEffectConfig.from(
                ViewfinderAssistSettings(isHistogramEnabled = true, isLevelEnabled = true)
            )
        ).isNull()
    }

    @Test
    fun configFrom_peakingOnly() {
        val config = ViewfinderAssistEffectConfig.from(
            ViewfinderAssistSettings(isFocusPeakingEnabled = true)
        )
        assertThat(config).isNotNull()
        assertThat(config!!.peaking).isEqualTo(FocusPeakingConfig.DEFAULT)
        assertThat(config.zebras).isNull()
    }

    @Test
    fun configFrom_zebrasOnly_usesThresholdPercent() {
        val config = ViewfinderAssistEffectConfig.from(
            ViewfinderAssistSettings(isZebrasEnabled = true, zebraThresholdPercent = 90)
        )
        assertThat(config).isNotNull()
        assertThat(config!!.peaking).isNull()
        assertThat(config.zebras).isNotNull()
        assertThat(config.zebras!!.threshold).isWithin(1e-6f).of(0.90f)
        assertThat(config.zebras.stripePeriodPx).isEqualTo(ZebraConfig.DEFAULT_STRIPE_PERIOD_PX)
    }

    @Test
    fun configFrom_both_enabled() {
        val config = ViewfinderAssistEffectConfig.from(
            ViewfinderAssistSettings(
                isFocusPeakingEnabled = true,
                isZebrasEnabled = true,
                zebraThresholdPercent = 100
            )
        )
        assertThat(config).isNotNull()
        assertThat(config!!.peaking).isEqualTo(FocusPeakingConfig.DEFAULT)
        assertThat(config.zebras!!.threshold).isWithin(1e-6f).of(1f)
    }

    @Test
    fun config_requiresAtLeastOneFeature() {
        assertThrows(IllegalArgumentException::class.java) {
            ViewfinderAssistEffectConfig(peaking = null, zebras = null)
        }
    }

    @Test
    fun providerConfigFor_fallsBackToPeakingWhenNothingEnabled() {
        val config = ViewfinderAssistEffectProvider.configFor(ViewfinderAssistSettings.DEFAULT)
        assertThat(config.peaking).isEqualTo(FocusPeakingConfig.DEFAULT)
        assertThat(config.zebras).isNull()
    }

    @Test
    fun providerConfigFor_honoursSettings() {
        val config = ViewfinderAssistEffectProvider.configFor(
            ViewfinderAssistSettings(isZebrasEnabled = true, zebraThresholdPercent = 95)
        )
        assertThat(config.peaking).isNull()
        assertThat(config.zebras!!.threshold).isWithin(1e-6f).of(0.95f)
    }

    // ---- FocusPeakingConfig -----------------------------------------------------------------

    @Test
    fun focusPeakingConfig_defaultsAreSane() {
        val config = FocusPeakingConfig.DEFAULT
        assertThat(config.colorRgb).hasLength(3)
        config.colorRgb.forEach { assertThat(it).isAtLeast(0f); assertThat(it).isAtMost(1f) }
        assertThat(config.threshold).isEqualTo(FocusPeakingConfig.DEFAULT_THRESHOLD)
        assertThat(config.feather).isEqualTo(FocusPeakingConfig.DEFAULT_FEATHER)
        assertThat(config.threshold + config.feather).isAtMost(1f)
    }

    @Test
    fun focusPeakingConfig_rejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            FocusPeakingConfig(colorRgb = floatArrayOf(1f, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) { FocusPeakingConfig(threshold = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { FocusPeakingConfig(feather = -0.1f) }
    }

    @Test
    fun focusPeakingConfig_equalityIsContentBased() {
        val a = FocusPeakingConfig(colorRgb = floatArrayOf(0.5f, 0.5f, 0.5f))
        val b = FocusPeakingConfig(colorRgb = floatArrayOf(0.5f, 0.5f, 0.5f))
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(FocusPeakingConfig(colorRgb = floatArrayOf(0f, 0f, 0f)))
    }

    // ---- ZebraConfig ------------------------------------------------------------------------

    @Test
    fun zebraConfig_defaultsAreSane() {
        val config = ZebraConfig.DEFAULT
        assertThat(config.threshold).isEqualTo(0.95f)
        assertThat(config.stripePeriodPx).isGreaterThan(0f)
        assertThat(config.stripeDutyCycle).isGreaterThan(0f)
        assertThat(config.stripeDutyCycle).isLessThan(1f)
        assertThat(config.stripeAlpha).isGreaterThan(0f)
        assertThat(config.stripeAlpha).isAtMost(1f)
    }

    @Test
    fun zebraConfig_rejectsInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { ZebraConfig(threshold = 1.01f) }
        assertThrows(IllegalArgumentException::class.java) { ZebraConfig(stripePeriodPx = 0f) }
        assertThrows(IllegalArgumentException::class.java) { ZebraConfig(stripeDutyCycle = 2f) }
        assertThrows(IllegalArgumentException::class.java) { ZebraConfig(stripeAlpha = -1f) }
    }

    // ---- ShaderCopy helpers -----------------------------------------------------------------

    @Test
    fun disabledThreshold_isAboveAnyNormalisedValue() {
        assertThat(ShaderCopy.DISABLED_THRESHOLD).isGreaterThan(1f)
    }

    @Test
    fun zebraPhase_staysWithinPeriod() {
        val period = ZebraConfig.DEFAULT_STRIPE_PERIOD_PX
        val samplesNanos = listOf(0L, 1_000_000L, 500_000_000L, 7_000_000_000L, 999_999_999_999L)
        samplesNanos.forEach { nanos ->
            val phase = ShaderCopy.zebraPhasePx(nanos, period)
            assertThat(phase).isAtLeast(0f)
            assertThat(phase).isLessThan(period)
        }
    }

    @Test
    fun zebraPhase_advancesWithTimeAtCrawlSpeed() {
        val period = 1_000f // large period so no wrap-around happens in one second
        val start = ShaderCopy.zebraPhasePx(0L, period)
        val afterOneSecond = ShaderCopy.zebraPhasePx(1_000_000_000L, period)
        assertThat(afterOneSecond - start).isWithin(0.01f).of(ShaderCopy.ZEBRA_CRAWL_PX_PER_SECOND)
    }
}
