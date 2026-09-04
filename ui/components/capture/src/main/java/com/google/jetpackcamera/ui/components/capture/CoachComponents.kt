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

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.jetpackcamera.model.CameraCoach
import com.google.jetpackcamera.model.CoachHint
import com.google.jetpackcamera.model.CoachHintSmoother
import com.google.jetpackcamera.model.CoachInputs
import com.google.jetpackcamera.model.TopShotTracker
import kotlinx.coroutines.delay

const val VIEWFINDER_COACH_HINT = "ViewfinderCoachHint"
const val VIEWFINDER_TOP_SHOT_BADGE = "ViewfinderTopShotBadge"

private val CHIP_BG = Color.Black.copy(alpha = 0.55f)
private val COACH_ACCENT = Color(0xFFFFD54F)
private val SHARP_COLOR = Color(0xFF69F0AE)
private val SOFT_COLOR = Color(0xFFFF7043)

/** How often the coach smoother is ticked while a hint candidate is pending. */
private const val COACH_TICK_MILLIS = 100L

/**
 * Camera Coach chip: evaluates [inputs] through [CameraCoach], debounces the result with a
 * [CoachHintSmoother] and shows the top hint as an animated pill.
 *
 * @param inputs Live rule inputs (frame stats, level, low light, zoom).
 * @param nowMillis Clock used by the smoother; injectable for tests.
 */
@Composable
fun CoachHintChip(
    inputs: CoachInputs,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
    val rawHint = remember(inputs) { CameraCoach.topHint(inputs) }
    var smoother by remember { mutableStateOf(CoachHintSmoother.IDLE) }
    val currentNow by rememberUpdatedState(nowMillis)

    // Tick while something is pending (a candidate waiting to show, or a visible hint waiting to
    // hide) so the smoother's time-based transitions fire even when inputs stop changing.
    LaunchedEffect(rawHint) {
        smoother = smoother.update(rawHint, currentNow())
        while (smoother.visibleHint != rawHint) {
            delay(COACH_TICK_MILLIS)
            smoother = smoother.update(rawHint, currentNow())
        }
    }

    val hint = smoother.visibleHint
    AnimatedVisibility(
        visible = hint != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier
    ) {
        // Keep the last non-null hint during the exit animation.
        var lastHint by remember { mutableStateOf(hint ?: CoachHint.LOW_CONTRAST) }
        if (hint != null) lastHint = hint
        AnimatedContent(targetState = lastHint, label = "coachHint") { shown ->
            CoachHintPill(hint = shown)
        }
    }
}

@Composable
private fun CoachHintPill(hint: CoachHint, modifier: Modifier = Modifier) {
    val text = stringResource(hint.labelRes())
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CHIP_BG)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(VIEWFINDER_COACH_HINT)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoachGlyph(hint = hint, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** Tiny vector glyph per hint so the chip does not depend on the material-icons artifact. */
@Composable
private fun CoachGlyph(hint: CoachHint, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val c = center
        val r = size.minDimension / 2f
        when (hint) {
            CoachHint.OVEREXPOSED -> {
                drawCircle(COACH_ACCENT, radius = r * 0.45f, center = c)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val dx = Math.cos(a).toFloat()
                    val dy = Math.sin(a).toFloat()
                    drawLine(
                        COACH_ACCENT,
                        Offset(c.x + dx * r * 0.65f, c.y + dy * r * 0.65f),
                        Offset(c.x + dx * r, c.y + dy * r),
                        stroke.width,
                        StrokeCap.Round
                    )
                }
            }
            CoachHint.UNDEREXPOSED, CoachHint.LOW_LIGHT -> {
                drawCircle(COACH_ACCENT, radius = r, center = c, style = stroke)
                drawCircle(COACH_ACCENT, radius = r * 0.55f, center = c.copy(x = c.x + r * 0.25f))
            }
            CoachHint.TILTED_HORIZON -> {
                drawLine(
                    COACH_ACCENT,
                    Offset(c.x - r, c.y + r * 0.35f),
                    Offset(c.x + r, c.y - r * 0.35f),
                    stroke.width,
                    StrokeCap.Round
                )
                drawLine(
                    Color.White.copy(alpha = 0.6f),
                    Offset(c.x - r, c.y),
                    Offset(c.x + r, c.y),
                    stroke.width * 0.6f,
                    StrokeCap.Round
                )
            }
            CoachHint.HIGH_ZOOM -> {
                drawCircle(
                    COACH_ACCENT,
                    radius = r * 0.7f,
                    center = c.copy(x = c.x - r * 0.2f, y = c.y - r * 0.2f),
                    style = stroke
                )
                drawLine(
                    COACH_ACCENT,
                    Offset(c.x + r * 0.3f, c.y + r * 0.3f),
                    Offset(c.x + r, c.y + r),
                    stroke.width,
                    StrokeCap.Round
                )
            }
            CoachHint.LOW_CONTRAST -> {
                drawRect(
                    COACH_ACCENT,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r, r * 2f)
                )
                drawRect(
                    COACH_ACCENT.copy(alpha = 0.4f),
                    topLeft = Offset(c.x, c.y - r),
                    size = Size(r, r * 2f)
                )
            }
        }
    }
}

private fun CoachHint.labelRes(): Int = when (this) {
    CoachHint.OVEREXPOSED -> R.string.coach_hint_overexposed
    CoachHint.UNDEREXPOSED -> R.string.coach_hint_underexposed
    CoachHint.LOW_LIGHT -> R.string.coach_hint_low_light
    CoachHint.TILTED_HORIZON -> R.string.coach_hint_tilted
    CoachHint.HIGH_ZOOM -> R.string.coach_hint_high_zoom
    CoachHint.LOW_CONTRAST -> R.string.coach_hint_low_contrast
}

/**
 * Top Shot badge: a small dot + label that turns green when the latest frame is among the
 * sharpest of the recent window and orange when it is noticeably softer (motion blur / hunting
 * focus). Hidden until the tracker has enough history.
 */
@Composable
fun TopShotBadge(tracker: TopShotTracker, modifier: Modifier = Modifier) {
    val ready = tracker.samples.size >= tracker.minSamples
    AnimatedVisibility(visible = ready, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val isSharp = tracker.isLatestSharp
        val text = stringResource(
            if (isSharp) R.string.top_shot_sharp else R.string.top_shot_soft
        )
        val color = if (isSharp) SHARP_COLOR else SOFT_COLOR
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(CHIP_BG)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(VIEWFINDER_TOP_SHOT_BADGE)
                .semantics { contentDescription = text },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color) }
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}
