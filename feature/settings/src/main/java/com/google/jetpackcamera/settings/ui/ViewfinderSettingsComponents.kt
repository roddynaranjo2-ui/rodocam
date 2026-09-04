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
package com.google.jetpackcamera.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.jetpackcamera.model.CompositionGrid
import com.google.jetpackcamera.model.ViewfinderAssistSettings
import com.google.jetpackcamera.settings.R
import com.google.jetpackcamera.settings.ViewfinderAssistUiState
import kotlin.math.roundToInt

/** Popup setting to choose the composition grid drawn over the viewfinder. */
@Composable
fun CompositionGridSetting(
    uiState: ViewfinderAssistUiState,
    setGrid: (CompositionGrid) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = uiState is ViewfinderAssistUiState.Enabled
    val current = (uiState as? ViewfinderAssistUiState.Enabled)?.settings?.grid
        ?: CompositionGrid.OFF
    BasicPopupSetting(
        modifier = modifier.testTag(BTN_OPEN_DIALOG_SETTING_GRID_TAG),
        title = stringResource(R.string.viewfinder_grid_title),
        leadingIcon = null,
        enabled = enabled,
        description = stringResource(getGridStringRes(current)),
        popupContents = {
            Column(Modifier.selectableGroup()) {
                CompositionGrid.entries.forEach { grid ->
                    SingleChoiceSelector(
                        modifier = Modifier.testTag(
                            BTN_DIALOG_GRID_OPTION_PREFIX + grid.name.lowercase()
                        ),
                        text = stringResource(getGridStringRes(grid)),
                        secondaryText = stringResource(getGridSecondaryStringRes(grid)),
                        selected = current == grid,
                        enabled = enabled,
                        onClick = { setGrid(grid) }
                    )
                }
            }
        }
    )
}

private fun getGridStringRes(grid: CompositionGrid): Int = when (grid) {
    CompositionGrid.OFF -> R.string.viewfinder_grid_value_off
    CompositionGrid.THIRDS -> R.string.viewfinder_grid_value_thirds
    CompositionGrid.FOURTHS -> R.string.viewfinder_grid_value_fourths
    CompositionGrid.GOLDEN_RATIO -> R.string.viewfinder_grid_value_golden
    CompositionGrid.DIAGONALS -> R.string.viewfinder_grid_value_diagonals
    CompositionGrid.CENTER -> R.string.viewfinder_grid_value_center
}

private fun getGridSecondaryStringRes(grid: CompositionGrid): Int = when (grid) {
    CompositionGrid.OFF -> R.string.viewfinder_grid_desc_off
    CompositionGrid.THIRDS -> R.string.viewfinder_grid_desc_thirds
    CompositionGrid.FOURTHS -> R.string.viewfinder_grid_desc_fourths
    CompositionGrid.GOLDEN_RATIO -> R.string.viewfinder_grid_desc_golden
    CompositionGrid.DIAGONALS -> R.string.viewfinder_grid_desc_diagonals
    CompositionGrid.CENTER -> R.string.viewfinder_grid_desc_center
}

/** Switch: horizon level indicator. */
@Composable
fun HorizonLevelSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    SwitchSettingUI(
        modifier = modifier.testTag(BTN_SWITCH_SETTING_LEVEL_TAG),
        title = stringResource(R.string.viewfinder_level_title),
        description = stringResource(R.string.viewfinder_level_description),
        leadingIcon = null,
        onSwitchChanged = setEnabled,
        settingValue = settings?.isLevelEnabled ?: false,
        enabled = settings != null
    )
}

/** Switch: luma histogram overlay. */
@Composable
fun HistogramSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    SwitchSettingUI(
        modifier = modifier.testTag(BTN_SWITCH_SETTING_HISTOGRAM_TAG),
        title = stringResource(R.string.viewfinder_histogram_title),
        description = stringResource(R.string.viewfinder_histogram_description),
        leadingIcon = null,
        onSwitchChanged = setEnabled,
        settingValue = settings?.isHistogramEnabled ?: false,
        enabled = settings != null
    )
}

/**
 * Popup: zebra (clipped highlights) warning with an on/off switch and a threshold slider.
 * The slider commits on release so the DataStore is not hammered while dragging.
 */
@Composable
fun ZebrasSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    setThreshold: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    val enabled = settings != null
    val isOn = settings?.isZebrasEnabled ?: false
    val threshold = settings?.zebraThresholdPercent
        ?: ViewfinderAssistSettings.DEFAULT.zebraThresholdPercent
    BasicPopupSetting(
        modifier = modifier.testTag(BTN_OPEN_DIALOG_SETTING_ZEBRAS_TAG),
        title = stringResource(R.string.viewfinder_zebras_title),
        leadingIcon = null,
        enabled = enabled,
        description = if (isOn) {
            stringResource(R.string.viewfinder_zebras_value_on, threshold)
        } else {
            stringResource(R.string.viewfinder_zebras_value_off)
        },
        popupContents = {
            Column {
                SwitchSettingUI(
                    modifier = Modifier.testTag(BTN_SWITCH_SETTING_ZEBRAS_TAG),
                    title = stringResource(R.string.viewfinder_zebras_switch_title),
                    description = stringResource(R.string.viewfinder_zebras_description),
                    leadingIcon = null,
                    onSwitchChanged = setEnabled,
                    settingValue = isOn,
                    enabled = enabled
                )
                ZebraThresholdSlider(
                    threshold = threshold,
                    enabled = enabled && isOn,
                    onCommit = setThreshold
                )
            }
        }
    )
}

@Composable
private fun ZebraThresholdSlider(
    threshold: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var pending by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
    val min = ViewfinderAssistSettings.MIN_ZEBRA_THRESHOLD_PERCENT
    val max = ViewfinderAssistSettings.MAX_ZEBRA_THRESHOLD_PERCENT
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(
                R.string.viewfinder_zebras_threshold_label,
                pending.roundToInt()
            )
        )
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SLIDER_SETTING_ZEBRA_THRESHOLD_TAG),
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = { onCommit(pending.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            enabled = enabled
        )
    }
}

/** Switch: haptic feedback on shutter, lens flip, recording and level. */
@Composable
fun HapticsSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    SwitchSettingUI(
        modifier = modifier.testTag(BTN_SWITCH_SETTING_HAPTICS_TAG),
        title = stringResource(R.string.viewfinder_haptics_title),
        description = stringResource(R.string.viewfinder_haptics_description),
        leadingIcon = null,
        onSwitchChanged = setEnabled,
        settingValue = settings?.isHapticsEnabled ?: true,
        enabled = settings != null
    )
}

/** Switch: Camera Coach contextual hints (exposure, tilt, low light, digital zoom). */
@Composable
fun CoachSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    SwitchSettingUI(
        modifier = modifier.testTag(BTN_SWITCH_SETTING_COACH_TAG),
        title = stringResource(R.string.viewfinder_coach_title),
        description = stringResource(R.string.viewfinder_coach_description),
        leadingIcon = null,
        onSwitchChanged = setEnabled,
        settingValue = settings?.isCoachEnabled ?: false,
        enabled = settings != null
    )
}

/** Switch: GPU focus peaking on the preview. */
@Composable
fun FocusPeakingSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    SwitchSettingUI(
        modifier = modifier.testTag(BTN_SWITCH_SETTING_FOCUS_PEAKING_TAG),
        title = stringResource(R.string.viewfinder_focus_peaking_title),
        description = stringResource(R.string.viewfinder_focus_peaking_description),
        leadingIcon = null,
        onSwitchChanged = setEnabled,
        settingValue = settings?.isFocusPeakingEnabled ?: false,
        enabled = settings != null
    )
}

/** Switch: Top Shot sharpness badge and blurry-capture warning. */
@Composable
fun TopShotSetting(
    uiState: ViewfinderAssistUiState,
    setEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = (uiState as? ViewfinderAssistUiState.Enabled)?.settings
    SwitchSettingUI(
        modifier = modifier.testTag(BTN_SWITCH_SETTING_TOP_SHOT_TAG),
        title = stringResource(R.string.viewfinder_top_shot_title),
        description = stringResource(R.string.viewfinder_top_shot_description),
        leadingIcon = null,
        onSwitchChanged = setEnabled,
        settingValue = settings?.isTopShotEnabled ?: false,
        enabled = settings != null
    )
}
