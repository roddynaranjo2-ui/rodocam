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
 * White balance presets. Mirrors the Camera2 `CONTROL_AWB_MODE_*` values but is kept as a pure
 * Kotlin model so that it can be persisted and used from UI layers without Android dependencies.
 */
enum class WhiteBalanceMode {
    AUTO,
    INCANDESCENT,
    FLUORESCENT,
    WARM_FLUORESCENT,
    DAYLIGHT,
    CLOUDY_DAYLIGHT,
    TWILIGHT,
    SHADE
}

/**
 * Snapshot of all "Pro" (manual) controls that the user may override.
 *
 * Every field is nullable: `null` means *automatic* (the camera HAL decides). A non-null value
 * pins that parameter. This mirrors how Pixel's Pro controls work: each control can be switched
 * between AUTO and a fixed value independently.
 *
 * ISO and shutter speed are coupled: when either one is set manually, the AE algorithm is turned
 * off and both must be resolved to concrete values. [resolvedIso]/[resolvedExposureTimeNanos]
 * help callers by falling back to the last automatic readout published by the camera.
 *
 * @property iso Sensor sensitivity (ISO). `null` = auto.
 * @property exposureTimeNanos Shutter speed in nanoseconds. `null` = auto.
 * @property exposureCompensationIndex Exposure compensation in *steps* (index), interpreted with
 *   the device's EV step (e.g. 1/3 EV). Ignored when ISO/shutter are manual. `null` = 0.
 * @property whiteBalance White balance preset. `null`/[WhiteBalanceMode.AUTO] = auto.
 * @property whiteBalanceKelvin Manual colour temperature in kelvin. When set it takes precedence
 *   over [whiteBalance]: AWB is switched off and per-channel gains derived from the temperature
 *   are applied (see [kelvinToRggbGains]). `null` = use [whiteBalance]/auto.
 * @property focusDistanceDiopters Manual focus distance in diopters (0 = infinity,
 *   `minimumFocusDistance` = closest). `null` = autofocus.
 * @property shadowsBoost Pixel-style "Dual Exposure" shadows control in `-1f..1f`. Positive
 *   values lift shadows, negative values deepen them, `0f`/`null` = HAL default tone mapping.
 *   The "brightness" half of Dual Exposure is [exposureCompensationIndex].
 * @property aeLock Whether auto-exposure is locked (long-press on viewfinder on Pixel).
 * @property awbLock Whether auto-white-balance is locked.
 */
data class ManualControls(
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val exposureCompensationIndex: Int? = null,
    val whiteBalance: WhiteBalanceMode? = null,
    val whiteBalanceKelvin: Int? = null,
    val focusDistanceDiopters: Float? = null,
    val shadowsBoost: Float? = null,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false
) {
    /** True when exposure (ISO and/or shutter) is pinned by the user. */
    val isManualExposure: Boolean
        get() = iso != null || exposureTimeNanos != null

    /** True when white balance is pinned (kelvin value or a preset other than AUTO). */
    val isManualWhiteBalance: Boolean
        get() = whiteBalanceKelvin != null ||
            (whiteBalance != null && whiteBalance != WhiteBalanceMode.AUTO)

    /** True when white balance is driven by an explicit colour temperature. */
    val isKelvinWhiteBalance: Boolean
        get() = whiteBalanceKelvin != null

    /** True when the shadows tone curve differs from the HAL default. */
    val isShadowsAdjusted: Boolean
        get() = shadowsBoost != null && shadowsBoost != 0f

    /** True when the focus distance is pinned. */
    val isManualFocus: Boolean
        get() = focusDistanceDiopters != null

    /** True when every control is left to the HAL, i.e. equals [AUTO]. */
    val isFullyAuto: Boolean
        get() = this == AUTO

    /** True when at least one control differs from automatic operation. */
    val hasOverrides: Boolean
        get() = !isFullyAuto

    /**
     * Resolves the ISO to apply given the last auto-exposure readout.
     * Returns `null` if nothing can be resolved (exposure fully auto).
     */
    fun resolvedIso(lastAutoIso: Int?): Int? = when {
        !isManualExposure -> null
        iso != null -> iso
        else -> lastAutoIso
    }

    /**
     * Resolves the exposure time to apply given the last auto-exposure readout.
     * Returns `null` if nothing can be resolved (exposure fully auto).
     */
    fun resolvedExposureTimeNanos(lastAutoExposureTimeNanos: Long?): Long? = when {
        !isManualExposure -> null
        exposureTimeNanos != null -> exposureTimeNanos
        else -> lastAutoExposureTimeNanos
    }

    companion object {
        /** Everything automatic. */
        val AUTO = ManualControls()

        /** Colour temperatures the kelvin white balance slider may select. */
        val WHITE_BALANCE_KELVIN_RANGE: IntRange = 2000..10000

        /** Neutral colour temperature (D65 daylight), used as the slider default. */
        const val WHITE_BALANCE_KELVIN_NEUTRAL: Int = 6500

        /** Step used by the kelvin slider. */
        const val WHITE_BALANCE_KELVIN_STEP: Int = 100

        /** Allowed range of [shadowsBoost]. */
        val SHADOWS_RANGE: ClosedFloatingPointRange<Float> = -1f..1f
    }
}

/**
 * Capabilities of a lens with respect to manual controls, read from Camera2 characteristics.
 *
 * Ranges are inclusive. A `null` range means the parameter cannot be controlled on this lens.
 *
 * @property isoRange Supported `SENSOR_SENSITIVITY` range.
 * @property exposureTimeRangeNanos Supported `SENSOR_EXPOSURE_TIME` range in nanoseconds.
 * @property exposureCompensationRange Supported `CONTROL_AE_COMPENSATION_RANGE` (in steps).
 * @property exposureCompensationStep Value of one EV step (e.g. 0.333f for 1/3 EV).
 * @property minimumFocusDistanceDiopters `LENS_INFO_MINIMUM_FOCUS_DISTANCE`; 0 means fixed focus.
 * @property supportedWhiteBalanceModes Supported [WhiteBalanceMode] presets.
 * @property isManualSensorSupported `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`.
 * @property isAeLockSupported `CONTROL_AE_LOCK_AVAILABLE`.
 * @property isAwbLockSupported `CONTROL_AWB_LOCK_AVAILABLE`.
 * @property isRawSupported `REQUEST_AVAILABLE_CAPABILITIES_RAW`.
 * @property isZslSupported Whether zero-shutter-lag capture is supported.
 * @property isManualPostProcessingSupported
 *   `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING` (colour correction + tone mapping).
 * @property isAwbOffSupported Whether `CONTROL_AWB_MODE_OFF` is in `CONTROL_AWB_AVAILABLE_MODES`.
 * @property isTonemapCurveSupported Whether `TONEMAP_MODE_CONTRAST_CURVE` is available.
 * @property maxTonemapCurvePoints `TONEMAP_MAX_CURVE_POINTS` (0 when unknown).
 */
data class ManualCapabilities(
    val isoRange: IntRange? = null,
    val exposureTimeRangeNanos: LongRange? = null,
    val exposureCompensationRange: IntRange? = null,
    val exposureCompensationStep: Float = 0f,
    val minimumFocusDistanceDiopters: Float = 0f,
    val supportedWhiteBalanceModes: Set<WhiteBalanceMode> = setOf(WhiteBalanceMode.AUTO),
    val isManualSensorSupported: Boolean = false,
    val isAeLockSupported: Boolean = false,
    val isAwbLockSupported: Boolean = false,
    val isRawSupported: Boolean = false,
    val isZslSupported: Boolean = false,
    val isManualPostProcessingSupported: Boolean = false,
    val isAwbOffSupported: Boolean = false,
    val isTonemapCurveSupported: Boolean = false,
    val maxTonemapCurvePoints: Int = 0
) {
    /** Manual exposure requires the MANUAL_SENSOR capability and both ranges. */
    val supportsManualExposure: Boolean
        get() = isManualSensorSupported && isoRange != null && exposureTimeRangeNanos != null

    /** Exposure compensation requires a non-degenerate range. */
    val supportsExposureCompensation: Boolean
        get() = exposureCompensationRange != null &&
            exposureCompensationRange.first != exposureCompensationRange.last

    /** Manual focus needs a minimum focus distance > 0 (fixed-focus lenses report 0). */
    val supportsManualFocus: Boolean
        get() = minimumFocusDistanceDiopters > 0f

    /** Manual white balance requires at least one non-AUTO preset or kelvin support. */
    val supportsManualWhiteBalance: Boolean
        get() = supportsWhiteBalanceKelvin ||
            supportedWhiteBalanceModes.any { it != WhiteBalanceMode.AUTO }

    /**
     * Kelvin white balance needs AWB off plus manual post-processing so that
     * `COLOR_CORRECTION_GAINS` are honoured by the HAL.
     */
    val supportsWhiteBalanceKelvin: Boolean
        get() = isManualPostProcessingSupported && isAwbOffSupported

    /** Shadows (Dual Exposure) needs a contrast curve with at least two control points. */
    val supportsShadowsBoost: Boolean
        get() = isManualPostProcessingSupported && isTonemapCurveSupported &&
            maxTonemapCurvePoints >= MIN_TONEMAP_CURVE_POINTS

    /** True when the lens exposes at least one manual control. */
    val supportsAnyManualControl: Boolean
        get() = supportsManualExposure || supportsExposureCompensation ||
            supportsManualFocus || supportsManualWhiteBalance || supportsShadowsBoost ||
            isAeLockSupported || isAwbLockSupported

    /**
     * Clamps [controls] to this lens' capabilities, dropping unsupported controls so that a
     * setting persisted for one lens never produces an illegal capture request on another.
     */
    fun sanitize(controls: ManualControls): ManualControls = ManualControls(
        iso = controls.iso?.takeIf { supportsManualExposure }
            ?.coerceIn(isoRange!!.first, isoRange.last),
        exposureTimeNanos = controls.exposureTimeNanos?.takeIf { supportsManualExposure }
            ?.coerceIn(exposureTimeRangeNanos!!.first, exposureTimeRangeNanos.last),
        exposureCompensationIndex = controls.exposureCompensationIndex
            ?.takeIf { supportsExposureCompensation }
            ?.coerceIn(exposureCompensationRange!!.first, exposureCompensationRange.last),
        whiteBalance = controls.whiteBalance?.takeIf { it in supportedWhiteBalanceModes },
        whiteBalanceKelvin = controls.whiteBalanceKelvin?.takeIf { supportsWhiteBalanceKelvin }
            ?.coerceIn(ManualControls.WHITE_BALANCE_KELVIN_RANGE),
        focusDistanceDiopters = controls.focusDistanceDiopters?.takeIf { supportsManualFocus }
            ?.coerceIn(0f, minimumFocusDistanceDiopters),
        shadowsBoost = controls.shadowsBoost?.takeIf { supportsShadowsBoost }
            ?.coerceIn(ManualControls.SHADOWS_RANGE)
            ?.takeIf { it != 0f },
        aeLock = controls.aeLock && isAeLockSupported,
        awbLock = controls.awbLock && isAwbLockSupported
    )

    companion object {
        /** No manual control at all (e.g. legacy HAL or external camera). */
        val NONE = ManualCapabilities()

        /** Smallest tone curve we are willing to build (start and end point). */
        const val MIN_TONEMAP_CURVE_POINTS: Int = 2
    }
}

/**
 * Real-time exposure readout published by the camera pipeline from `TotalCaptureResult`.
 *
 * Used by the Pro UI to display the current ISO / shutter chosen by AE (or confirm the manual
 * values actually applied by the HAL), exactly like Pixel's Pro overlay.
 *
 * @property iso Current `SENSOR_SENSITIVITY`, or null if not reported.
 * @property exposureTimeNanos Current `SENSOR_EXPOSURE_TIME`, or null if not reported.
 * @property focusDistanceDiopters Current `LENS_FOCUS_DISTANCE`, or null if not reported.
 * @property isAeLocked Current `CONTROL_AE_LOCK` state.
 * @property isAwbLocked Current `CONTROL_AWB_LOCK` state.
 * @property apertureFNumber Current `LENS_APERTURE` (f-number), or null if not reported. Used
 *   together with ISO and shutter to estimate scene brightness ([SceneBrightness.ev100]).
 */
data class ExposureInfo(
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val isAeLocked: Boolean = false,
    val isAwbLocked: Boolean = false,
    val apertureFNumber: Float? = null
) {
    companion object {
        val UNKNOWN = ExposureInfo()
    }
}

/**
 * Formats an exposure time in nanoseconds as a human readable shutter speed string:
 * `1/250`, `1/8`, `0.5"`, `2"`.
 */
fun formatShutterSpeed(exposureTimeNanos: Long): String {
    val seconds = exposureTimeNanos / 1_000_000_000.0
    return when {
        seconds <= 0.0 -> "—"
        seconds >= 1.0 -> {
            val rounded = (seconds * 10).toLong() / 10.0
            if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}\"" else "$rounded\""
        }
        seconds >= 0.3 -> {
            val rounded = (seconds * 10).toLong() / 10.0
            "$rounded\""
        }
        else -> "1/${(1.0 / seconds).toLong()}"
    }
}
