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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.jetpackcamera.model.ManualControls
import com.google.jetpackcamera.model.WhiteBalanceMode
import com.google.jetpackcamera.model.approximateKelvin
import com.google.jetpackcamera.model.formatShutterSpeed
import com.google.jetpackcamera.ui.controller.ManualControlsController
import com.google.jetpackcamera.ui.uistate.capture.ManualControlsUiState
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/** Which manual control is currently expanded in the Pro panel. */
enum class ProControl { ISO, SHUTTER, EV, SHADOWS, WHITE_BALANCE, FOCUS }

/**
 * Pixel-style "Pro" toggle shown next to the zoom row: a small pill that enables/disables the
 * manual controls panel. Hidden entirely when the lens has no manual controls.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProModeToggle(
    manualControlsUiState: ManualControlsUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available = manualControlsUiState as? ManualControlsUiState.Available ?: return
    val description = stringResource(R.string.pro_mode_toggle_description)
    ToggleButton(
        modifier = modifier
            .height(ButtonDefaults.ExtraSmallContainerHeight)
            .semantics { contentDescription = description }
            .testTag(PRO_MODE_TOGGLE_TAG),
        checked = available.isProModeEnabled,
        onCheckedChange = { onToggle() },
        shapes = ToggleButtonDefaults.shapes(),
        colors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = Color.Black.copy(alpha = 0.32f),
            contentColor = Color.White,
            checkedContainerColor = MaterialTheme.colorScheme.primary,
            checkedContentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.ExtraSmallContainerHeight)
    ) {
        Text(
            text = stringResource(R.string.pro_mode_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Live exposure readout ("ISO 100 · 1/120 · +0.3 EV") shown above the zoom row while Pro mode is
 * on, mirroring the Pixel Pro overlay. Tapping any chip expands its slider.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ManualControlsPanel(
    manualControlsUiState: ManualControlsUiState,
    controller: ManualControlsController?,
    modifier: Modifier = Modifier
) {
    val available = manualControlsUiState as? ManualControlsUiState.Available ?: return
    if (!available.isProModeEnabled) return

    var expanded by rememberSaveable { mutableStateOf<ProControl?>(null) }
    val caps = available.capabilities
    val controls = available.controls

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(PRO_PANEL_TAG),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = expanded != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when (expanded) {
                    ProControl.ISO -> IsoSlider(available, controller)
                    ProControl.SHUTTER -> ShutterSlider(available, controller)
                    ProControl.EV -> ExposureCompensationSlider(available, controller)
                    ProControl.SHADOWS -> ShadowsSlider(available, controller)
                    ProControl.WHITE_BALANCE -> WhiteBalancePanel(available, controller)
                    ProControl.FOCUS -> FocusSlider(available, controller)
                    null -> Unit
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .background(Color.Black.copy(alpha = 0.32f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (caps.supportsManualExposure) {
                ProChip(
                    label = stringResource(R.string.pro_iso_label),
                    value = available.displayIso?.toString() ?: "—",
                    isManual = controls.iso != null,
                    selected = expanded == ProControl.ISO,
                    enabled = !available.isRecording,
                    testTag = PRO_CHIP_ISO_TAG
                ) { expanded = if (expanded == ProControl.ISO) null else ProControl.ISO }
                ProChip(
                    label = stringResource(R.string.pro_shutter_label),
                    value = available.displayExposureTimeNanos?.let(::formatShutterSpeed) ?: "—",
                    isManual = controls.exposureTimeNanos != null,
                    selected = expanded == ProControl.SHUTTER,
                    enabled = !available.isRecording,
                    testTag = PRO_CHIP_SHUTTER_TAG
                ) { expanded = if (expanded == ProControl.SHUTTER) null else ProControl.SHUTTER }
            }
            if (caps.supportsExposureCompensation) {
                ProChip(
                    label = stringResource(R.string.pro_ev_label),
                    value = formatEv(available.displayExposureCompensationEv),
                    isManual = (controls.exposureCompensationIndex ?: 0) != 0,
                    selected = expanded == ProControl.EV,
                    enabled = !controls.isManualExposure,
                    testTag = PRO_CHIP_EV_TAG
                ) { expanded = if (expanded == ProControl.EV) null else ProControl.EV }
            }
            if (caps.supportsShadowsBoost) {
                ProChip(
                    label = stringResource(R.string.pro_shadows_label),
                    value = formatShadows(controls.shadowsBoost),
                    isManual = controls.isShadowsAdjusted,
                    selected = expanded == ProControl.SHADOWS,
                    enabled = true,
                    testTag = PRO_CHIP_SHADOWS_TAG
                ) { expanded = if (expanded == ProControl.SHADOWS) null else ProControl.SHADOWS }
            }
            if (caps.supportsManualWhiteBalance) {
                ProChip(
                    label = stringResource(R.string.pro_wb_label),
                    value = controls.whiteBalanceKelvin?.let(::formatKelvin)
                        ?: stringResource(controls.whiteBalance.labelRes()),
                    isManual = controls.isManualWhiteBalance,
                    selected = expanded == ProControl.WHITE_BALANCE,
                    enabled = true,
                    testTag = PRO_CHIP_WB_TAG
                ) {
                    expanded = if (expanded == ProControl.WHITE_BALANCE) {
                        null
                    } else {
                        ProControl.WHITE_BALANCE
                    }
                }
            }
            if (caps.supportsManualFocus) {
                ProChip(
                    label = stringResource(R.string.pro_focus_label),
                    value = controls.focusDistanceDiopters
                        ?.let { formatFocus(it, caps.minimumFocusDistanceDiopters) }
                        ?: stringResource(R.string.pro_value_auto),
                    isManual = controls.isManualFocus,
                    selected = expanded == ProControl.FOCUS,
                    enabled = true,
                    testTag = PRO_CHIP_FOCUS_TAG
                ) { expanded = if (expanded == ProControl.FOCUS) null else ProControl.FOCUS }
            }
            if (caps.isAeLockSupported) {
                ProChip(
                    label = stringResource(R.string.pro_ae_lock_label),
                    value = if (controls.aeLock) {
                        stringResource(R.string.pro_value_locked)
                    } else {
                        stringResource(R.string.pro_value_unlocked)
                    },
                    isManual = controls.aeLock,
                    selected = false,
                    enabled = !controls.isManualExposure && !available.isRecording,
                    testTag = PRO_CHIP_AE_LOCK_TAG
                ) { controller?.setAeLock(!controls.aeLock) }
            }
            if (controls.hasOverrides) {
                TextButton(
                    onClick = {
                        controller?.resetToAuto()
                        expanded = null
                    },
                    modifier = Modifier.testTag(PRO_RESET_TAG),
                    contentPadding = ButtonDefaults.contentPaddingFor(
                        ButtonDefaults.ExtraSmallContainerHeight
                    )
                ) {
                    Text(
                        text = stringResource(R.string.pro_reset_label),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ProChip(
    label: String,
    value: String,
    isManual: Boolean,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val stateText = stringResource(
        if (isManual) R.string.pro_state_manual else R.string.pro_value_auto
    )
    FilterChip(
        modifier = Modifier
            .testTag(testTag)
            .semantics { stateDescription = stateText },
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isManual) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    }
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = Color.White.copy(alpha = 0.18f),
            labelColor = Color.White,
            selectedLabelColor = Color.White
        ),
        border = null
    )
}

// ---------------------------------------------------------------------------------------------
// Sliders
// ---------------------------------------------------------------------------------------------

/**
 * Debounces slider drags so we do not flood Camera2 with a request per pixel. The last value is
 * always delivered.
 */
@Composable
private fun rememberDebouncedSetter(
    delayMillis: Long = 40L,
    setter: (Float) -> Unit
): (Float) -> Unit {
    val latestSetter by rememberUpdatedState(setter)
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pending) {
        val value = pending ?: return@LaunchedEffect
        delay(delayMillis)
        latestSetter(value)
    }
    return { value -> pending = value }
}

/**
 * Slider position that follows an external (camera-state derived) value **only while the user is
 * not dragging**. Without this the snapped value echoed back by the controller ~40 ms after each
 * drag event would yank the thumb away from the finger.
 */
@Stable
private class SliderSync(initialPosition: Float) {
    var position by mutableFloatStateOf(initialPosition)
    var isDragging by mutableStateOf(false)
}

@Composable
private fun rememberSliderSync(externalPosition: Float): SliderSync {
    val sync = remember { SliderSync(externalPosition) }
    LaunchedEffect(externalPosition) {
        if (!sync.isDragging) sync.position = externalPosition
    }
    return sync
}

/**
 * Shared [Slider] wiring: test tag, TalkBack description and drag tracking for [SliderSync].
 */
@Composable
private fun ProSlider(
    sync: SliderSync,
    testTag: String,
    contentDescription: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    Slider(
        modifier = Modifier
            .testTag(testTag)
            .semantics { this.contentDescription = contentDescription },
        value = sync.position,
        valueRange = valueRange,
        steps = steps,
        onValueChange = {
            sync.isDragging = true
            sync.position = it
            onValueChange(it)
        },
        onValueChangeFinished = { sync.isDragging = false }
    )
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    isManual: Boolean,
    onAuto: () -> Unit,
    slider: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = valueText,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAuto, enabled = isManual) {
                Text(
                    text = stringResource(R.string.pro_value_auto),
                    color = if (isManual) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.4f)
                    }
                )
            }
        }
        slider()
    }
}

@Composable
private fun IsoSlider(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val range = state.capabilities.isoRange ?: return
    // Logarithmic mapping: ISO doubles feel linear to photographers.
    val minLog = ln(range.first.toFloat())
    val maxLog = ln(range.last.toFloat())
    val current = state.displayIso ?: range.first
    val sync = rememberSliderSync(
        ((ln(current.toFloat()) - minLog) / (maxLog - minLog)).coerceIn(0f, 1f)
    )
    val apply = rememberDebouncedSetter { pos ->
        val iso = exp(minLog + pos * (maxLog - minLog)).roundToInt()
        controller?.setIso(snapIso(iso).coerceIn(range.first, range.last))
    }
    val title = stringResource(R.string.pro_iso_label)
    SliderRow(
        title = title,
        valueText = state.displayIso?.toString() ?: "—",
        isManual = state.controls.iso != null,
        onAuto = { controller?.setIso(null) }
    ) {
        ProSlider(
            sync = sync,
            testTag = PRO_SLIDER_ISO_TAG,
            contentDescription = title,
            onValueChange = apply
        )
    }
}

@Composable
private fun ShutterSlider(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val range = state.capabilities.exposureTimeRangeNanos ?: return
    // Clamp the UI range to something usable in a viewfinder (1/8000 .. 1s); HAL may allow more.
    val lo = range.first.coerceAtLeast(125_000L)
    val hi = range.last.coerceAtMost(1_000_000_000L).coerceAtLeast(lo + 1)
    val minLog = ln(lo.toDouble())
    val maxLog = ln(hi.toDouble())
    val current = (state.displayExposureTimeNanos ?: lo).coerceIn(lo, hi)
    val sync = rememberSliderSync(
        ((ln(current.toDouble()) - minLog) / (maxLog - minLog)).toFloat().coerceIn(0f, 1f)
    )
    val apply = rememberDebouncedSetter { pos ->
        val nanos = exp(minLog + pos * (maxLog - minLog)).toLong()
        controller?.setExposureTimeNanos(snapShutter(nanos).coerceIn(lo, hi))
    }
    val title = stringResource(R.string.pro_shutter_label)
    SliderRow(
        title = title,
        valueText = state.displayExposureTimeNanos?.let(::formatShutterSpeed) ?: "—",
        isManual = state.controls.exposureTimeNanos != null,
        onAuto = { controller?.setExposureTimeNanos(null) }
    ) {
        ProSlider(
            sync = sync,
            testTag = PRO_SLIDER_SHUTTER_TAG,
            contentDescription = title,
            onValueChange = apply
        )
    }
}

@Composable
private fun ExposureCompensationSlider(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val range = state.capabilities.exposureCompensationRange ?: return
    val current = state.controls.exposureCompensationIndex ?: 0
    val sync = rememberSliderSync(current.toFloat())
    val apply = rememberDebouncedSetter { pos ->
        controller?.setExposureCompensationIndex(pos.roundToInt())
    }
    val title = stringResource(R.string.pro_ev_label)
    SliderRow(
        title = title,
        valueText = formatEv(
            sync.position.roundToInt() * state.capabilities.exposureCompensationStep
        ),
        isManual = current != 0,
        onAuto = { controller?.setExposureCompensationIndex(null) }
    ) {
        ProSlider(
            sync = sync,
            testTag = PRO_SLIDER_EV_TAG,
            contentDescription = title,
            onValueChange = apply,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
}

@Composable
private fun FocusSlider(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val maxDiopters = state.capabilities.minimumFocusDistanceDiopters
    if (maxDiopters <= 0f) return
    // Slider 0 = infinity (0 diopters), 1 = closest (maxDiopters). Square mapping gives finer
    // control near infinity where landscapes/portraits live.
    val current = state.controls.focusDistanceDiopters
        ?: state.exposureInfo.focusDistanceDiopters
        ?: 0f
    val sync = rememberSliderSync(sqrt((current / maxDiopters).coerceIn(0f, 1f)))
    val apply = rememberDebouncedSetter { pos ->
        controller?.setFocusDistance((pos.pow(2) * maxDiopters).coerceIn(0f, maxDiopters))
    }
    val title = stringResource(R.string.pro_focus_label)
    SliderRow(
        title = title,
        valueText = formatFocus(sync.position.pow(2) * maxDiopters, maxDiopters),
        isManual = state.controls.focusDistanceDiopters != null,
        onAuto = { controller?.setFocusDistance(null) }
    ) {
        ProSlider(
            sync = sync,
            testTag = PRO_SLIDER_FOCUS_TAG,
            contentDescription = title,
            onValueChange = apply
        )
    }
}

/**
 * Dual Exposure "shadows" slider: -1 (deep shadows) … 0 (default) … +1 (lifted shadows).
 * Snaps to 0 near the centre so the neutral position is easy to hit.
 */
@Composable
private fun ShadowsSlider(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val current = state.controls.shadowsBoost ?: 0f
    val sync = rememberSliderSync(current)
    val apply = rememberDebouncedSetter { pos ->
        controller?.setShadowsBoost(snapShadows(pos))
    }
    val title = stringResource(R.string.pro_shadows_label)
    SliderRow(
        title = title,
        valueText = formatShadows(snapShadows(sync.position)),
        isManual = state.controls.isShadowsAdjusted,
        onAuto = { controller?.setShadowsBoost(null) }
    ) {
        ProSlider(
            sync = sync,
            testTag = PRO_SLIDER_SHADOWS_TAG,
            contentDescription = title,
            onValueChange = apply,
            valueRange = ManualControls.SHADOWS_RANGE
        )
    }
}

/**
 * White balance panel: preset chips (Auto, Daylight, …) plus a "K" chip that reveals a kelvin
 * slider when the lens supports manual colour correction, like Pixel's Pro white balance.
 */
@Composable
private fun WhiteBalancePanel(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val caps = state.capabilities
    val modes = remember(caps.supportedWhiteBalanceModes) {
        WhiteBalanceMode.entries.filter { it in caps.supportedWhiteBalanceModes }
    }
    val controls = state.controls
    val isKelvin = controls.isKelvinWhiteBalance
    val selectedPreset = controls.whiteBalance ?: WhiteBalanceMode.AUTO
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            modes.forEach { mode ->
                WhiteBalanceChip(
                    label = stringResource(mode.labelRes()),
                    selected = !isKelvin && mode == selectedPreset,
                    testTag = "$PRO_WB_CHIP_PREFIX${mode.name}"
                ) { controller?.setWhiteBalance(mode) }
            }
            if (caps.supportsWhiteBalanceKelvin) {
                WhiteBalanceChip(
                    label = stringResource(R.string.pro_wb_kelvin_label),
                    selected = isKelvin,
                    testTag = PRO_CHIP_WB_KELVIN_TAG
                ) {
                    if (!isKelvin) {
                        controller?.setWhiteBalanceKelvin(selectedPreset.approximateKelvin())
                    }
                }
            }
        }
        if (isKelvin && caps.supportsWhiteBalanceKelvin) {
            Spacer(Modifier.height(4.dp))
            KelvinSlider(state, controller)
        }
    }
}

@Composable
private fun WhiteBalanceChip(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    FilterChip(
        modifier = Modifier.testTag(testTag),
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun KelvinSlider(
    state: ManualControlsUiState.Available,
    controller: ManualControlsController?
) {
    val range = ManualControls.WHITE_BALANCE_KELVIN_RANGE
    val step = ManualControls.WHITE_BALANCE_KELVIN_STEP
    val current = state.controls.whiteBalanceKelvin ?: ManualControls.WHITE_BALANCE_KELVIN_NEUTRAL
    val sync = rememberSliderSync(current.toFloat())
    val apply = rememberDebouncedSetter { pos ->
        controller?.setWhiteBalanceKelvin(snapKelvin(pos, step).coerceIn(range))
    }
    val title = stringResource(R.string.pro_wb_label)
    SliderRow(
        title = title,
        valueText = formatKelvin(snapKelvin(sync.position, step)),
        isManual = true,
        onAuto = { controller?.setWhiteBalance(null) }
    ) {
        ProSlider(
            sync = sync,
            testTag = PRO_SLIDER_WB_KELVIN_TAG,
            contentDescription = title,
            onValueChange = apply,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------------------------

private fun WhiteBalanceMode?.labelRes(): Int = when (this) {
    null, WhiteBalanceMode.AUTO -> R.string.pro_wb_auto
    WhiteBalanceMode.INCANDESCENT -> R.string.pro_wb_incandescent
    WhiteBalanceMode.FLUORESCENT -> R.string.pro_wb_fluorescent
    WhiteBalanceMode.WARM_FLUORESCENT -> R.string.pro_wb_warm_fluorescent
    WhiteBalanceMode.DAYLIGHT -> R.string.pro_wb_daylight
    WhiteBalanceMode.CLOUDY_DAYLIGHT -> R.string.pro_wb_cloudy
    WhiteBalanceMode.TWILIGHT -> R.string.pro_wb_twilight
    WhiteBalanceMode.SHADE -> R.string.pro_wb_shade
}
