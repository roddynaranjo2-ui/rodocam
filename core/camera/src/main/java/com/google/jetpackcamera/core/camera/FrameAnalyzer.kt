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
package com.google.jetpackcamera.core.camera

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.jetpackcamera.model.FrameStats
import com.google.jetpackcamera.model.LumaHistogram
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "FrameAnalyzer"

/** Analysis resolution: small enough to be negligible on the CPU, large enough for stats. */
internal val FRAME_ANALYSIS_TARGET_SIZE = Size(320, 240)

/** Publish at most ~15 stats/s; the histogram overlay does not need more. */
internal const val FRAME_STATS_MIN_INTERVAL_NANOS = 66_000_000L

/**
 * Lightweight [ImageAnalysis.Analyzer] that computes a luma histogram, clipping statistics and
 * a sharpness metric (Top Shot) from the Y plane of each preview-sized frame and publishes them
 * into [frameStats].
 *
 * Only the Y plane is read (no colour conversion), sampling every other pixel horizontally and
 * vertically, so a 320x240 frame costs ~19k byte reads.
 *
 * @param frameStats Destination for the computed statistics.
 * @param zebraThresholdPercent Luma percentage from which pixels count as clipped highlights.
 */
internal class FrameAnalyzer(
    private val frameStats: MutableStateFlow<FrameStats>,
    zebraThresholdPercent: Int
) : ImageAnalysis.Analyzer {

    @Volatile
    var zebraThresholdPercent: Int = zebraThresholdPercent

    private var lastPublishNanos = 0L
    private val bins = IntArray(LumaHistogram.DEFAULT_BIN_COUNT)
    private var rowBuffer = ByteArray(0)
    private var sampleRow = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastPublishNanos < FRAME_STATS_MIN_INTERVAL_NANOS) return
            val stats = computeStats(image) ?: return
            lastPublishNanos = now
            frameStats.update { stats }
        } catch (e: Exception) {
            // Never let an analyzer exception kill the camera executor thread.
            Log.w(TAG, "Frame analysis failed", e)
        } finally {
            image.close()
        }
    }

    private fun computeStats(image: ImageProxy): FrameStats? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val yPlane = planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null

        bins.fill(0)
        val shift = LumaHistogram.binShift(bins.size)
        val step = SAMPLE_STEP
        if (rowBuffer.size < rowStride) rowBuffer = ByteArray(rowStride)
        val samplesPerRow = (width + step - 1) / step
        if (sampleRow.size < samplesPerRow) sampleRow = ByteArray(samplesPerRow)

        var sharpnessAcc = 0.0
        var rows = 0
        var y = crop.top
        while (y < crop.bottom) {
            readRow(buffer, y * rowStride, rowStride)
            var x = crop.left
            var n = 0
            while (x < crop.right) {
                val b = rowBuffer[x * pixelStride]
                bins[(b.toInt() and 0xFF) shr shift]++
                sampleRow[n++] = b
                x += step
            }
            sharpnessAcc += FrameStats.rowSharpness(sampleRow, n)
            rows++
            y += step
        }

        val histogram = LumaHistogram(bins)
        return FrameStats(
            histogram = histogram,
            clippedHighlightsFraction = histogram.fractionAbove(zebraThresholdPercent),
            crushedShadowsFraction = histogram.fractionBelow(FrameStats.CRUSHED_SHADOWS_PERCENT),
            width = width,
            height = height,
            timestampNanos = image.imageInfo.timestamp,
            sharpness = if (rows == 0) 0f else (sharpnessAcc / rows).toFloat()
        )
    }

    private fun readRow(buffer: ByteBuffer, offset: Int, length: Int) {
        val available = (buffer.limit() - offset).coerceAtLeast(0)
        val toRead = minOf(length, available)
        if (toRead <= 0) {
            rowBuffer.fill(0, 0, length.coerceAtMost(rowBuffer.size))
            return
        }
        val dup = buffer.duplicate()
        dup.position(offset)
        dup.get(rowBuffer, 0, toRead)
        if (toRead < length) rowBuffer.fill(0, toRead, length)
    }

    companion object {
        /** Sample every N-th pixel in both directions. */
        const val SAMPLE_STEP = 2
    }
}

/**
 * Builds the [ImageAnalysis] use case shared by the viewfinder overlays (histogram, zebras).
 *
 * `STRATEGY_KEEP_ONLY_LATEST` guarantees the analyzer never back-pressures the preview, and the
 * small target resolution keeps the extra stream cheap for the HAL.
 */
internal fun createFrameAnalysisUseCase(
    executor: Executor,
    analyzer: ImageAnalysis.Analyzer
): ImageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    FRAME_ANALYSIS_TARGET_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
    )
    .build()
    .also { it.setAnalyzer(executor, analyzer) }
