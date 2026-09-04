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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.jetpackcamera.model.TimerCountdown
import com.google.jetpackcamera.ui.uistate.capture.CaptureTimerUiState
import com.google.jetpackcamera.ui.uistate.capture.activeCountdown

private val COUNTDOWN_RING_SIZE = 132.dp
private val COUNTDOWN_RING_STROKE = 6.dp
private const val RING_ANIMATION_MILLIS = 900
private const val START_ANGLE_TOP = -90f
private const val FULL_SWEEP = 360f

/**
 * Full-viewfinder self-timer countdown: a large remaining-seconds figure inside a ring that
 * drains as the timer progresses (Pixel Camera style). Tapping anywhere cancels the pending
 * capture through [onCancel].
 *
 * Nothing is drawn when [uiState] has no active countdown.
 */
@Composable
fun BoxScope.TimerCountdownOverlay(
    uiState: CaptureTimerUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val countdown = uiState.activeCountdown
    // Remember the last value so the exit fade keeps showing the final digit instead of
    // collapsing to nothing the moment the countdown clears.
    var lastShown by remember { mutableStateOf(countdown) }
    if (countdown != null) lastShown = countdown
    AnimatedVisibility(
        visible = countdown != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.matchParentSize()
    ) {
        lastShown?.let { CountdownContent(countdown = it, onCancel = onCancel) }
    }
}

@Composable
private fun CountdownContent(countdown: TimerCountdown, onCancel: () -> Unit) {
    val description = stringResource(
        R.string.timer_countdown_description,
        countdown.remainingSeconds
    )
    val cancelLabel = stringResource(R.string.timer_countdown_cancel)
    val progress by animateFloatAsState(
        targetValue = countdown.progress,
        animationSpec = tween(RING_ANIMATION_MILLIS),
        label = "timerRing"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .testTag(TIMER_COUNTDOWN_OVERLAY)
            .semantics { contentDescription = description }
            .clickable(onClickLabel = cancelLabel, onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(COUNTDOWN_RING_SIZE)) {
            val strokePx = COUNTDOWN_RING_STROKE.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.3f),
                startAngle = START_ANGLE_TOP,
                sweepAngle = FULL_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color.White,
                startAngle = START_ANGLE_TOP,
                sweepAngle = FULL_SWEEP * (1f - progress),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Text(
            text = countdown.remainingSeconds.toString(),
            color = Color.White,
            fontSize = 64.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
