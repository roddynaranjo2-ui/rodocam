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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.jetpackcamera.ui.uistate.DisableRationale
import com.google.jetpackcamera.ui.uistate.SingleSelectableUiState
import com.google.jetpackcamera.ui.uistate.capture.CaptureModeCarouselUiState
import com.google.jetpackcamera.ui.uistate.capture.ShootingMode

/** Test tag of the whole carousel. */
const val MODE_CAROUSEL_TAG = "ModeCarouselTag"

/** Test tag of one chip; see [modeChipTestTag]. */
fun modeChipTestTag(mode: ShootingMode): String = "ModeCarouselChip_${mode.name}"

/** Minimum horizontal drag (dp) that moves the selection by one chip. */
internal val MODE_CAROUSEL_SWIPE_THRESHOLD: Dp = 48.dp

private val CHIP_WIDTH = 84.dp
private val CHIP_HEIGHT = 32.dp
private val SELECTED_BG = Color.White.copy(alpha = 0.92f)
private val SELECTED_FG = Color(0xFF1B1B1B)
private val UNSELECTED_FG = Color.White
private val DISABLED_FG = Color.White.copy(alpha = 0.38f)
private val DOT_COLOR = Color(0xFFFFB300)

/**
 * Pixel-style shooting mode carousel: uppercase labels in a row, the selected one sits at the
 * horizontal centre inside a white pill that slides between chips. Tapping a chip selects it;
 * dragging horizontally moves the selection one step in the swipe direction (drag left → next
 * mode on the right, like paging through the Pixel camera).
 *
 * The composable is stateless: the selected chip is fully driven by [uiState]. Disabled entries
 * stay visible (dimmed) and report their rationale through [onModeDisabled] when tapped.
 *
 * Renders nothing when [uiState] is [CaptureModeCarouselUiState.Unavailable].
 */
@Composable
fun ModeCarousel(
    uiState: CaptureModeCarouselUiState,
    onSelectMode: (ShootingMode) -> Unit,
    onModeDisabled: (DisableRationale) -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState !is CaptureModeCarouselUiState.Available) return
    val modes = uiState.modes
    val selectedIndex = uiState.selectedIndex.coerceAtLeast(0)
    val latestState by rememberUpdatedState(uiState)
    val latestSelect by rememberUpdatedState(onSelectMode)

    val density = LocalDensity.current
    val thresholdPx = with(density) { MODE_CAROUSEL_SWIPE_THRESHOLD.toPx() }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    // One selection step per gesture: a long swipe must not page through several modes.
    var stepTakenInGesture by remember { mutableStateOf(false) }

    // The row is centred in the box; shift it so that the selected chip's centre lands on the
    // box centre: chip i is at (i + 0.5) * W from the row start, the row centre at N * W / 2.
    val rowShift by animateDpAsState(
        targetValue = rowShiftFor(modes.size, selectedIndex, CHIP_WIDTH),
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.9f),
        label = "modeCarouselShift"
    )
    // State description mirrors the legacy photo/video toggle for Photo and Video so existing
    // instrumentation (getCaptureModeToggleState) keeps working; other presets expose their label.
    val selectedLabel = when (uiState.selectedMode) {
        ShootingMode.PHOTO ->
            stringResource(R.string.capture_mode_image_capture_content_description)

        ShootingMode.VIDEO ->
            stringResource(R.string.capture_mode_video_recording_content_description)

        else -> stringResource(uiState.selectedMode.labelRes())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CHIP_HEIGHT + 8.dp)
            .semantics {
                testTag = MODE_CAROUSEL_TAG
                stateDescription = selectedLabel
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragAccumulator = 0f
                        stepTakenInGesture = false
                    },
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (!stepTakenInGesture) {
                            dragAccumulator += dragAmount
                            val step = when {
                                dragAccumulator <= -thresholdPx -> +1
                                dragAccumulator >= thresholdPx -> -1
                                else -> 0
                            }
                            if (step != 0) {
                                stepTakenInGesture = true
                                dragAccumulator = 0f
                                nextSelectable(latestState, step)?.let(latestSelect)
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Selection pill, fixed at the centre.
        Box(
            modifier = Modifier
                .width(CHIP_WIDTH - 8.dp)
                .height(CHIP_HEIGHT)
                .clip(CircleShape)
                .background(SELECTED_BG)
        )
        Row(
            modifier = Modifier.offset(x = rowShift),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEachIndexed { index, entry ->
                ModeChip(
                    entry = entry,
                    isSelected = index == selectedIndex,
                    onClick = {
                        when (entry) {
                            is SingleSelectableUiState.SelectableUi -> {
                                if (index != selectedIndex) onSelectMode(entry.value)
                            }

                            is SingleSelectableUiState.Disabled ->
                                onModeDisabled(entry.disabledReason)
                        }
                    }
                )
            }
        }
    }
}

/** Horizontal offset that centres chip [selectedIndex] of a [count]-chip row of [chipWidth]. */
internal fun rowShiftFor(count: Int, selectedIndex: Int, chipWidth: Dp): Dp =
    chipWidth * (count / 2f - selectedIndex - 0.5f)

/** Next selectable mode from the current selection in [step] direction, or null at the end. */
internal fun nextSelectable(
    state: CaptureModeCarouselUiState.Available,
    step: Int
): ShootingMode? {
    var index = state.selectedIndex + step
    while (index in state.modes.indices) {
        val entry = state.modes[index]
        if (entry is SingleSelectableUiState.SelectableUi) return entry.value
        index += step
    }
    return null
}

@Composable
private fun ModeChip(
    entry: SingleSelectableUiState<ShootingMode>,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mode = entry.value
    val isEnabled = entry is SingleSelectableUiState.SelectableUi
    val label = stringResource(mode.labelRes())
    val textColor by animateColorAsState(
        targetValue = when {
            isSelected -> SELECTED_FG
            isEnabled -> UNSELECTED_FG
            else -> DISABLED_FG
        },
        label = "modeChipColor"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(CHIP_WIDTH)
            .height(CHIP_HEIGHT)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                testTag = modeChipTestTag(mode)
                contentDescription = label
                selected = isSelected
                if (!isEnabled) disabled()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge
        )
        if (mode == ShootingMode.PRO && !isSelected && isEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 10.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(DOT_COLOR)
            )
        }
    }
}

/** Uppercase label resource for each preset. */
fun ShootingMode.labelRes(): Int = when (this) {
    ShootingMode.PHOTO -> R.string.mode_carousel_photo
    ShootingMode.VIDEO -> R.string.mode_carousel_video
    ShootingMode.PORTRAIT -> R.string.mode_carousel_portrait
    ShootingMode.NIGHT -> R.string.mode_carousel_night
    ShootingMode.PRO -> R.string.mode_carousel_pro
}
