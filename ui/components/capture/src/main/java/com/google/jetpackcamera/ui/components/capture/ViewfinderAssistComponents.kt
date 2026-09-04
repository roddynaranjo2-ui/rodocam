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

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.jetpackcamera.model.CompositionGrid
import com.google.jetpackcamera.model.FrameStats
import com.google.jetpackcamera.model.HorizonLevel
import com.google.jetpackcamera.model.LumaHistogram
import com.google.jetpackcamera.model.ThermalStatus
import com.google.jetpackcamera.model.TopShotTracker
import com.google.jetpackcamera.ui.uistate.capture.ViewfinderAssistUiState
import kotlin.math.roundToInt

/** Test tags for the viewfinder assist overlays. */
const val VIEWFINDER_GRID_OVERLAY = "ViewfinderGridOverlay"
const val VIEWFINDER_LEVEL_INDICATOR = "ViewfinderLevelIndicator"
const val VIEWFINDER_HISTOGRAM = "ViewfinderHistogram"
const val VIEWFINDER_ZEBRA_WARNING = "ViewfinderZebraWarning"
const val VIEWFINDER_LOW_LIGHT_HINT = "ViewfinderLowLightHint"
const val VIEWFINDER_THERMAL_WARNING = "ViewfinderThermalWarning"
const val VIEWFINDER_FOCUS_PEAKING_BADGE = "ViewfinderFocusPeakingBadge"

private val GRID_LINE_COLOR = Color.White.copy(alpha = 0.55f)
private val GRID_SHADOW_COLOR = Color.Black.copy(alpha = 0.35f)
private val LEVEL_IDLE_COLOR = Color.White.copy(alpha = 0.85f)
private val LEVEL_OK_COLOR = Color(0xFFFFD54F) // Pixel: amber when level
private val HISTOGRAM_BG = Color.Black.copy(alpha = 0.45f)
private val HISTOGRAM_BAR = Color.White.copy(alpha = 0.9f)
private val HISTOGRAM_CLIP = Color(0xFFFF7043)
private val ZEBRA_COLOR = Color(0xFFFF5252)
private val PEAKING_COLOR = Color(0xFFFF3D00)

/**
 * Draws every enabled viewfinder assist on top of the camera preview.
 *
 * Must be placed inside the same clipped `Box` as the viewfinder so the grid and level line up
 * with the visible frame. Draws nothing when [uiState] is [ViewfinderAssistUiState.Disabled].
 *
 * @param displayRotationDegrees Rotation of the display relative to the device's natural
 *   orientation (0/90/180/270), used to fold the gravity vector into screen coordinates.
 */
@Composable
fun BoxScope.ViewfinderAssistOverlay(
    uiState: ViewfinderAssistUiState,
    modifier: Modifier = Modifier,
    displayRotationDegrees: Int = rememberDisplayRotationDegrees()
) {
    if (uiState !is ViewfinderAssistUiState.Enabled) return

    // The level sensor is read once here and shared by the level indicator and the coach.
    val horizonLevel by if (uiState.isLevelEnabled) {
        rememberHorizonLevel(displayRotationDegrees)
    } else {
        remember { mutableStateOf(HorizonLevel.LEVEL) }
    }

    if (uiState.grid != CompositionGrid.OFF) {
        CompositionGridOverlay(
            grid = uiState.grid,
            modifier = modifier.matchParentSize()
        )
    }
    if (uiState.isLevelEnabled) {
        HorizonLevelIndicator(
            level = horizonLevel,
            isHapticsEnabled = uiState.isHapticsEnabled,
            modifier = modifier.matchParentSize()
        )
    }
    if (uiState.showZebras) {
        ZebraWarning(
            clippedFraction = uiState.frameStats.clippedHighlightsFraction,
            thresholdPercent = uiState.zebraThresholdPercent,
            modifier = modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        )
    }
    if (uiState.showHistogram) {
        HistogramOverlay(
            histogram = uiState.frameStats.histogram,
            clippedFraction = uiState.frameStats.clippedHighlightsFraction,
            crushedFraction = uiState.frameStats.crushedShadowsFraction,
            modifier = modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
        )
    }
    if (uiState.showThermalWarning) {
        // Throttling takes the top-centre slot over the coach / low-light hints: the user must
        // know why the preview just dropped fps or lost peaking.
        ThermalWarningChip(
            thermalStatus = uiState.thermalStatus,
            modifier = modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )
    } else if (uiState.isCoachEnabled) {
        // The coach subsumes the plain low-light pill (LOW_LIGHT is one of its hints).
        CoachHintChip(
            inputs = uiState.coachInputs(horizonLevel),
            modifier = modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )
    } else if (uiState.isLowLightScene) {
        LowLightHint(
            modifier = modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
        )
    }
    if (uiState.isTopShotEnabled || uiState.isFocusPeakingEnabled) {
        Row(
            modifier = modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (uiState.isFocusPeakingEnabled) {
                FocusPeakingBadge()
            }
            if (uiState.showTopShot) {
                val tracker = rememberTopShotTracker(uiState.frameStats)
                val startPadding = if (uiState.isFocusPeakingEnabled) 6.dp else 0.dp
                TopShotBadge(
                    tracker = tracker,
                    modifier = Modifier.padding(start = startPadding)
                )
            }
        }
    }
}

/** Feeds each new [FrameStats] into a remembered [TopShotTracker]. */
@Composable
fun rememberTopShotTracker(frameStats: FrameStats): TopShotTracker {
    var tracker by remember { mutableStateOf(TopShotTracker.EMPTY) }
    LaunchedEffect(frameStats) {
        tracker = tracker.add(frameStats)
    }
    return tracker
}

/** Small static badge reminding the user that the preview (not the capture) is being peaked. */
@Composable
fun FocusPeakingBadge(modifier: Modifier = Modifier) {
    val text = stringResource(R.string.focus_peaking_badge)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HISTOGRAM_BG)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(VIEWFINDER_FOCUS_PEAKING_BADGE),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(8.dp)) { drawCircle(PEAKING_COLOR) }
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/**
 * Composition grid (rule of thirds, fourths, golden ratio, diagonals, centre cross).
 *
 * Lines are drawn twice — a soft dark shadow under a light stroke — so they stay visible over
 * both bright and dark scenes without a background box.
 */
@Composable
fun CompositionGridOverlay(grid: CompositionGrid, modifier: Modifier = Modifier) {
    if (grid == CompositionGrid.OFF) return
    val description = stringResource(R.string.viewfinder_grid_description)
    Canvas(
        modifier = modifier
            .testTag(VIEWFINDER_GRID_OVERLAY)
            .semantics { contentDescription = description }
    ) {
        val stroke = 1.dp.toPx()
        drawGrid(grid, GRID_SHADOW_COLOR, stroke * 3f)
        drawGrid(grid, GRID_LINE_COLOR, stroke)
    }
}

private fun DrawScope.drawGrid(grid: CompositionGrid, color: Color, strokeWidth: Float) {
    val w = size.width
    val h = size.height
    for (f in grid.lineFractions) {
        drawLine(color, Offset(w * f, 0f), Offset(w * f, h), strokeWidth)
        drawLine(color, Offset(0f, h * f), Offset(w, h * f), strokeWidth)
    }
    if (grid.hasDiagonals) {
        drawLine(color, Offset(0f, 0f), Offset(w, h), strokeWidth)
        drawLine(color, Offset(w, 0f), Offset(0f, h), strokeWidth)
    }
    if (grid.hasCenterMark) {
        val arm = minOf(w, h) * 0.06f
        val c = Offset(w / 2f, h / 2f)
        drawLine(color, c.copy(x = c.x - arm), c.copy(x = c.x + arm), strokeWidth)
        drawLine(color, c.copy(y = c.y - arm), c.copy(y = c.y + arm), strokeWidth)
    }
}

/**
 * Pixel-style horizon level: a short centre line that rotates with the device roll and a fixed
 * reference line. Turns amber and emits one haptic tick when the device becomes level.
 *
 * Hidden when the device is pointing straight up/down (pitch beyond ±70°) since roll is
 * meaningless there.
 */
@Composable
fun HorizonLevelIndicator(
    isHapticsEnabled: Boolean,
    displayRotationDegrees: Int,
    modifier: Modifier = Modifier
) {
    val level by rememberHorizonLevel(displayRotationDegrees)
    HorizonLevelIndicator(level = level, isHapticsEnabled = isHapticsEnabled, modifier = modifier)
}

/** Stateless variant of [HorizonLevelIndicator] driven by an externally read [level]. */
@Composable
fun HorizonLevelIndicator(
    level: HorizonLevel,
    isHapticsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val currentHaptics by rememberUpdatedState(isHapticsEnabled)

    // One tick on the transition to level (not while staying level).
    LaunchedEffect(level.isLevel, level.isUsable) {
        if (level.isLevel && level.isUsable && currentHaptics) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val color by animateFloatAsState(
        targetValue = if (level.isLevel) 1f else 0f,
        animationSpec = tween(120),
        label = "levelColor"
    )
    val rollLabel = stringResource(
        R.string.viewfinder_level_description,
        level.rollDegrees.roundToInt()
    )
    AnimatedVisibility(
        visible = level.isUsable,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag(VIEWFINDER_LEVEL_INDICATOR)
                .semantics { contentDescription = rollLabel }
        ) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val half = minOf(size.width, size.height) * 0.16f
            val gap = 6.dp.toPx()
            val stroke = 2.dp.toPx()
            val lineColor = lerpColor(LEVEL_IDLE_COLOR, LEVEL_OK_COLOR, color)

            // Fixed reference ticks either side of the centre.
            drawLine(
                GRID_SHADOW_COLOR,
                Offset(centre.x - half - gap - half * 0.5f, centre.y),
                Offset(centre.x - half - gap, centre.y),
                stroke * 2f,
                StrokeCap.Round
            )
            drawLine(
                LEVEL_IDLE_COLOR,
                Offset(centre.x - half - gap - half * 0.5f, centre.y),
                Offset(centre.x - half - gap, centre.y),
                stroke,
                StrokeCap.Round
            )
            drawLine(
                GRID_SHADOW_COLOR,
                Offset(centre.x + half + gap, centre.y),
                Offset(centre.x + half + gap + half * 0.5f, centre.y),
                stroke * 2f,
                StrokeCap.Round
            )
            drawLine(
                LEVEL_IDLE_COLOR,
                Offset(centre.x + half + gap, centre.y),
                Offset(centre.x + half + gap + half * 0.5f, centre.y),
                stroke,
                StrokeCap.Round
            )

            // Rotating centre line that follows the horizon.
            rotate(degrees = -level.rollDegrees, pivot = centre) {
                drawLine(
                    GRID_SHADOW_COLOR,
                    Offset(centre.x - half, centre.y),
                    Offset(centre.x + half, centre.y),
                    stroke * 2f,
                    StrokeCap.Round
                )
                drawLine(
                    lineColor,
                    Offset(centre.x - half, centre.y),
                    Offset(centre.x + half, centre.y),
                    stroke,
                    StrokeCap.Round
                )
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)

/**
 * Subscribes to the gravity sensor (falling back to the accelerometer) while composed and maps
 * it to a [HorizonLevel]. Uses SENSOR_DELAY_UI (~60 ms) and a light low-pass filter to keep the
 * indicator smooth without eating battery.
 */
@Composable
fun rememberHorizonLevel(displayRotationDegrees: Int): State<HorizonLevel> {
    val context = LocalContext.current
    val currentRotation by rememberUpdatedState(displayRotationDegrees)
    return produceState(initialValue = HorizonLevel.LEVEL, context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@produceState
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return@produceState
        var fx = 0f
        var fy = 0f
        var fz = 0f
        var primed = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                if (!primed) {
                    fx = event.values[0]
                    fy = event.values[1]
                    fz = event.values[2]
                    primed = true
                } else {
                    fx += (event.values[0] - fx) * LOW_PASS_ALPHA
                    fy += (event.values[1] - fy) * LOW_PASS_ALPHA
                    fz += (event.values[2] - fz) * LOW_PASS_ALPHA
                }
                val next = HorizonLevel.fromGravity(fx, fy, fz, currentRotation)
                // Quantise to 0.5° so the Canvas does not redraw on sensor noise.
                val quantised = HorizonLevel(
                    rollDegrees = (next.rollDegrees * 2f).roundToInt() / 2f,
                    pitchDegrees = (next.pitchDegrees * 2f).roundToInt() / 2f
                )
                if (quantised != value) value = quantised
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitDispose { sensorManager.unregisterListener(listener) }
    }
}

private const val LOW_PASS_ALPHA = 0.25f

/** Current display rotation in degrees (0/90/180/270), read from the hosting view's display. */
@Composable
fun rememberDisplayRotationDegrees(): Int {
    val view = LocalView.current
    val context = LocalContext.current
    val rotationState = remember { mutableStateOf(readDisplayRotation(context, view.display)) }
    DisposableEffect(view) {
        // Re-read on every layout pass; rotation changes are rare and cheap to poll this way.
        val listener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val next = readDisplayRotation(v.context, v.display)
            if (rotationState.value != next) rotationState.value = next
        }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return rotationState.value
}

private fun readDisplayRotation(context: Context, display: android.view.Display?): Int {
    val d = display ?: ContextCompat.getDisplayOrDefault(context)
    return when (d.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

/**
 * Compact 64-bar luma histogram in the bottom-left corner. The last bins glow orange when a
 * meaningful share of pixels is clipped; the first bins do the same for crushed shadows.
 */
@Composable
fun HistogramOverlay(
    histogram: LumaHistogram,
    clippedFraction: Float,
    crushedFraction: Float,
    modifier: Modifier = Modifier
) {
    if (histogram.isEmpty) return
    val description = stringResource(R.string.viewfinder_histogram_description)
    val normalized = remember(histogram) { histogram.normalized() }
    Canvas(
        modifier = modifier
            .width(120.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(HISTOGRAM_BG)
            .padding(4.dp)
            .testTag(VIEWFINDER_HISTOGRAM)
            .semantics { contentDescription = description }
    ) {
        val n = normalized.size
        if (n == 0) return@Canvas
        val barWidth = size.width / n
        val clipBins = maxOf(1, n / 16)
        for (i in 0 until n) {
            val v = normalized[i].coerceIn(0f, 1f)
            val barHeight = size.height * v
            val color = when {
                i >= n - clipBins && clippedFraction > CLIP_WARN_FRACTION -> HISTOGRAM_CLIP
                i < clipBins && crushedFraction > CLIP_WARN_FRACTION -> HISTOGRAM_CLIP
                else -> HISTOGRAM_BAR
            }
            drawRect(
                color = color,
                topLeft = Offset(i * barWidth, size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
        // Baseline.
        drawLine(
            HISTOGRAM_BAR.copy(alpha = 0.4f),
            Offset(0f, size.height - 0.5f),
            Offset(size.width, size.height - 0.5f),
            1f
        )
    }
}

private const val CLIP_WARN_FRACTION = 0.01f

/**
 * Zebra warning badge: a striped chip in the top-right showing the share of clipped highlights.
 *
 * Per-pixel zebra striping over the preview requires a GPU effect on the camera stream; this
 * badge is the portable fallback that works on every API level and every device.
 */
@Composable
fun ZebraWarning(clippedFraction: Float, thresholdPercent: Int, modifier: Modifier = Modifier) {
    val percent = (clippedFraction * 100f).roundToInt().coerceIn(0, 100)
    if (percent == 0) return
    val description = stringResource(
        R.string.viewfinder_zebra_description,
        percent,
        thresholdPercent
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HISTOGRAM_BG)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(VIEWFINDER_ZEBRA_WARNING)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            drawRect(Color.White.copy(alpha = 0.9f))
            val stripe = 3.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    ZEBRA_COLOR,
                    Offset(x, size.height),
                    Offset(x + size.height, 0f),
                    stripe
                )
                x += stripe * 2f
            }
        }
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** Small pill suggesting Night mode when AE reports a dark scene. */
@Composable
fun LowLightHint(modifier: Modifier = Modifier) {
    val text = stringResource(R.string.viewfinder_low_light_hint)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HISTOGRAM_BG)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(VIEWFINDER_LOW_LIGHT_HINT),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nightlight_outline),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/**
 * Warning chip shown while the device is thermally throttled. From [ThermalStatus.SEVERE] the
 * text says the camera quality has been reduced; below that it only says the device is warm.
 */
@Composable
fun ThermalWarningChip(thermalStatus: ThermalStatus, modifier: Modifier = Modifier) {
    val text = stringResource(
        if (thermalStatus.isHot) {
            R.string.viewfinder_thermal_hot
        } else {
            R.string.viewfinder_thermal_warm
        }
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HISTOGRAM_BG)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(VIEWFINDER_THERMAL_WARNING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(if (thermalStatus.isHot) ZEBRA_COLOR else PEAKING_COLOR)
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** Debug helper: renders a static histogram so previews/tests can exercise the drawing code. */
internal fun sampleHistogram(): LumaHistogram {
    val counts = IntArray(LumaHistogram.DEFAULT_BIN_COUNT) { i ->
        val x = (i - 30) / 12f
        (1000f * kotlin.math.exp(-x * x)).roundToInt() + if (i > 60) 400 else 0
    }
    return LumaHistogram(counts)
}
