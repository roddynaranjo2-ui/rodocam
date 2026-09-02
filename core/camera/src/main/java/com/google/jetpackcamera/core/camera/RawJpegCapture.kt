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

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "RawJpegCapture"

/**
 * Result of a simultaneous RAW + JPEG capture.
 *
 * @property jpeg results of the developed JPEG (primary image, always present on success).
 * @property raw results of the DNG file, or `null` if the HAL delivered only the JPEG.
 */
data class RawJpegResults(
    val jpeg: ImageCapture.OutputFileResults,
    val raw: ImageCapture.OutputFileResults?
)

/**
 * Suspending wrapper around
 * [ImageCapture.takePicture] `(rawOptions, jpegOptions, executor, callback)` for use cases built
 * with [ImageCapture.OUTPUT_FORMAT_RAW_JPEG].
 *
 * CameraX invokes [ImageCapture.OnImageSavedCallback.onImageSaved] once per output (RAW and JPEG,
 * in unspecified order) and [ImageCapture.OnImageSavedCallback.onError] at most once. This
 * function resumes when both files have been saved, or fails on the first error.
 *
 * @param rawOutputFileOptions destination of the DNG file.
 * @param jpegOutputFileOptions destination of the JPEG file.
 * @param onCaptureStarted invoked when the shutter fires (for the UI flash / sound).
 */
suspend fun ImageCapture.takeRawJpegPicture(
    rawOutputFileOptions: ImageCapture.OutputFileOptions,
    jpegOutputFileOptions: ImageCapture.OutputFileOptions,
    executor: Executor,
    onCaptureStarted: () -> Unit = {}
): RawJpegResults = suspendCancellableCoroutine { continuation ->
    val jpegResult = atomic<ImageCapture.OutputFileResults?>(null)
    val rawResult = atomic<ImageCapture.OutputFileResults?>(null)
    val savedCount = atomic(0)
    val finished = atomic(false)

    takePicture(
        rawOutputFileOptions,
        jpegOutputFileOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() = onCaptureStarted()

            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                when (outputFileResults.imageFormat) {
                    ImageFormat.RAW_SENSOR -> rawResult.value = outputFileResults
                    else -> jpegResult.value = outputFileResults
                }
                if (savedCount.incrementAndGet() >= 2 && finished.compareAndSet(false, true)) {
                    val jpeg = jpegResult.value
                    if (jpeg != null) {
                        continuation.resume(RawJpegResults(jpeg = jpeg, raw = rawResult.value))
                    } else {
                        // Two outputs but no JPEG among them: should not happen, but never hang.
                        continuation.resumeWithException(
                            ImageCaptureException(
                                ImageCaptureException.ERROR_UNKNOWN,
                                "RAW+JPEG capture finished without a JPEG output",
                                null
                            )
                        )
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "RAW+JPEG capture failed", exception)
                if (finished.compareAndSet(false, true)) {
                    continuation.resumeWithException(exception)
                }
            }
        }
    )
}
