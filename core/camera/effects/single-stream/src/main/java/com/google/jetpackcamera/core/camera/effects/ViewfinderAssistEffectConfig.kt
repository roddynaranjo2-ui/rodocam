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

import com.google.jetpackcamera.model.ViewfinderAssistSettings

/**
 * Parameters of the preview-only viewfinder assist shader. Both features are optional and are
 * drawn in one pass:
 *
 * - **Focus peaking** ([peaking]): 3x3 Sobel gradient of the luma; pixels whose gradient exceeds
 *   the threshold are tinted with the peaking colour.
 * - **Zebras** ([zebras]): pixels whose luma is at or above the threshold are covered with
 *   animated diagonal stripes, like a broadcast monitor.
 *
 * At least one of the two must be set; a config with neither is meaningless (a plain copy).
 */
data class ViewfinderAssistEffectConfig(
    val peaking: FocusPeakingConfig? = null,
    val zebras: ZebraConfig? = null
) {
    init {
        require(peaking != null || zebras != null) {
            "ViewfinderAssistEffectConfig needs focus peaking, zebras or both"
        }
    }

    companion object {
        /**
         * Builds the shader config for [settings], or `null` when the shader effect is not
         * needed ([ViewfinderAssistSettings.needsShaderEffect] is false).
         */
        fun from(settings: ViewfinderAssistSettings): ViewfinderAssistEffectConfig? {
            if (!settings.needsShaderEffect) return null
            return ViewfinderAssistEffectConfig(
                peaking = if (settings.isFocusPeakingEnabled) FocusPeakingConfig.DEFAULT else null,
                zebras = if (settings.isZebrasEnabled) {
                    ZebraConfig(threshold = settings.zebraThresholdPercent / 100f)
                } else {
                    null
                }
            )
        }
    }
}

/**
 * Parameters of the GPU focus peaking pass.
 *
 * @property colorRgb Highlight colour, components in `0..1` (default: Pixel-style amber/red).
 * @property threshold Sobel magnitude (0..1) from which a pixel starts to be highlighted.
 * @property feather Width of the soft transition above [threshold].
 */
data class FocusPeakingConfig(
    val colorRgb: FloatArray = floatArrayOf(1.0f, 0.24f, 0.0f),
    val threshold: Float = DEFAULT_THRESHOLD,
    val feather: Float = DEFAULT_FEATHER
) {
    init {
        require(colorRgb.size == 3) { "colorRgb must have 3 components" }
        require(threshold in 0f..1f) { "threshold must be in 0..1" }
        require(feather in 0f..1f) { "feather must be in 0..1" }
    }

    override fun equals(other: Any?): Boolean = other is FocusPeakingConfig &&
        colorRgb.contentEquals(other.colorRgb) &&
        threshold == other.threshold &&
        feather == other.feather

    override fun hashCode(): Int =
        31 * (31 * colorRgb.contentHashCode() + threshold.hashCode()) + feather.hashCode()

    companion object {
        const val DEFAULT_THRESHOLD = 0.30f
        const val DEFAULT_FEATHER = 0.15f
        val DEFAULT = FocusPeakingConfig()
    }
}

/**
 * Parameters of the per-pixel zebra pass.
 *
 * @property threshold Luma (0..1) from which a pixel is considered clipped and striped.
 * @property stripePeriodPx Distance in output pixels between two stripes.
 * @property stripeDutyCycle Fraction (0..1) of each period that is painted.
 * @property stripeAlpha Opacity of the stripes over the image.
 */
data class ZebraConfig(
    val threshold: Float = DEFAULT_THRESHOLD,
    val stripePeriodPx: Float = DEFAULT_STRIPE_PERIOD_PX,
    val stripeDutyCycle: Float = DEFAULT_STRIPE_DUTY_CYCLE,
    val stripeAlpha: Float = DEFAULT_STRIPE_ALPHA
) {
    init {
        require(threshold in 0f..1f) { "threshold must be in 0..1" }
        require(stripePeriodPx > 0f) { "stripePeriodPx must be positive" }
        require(stripeDutyCycle in 0f..1f) { "stripeDutyCycle must be in 0..1" }
        require(stripeAlpha in 0f..1f) { "stripeAlpha must be in 0..1" }
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.95f
        const val DEFAULT_STRIPE_PERIOD_PX = 14f
        const val DEFAULT_STRIPE_DUTY_CYCLE = 0.5f
        const val DEFAULT_STRIPE_ALPHA = 0.85f
        val DEFAULT = ZebraConfig()
    }
}
