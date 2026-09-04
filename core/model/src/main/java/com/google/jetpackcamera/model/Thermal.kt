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

/**
 * Device thermal status as reported by the platform (`PowerManager.THERMAL_STATUS_*`), in
 * increasing order of severity. [UNKNOWN] is used before the first callback and on devices
 * without thermal reporting (API < 29), and is treated like [NONE].
 */
enum class ThermalStatus {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN;

    /** True from [MODERATE] upwards, i.e. when the app should start shedding load. */
    val isThrottling: Boolean
        get() = this >= MODERATE

    /** True from [SEVERE] upwards, i.e. when the user should be warned explicitly. */
    val isHot: Boolean
        get() = this >= SEVERE

    companion object {
        /**
         * Maps a `PowerManager.THERMAL_STATUS_*` integer (0 = none ... 6 = shutdown) to a
         * [ThermalStatus]; out-of-range values map to [UNKNOWN].
         */
        fun fromPlatformStatus(status: Int): ThermalStatus = when (status) {
            0 -> NONE
            1 -> LIGHT
            2 -> MODERATE
            3 -> SEVERE
            4 -> CRITICAL
            5 -> EMERGENCY
            6 -> SHUTDOWN
            else -> UNKNOWN
        }
    }
}

/**
 * Load-shedding rules derived from the [ThermalStatus]. The policy is applied on top of the
 * user's settings for the duration of the thermal event; persisted settings are never changed,
 * so everything comes back once the device cools down.
 *
 * Tiers (mirroring Pixel Camera behaviour, which first drops preview effects, then frame rate,
 * then recording resolution):
 * - [UNKNOWN]/[NONE]/[LIGHT]: no restriction.
 * - [MODERATE]: GPU viewfinder shader (peaking / zebras) disabled; fps capped at 30.
 * - [SEVERE]: additionally video capped at FHD.
 * - [CRITICAL] and above: video capped at HD and fps capped at 24.
 *
 * @property maxTargetFrameRate Highest fixed frame rate allowed, or `null` for no cap.
 * @property allowShaderEffect Whether the preview GPU assist shader may run.
 * @property maxVideoQuality Highest video quality allowed, or `null` for no cap.
 */
data class ThermalPolicy(
    val status: ThermalStatus,
    val maxTargetFrameRate: Int? = null,
    val allowShaderEffect: Boolean = true,
    val maxVideoQuality: VideoQuality? = null
) {
    /** True when the policy restricts anything. */
    val isRestricting: Boolean
        get() = maxTargetFrameRate != null || !allowShaderEffect || maxVideoQuality != null

    /**
     * Caps [requested] (a `TARGET_FPS_*` value) to [maxTargetFrameRate]. [TARGET_FPS_AUTO] is
     * left untouched (the camera picks). If the cap is not one of [supported] the highest
     * supported rate not above the cap is used, or [TARGET_FPS_AUTO] when none qualifies.
     */
    fun applyTargetFrameRate(requested: Int, supported: Set<Int>): Int {
        val cap = maxTargetFrameRate ?: return requested
        if (requested == TARGET_FPS_AUTO || requested <= cap) return requested
        if (cap in supported) return cap
        return supported.filter { it != TARGET_FPS_AUTO && it <= cap }.maxOrNull()
            ?: TARGET_FPS_AUTO
    }

    /** Caps [requested] to [maxVideoQuality]; [VideoQuality.UNSPECIFIED] is left untouched. */
    fun applyVideoQuality(requested: VideoQuality): VideoQuality {
        val cap = maxVideoQuality ?: return requested
        if (requested == VideoQuality.UNSPECIFIED) return requested
        return if (requested.ordinal > cap.ordinal) cap else requested
    }

    /** Disables the shader-backed assists (peaking, zebras) when [allowShaderEffect] is false. */
    fun applyViewfinderAssist(settings: ViewfinderAssistSettings): ViewfinderAssistSettings =
        if (allowShaderEffect || !settings.needsShaderEffect) {
            settings
        } else {
            settings.copy(isFocusPeakingEnabled = false, isZebrasEnabled = false)
        }

    companion object {
        /** Policy for [status]. */
        fun forStatus(status: ThermalStatus): ThermalPolicy = when (status) {
            ThermalStatus.UNKNOWN,
            ThermalStatus.NONE,
            ThermalStatus.LIGHT -> ThermalPolicy(status)

            ThermalStatus.MODERATE -> ThermalPolicy(
                status = status,
                maxTargetFrameRate = TARGET_FPS_30,
                allowShaderEffect = false
            )

            ThermalStatus.SEVERE -> ThermalPolicy(
                status = status,
                maxTargetFrameRate = TARGET_FPS_30,
                allowShaderEffect = false,
                maxVideoQuality = VideoQuality.FHD
            )

            ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN -> ThermalPolicy(
                status = status,
                maxTargetFrameRate = TARGET_FPS_24,
                allowShaderEffect = false,
                maxVideoQuality = VideoQuality.HD
            )
        }

        val NONE = forStatus(ThermalStatus.NONE)
    }
}
