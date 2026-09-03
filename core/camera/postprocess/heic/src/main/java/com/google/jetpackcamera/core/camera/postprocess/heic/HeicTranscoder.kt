/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.google.jetpackcamera.core.camera.postprocess.heic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Converts an in-memory JPEG into an HEIC file.
 *
 * Abstracted behind an interface so [HeicImagePostProcessor] can be unit tested without a
 * hardware HEVC encoder.
 */
interface HeicTranscoder {
    /** Whether this device can encode HEIC at all. */
    val isSupported: Boolean

    /**
     * Encodes [jpeg] into [output] as HEIC.
     *
     * @param quality encoder quality in `0..100`.
     * @throws Exception on any decode/encode failure. [output] may be left partially written.
     */
    fun transcode(jpeg: ByteArray, output: File, quality: Int)
}

/**
 * [HeicTranscoder] backed by AndroidX [HeifWriter] (hardware HEVC via `MediaCodec`).
 *
 * The camera JPEG is decoded once, handed to the encoder as a bitmap and tiled into a 512x512
 * grid (the layout Pixel/iOS producers use). Rotation is preserved via the HEIF `irot` property
 * rather than by rotating pixels on the CPU, and the original EXIF block is copied over so
 * capture time, exposure data and GPS survive the conversion.
 */
class HeifWriterTranscoder : HeicTranscoder {

    /** Resolved once: probing [MediaCodecList] is not free and the answer never changes. */
    override val isSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasHevcEncoder()
    }

    override fun transcode(jpeg: ByteArray, output: File, quality: Int) {
        check(isSupported) { "HEIC encoding requires Android 9 (API 28) and an HEVC encoder" }
        transcodeInternal(jpeg, output, quality.coerceIn(0, 100))
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun transcodeInternal(jpeg: ByteArray, output: File, quality: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read JPEG dimensions" }

        val rotationDegrees = readRotationDegrees(jpeg)
        val exifBlock = JpegExif.extractExifBlock(jpeg)?.let { JpegExif.withOrientation(it) }

        val bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
        ) ?: error("Unable to decode JPEG")

        try {
            val writer = HeifWriter.Builder(
                output.absolutePath,
                bitmap.width,
                bitmap.height,
                HeifWriter.INPUT_MODE_BITMAP
            )
                .setQuality(quality)
                .setRotation(rotationDegrees)
                .setMaxImages(1)
                .setPrimaryIndex(0)
                .setGridEnabled(true)
                .build()
            try {
                writer.start()
                exifBlock?.let { writer.addExifData(0, it, 0, it.size) }
                writer.addBitmap(bitmap)
                writer.stop(STOP_TIMEOUT_MS)
            } finally {
                writer.close()
            }
        } finally {
            bitmap.recycle()
        }
        check(output.length() > 0L) { "HEIC encoder produced an empty file" }
    }

    /** Maps the JPEG EXIF orientation to a clockwise rotation understood by [HeifWriter]. */
    private fun readRotationDegrees(jpeg: ByteArray): Int = try {
        val orientation = ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90

            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180

            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270

            else -> 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read EXIF orientation, assuming upright", e)
        0
    }

    private fun hasHevcEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to query codec list", e)
        false
    }

    private companion object {
        const val TAG = "HeifWriterTranscoder"

        /** Upper bound for the encoder to flush a single 50 MP still on a slow SoC. */
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
