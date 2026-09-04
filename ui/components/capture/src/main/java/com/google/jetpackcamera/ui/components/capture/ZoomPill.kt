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
package com.google.jetpackcamera.ui.components.capture

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.jetpackcamera.model.LensFacing
import com.google.jetpackcamera.ui.uistate.capture.ZoomControlUiState
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Test tag of the pill container (kept identical to the legacy row for instrumentation). */
const val ZOOM_PILL_TAG = ZOOM_BUTTON_ROW_TAG

private val PILL_BG = Color.Black.copy(alpha = 0.38f)
private val CHIP_SELECTED_BG = Color.White
private val CHIP_SELECTED_FG = Color(0xFF1B1B1B)
private val CHIP_FG = Color.White

/**
 * Pixel-style zoom pill: a dark rounded track with one chip per zoom level (0.5 / 1 / 2 / 5 or
 * the device's physical lenses). A white indicator slides behind the selected chip, which shows
 * the live zoom ratio (e.g. "2.3x") while the others show their target ("0.5", "2", "5").
 *
 * Tapping a chip requests that zoom through [onChangeZoom].
 *
 * @param chipSize Height of each chip. The pill height is `chipSize + 2 * padding`.
 * @param spacing Horizontal gap between chips.
 */
@Composable
fun ZoomPill(
    zoomControlUiState: ZoomControlUiState,
    onChangeZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    chipSize: Dp = 36.dp,
    spacing: Dp = 4.dp,
    padding: Dp = 4.dp
) {
    if (zoomControlUiState !is ZoomControlUiState.Enabled) return
    val levels = zoomControlUiState.zoomLevels
    if (levels.isEmpty()) return

    val displayedRatio = zoomControlUiState.animatingToValue
        ?: zoomControlUiState.primaryZoomRatio
        ?: zoomControlUiState.initialZoomRatio
        ?: 1f
    val selectedIndex = selectedZoomIndex(levels, displayedRatio)
    val chipWidth = remember(chipSize) { chipSize + 8.dp }

    val indicatorOffset by animateDpAsState(
        targetValue = (chipWidth + spacing) * selectedIndex,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.85f),
        label = "zoomIndicator"
    )
    val stateDescriptionText = formatZoomRatio(displayedRatio)

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(PILL_BG)
            .padding(padding)
            .height(chipSize)
            .semantics {
                testTag = ZOOM_PILL_TAG
                stateDescription = stateDescriptionText
            }
    ) {
        // Sliding indicator (drawn below the chips).
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(chipWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(CHIP_SELECTED_BG)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            levels.forEachIndexed { index, level ->
                if (index > 0) Box(modifier = Modifier.width(spacing))
                val isSelected = index == selectedIndex
                ZoomChip(
                    level = level,
                    label = zoomChipLabel(level, displayedRatio, isSelected),
                    isSelected = isSelected,
                    onClick = { onChangeZoom(level) },
                    modifier = Modifier
                        .width(chipWidth)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ZoomChip(
    level: Float,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description =
        stringResource(R.string.zoom_button_content_description, formatZoomLevel(level))
    val selectedDescription = stringResource(R.string.zoom_button_state_selected)
    val notSelectedDescription = stringResource(R.string.zoom_button_state_not_selected)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                testTag = getZoomButtonTestTag(level)
                contentDescription = description
                role = Role.RadioButton
                selected = isSelected
                stateDescription = if (isSelected) selectedDescription else notSelectedDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            modifier = Modifier.animateContentSize(),
            text = label,
            color = if (isSelected) CHIP_SELECTED_FG else CHIP_FG,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Index of the chip that should appear selected for [ratio]: the last level that is `<= ratio`
 * (so 1.7x highlights "1", 2.3x highlights "2"), or the first chip when the ratio is below every
 * level (ultra-wide range). Returns 0 for an empty list.
 */
fun selectedZoomIndex(levels: List<Float>, ratio: Float): Int {
    if (levels.isEmpty()) return 0
    val idx = levels.indexOfLast { ratio >= it - ZOOM_EPSILON }
    return if (idx < 0) 0 else idx
}

/**
 * Label shown in a chip. The selected chip shows the live ratio with one decimal and an "x"
 * (Pixel: "1.0x", "2.3x"); other chips show their compact target ("0.5", "1", "2", "5").
 */
fun zoomChipLabel(level: Float, currentRatio: Float, isSelected: Boolean): String =
    if (isSelected) formatZoomRatio(currentRatio) else formatZoomLevel(level)

/** Compact target formatting: `0.5`, `1`, `2`, `3`, `10`. Sub-1x values round up, others down. */
fun formatZoomLevel(level: Float): String {
    // Pre-round to two decimals so float noise (0.6f == 0.60000002) does not flip the rounding.
    val rounded = (level * 100f).roundToInt() / 100.0
    val formatter = DecimalFormat("#.#").apply {
        minimumIntegerDigits = if (rounded < 1.0) 1 else 0
        roundingMode = if (rounded >= 1.0) RoundingMode.DOWN else RoundingMode.UP
    }
    return formatter.format(rounded)
}

/** Live ratio formatting with one decimal and an "x" suffix: `0.6x`, `1.0x`, `2.3x`, `10.0x`. */
fun formatZoomRatio(ratio: Float): String =
    String.format(Locale.US, "%.1fx", ratio.coerceAtLeast(0f))

private const val ZOOM_EPSILON = 0.005f

@Preview(showBackground = true)
@Composable
private fun ZoomPillPreview() {
    Box(
        Modifier
            .background(Color.DarkGray)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        ZoomPill(
            zoomControlUiState = ZoomControlUiState.Enabled(
                zoomLevels = listOf(0.5f, 1f, 2f, 5f),
                primaryLensFacing = LensFacing.BACK,
                primaryZoomRatio = 2.3f
            ),
            onChangeZoom = {}
        )
    }
}
