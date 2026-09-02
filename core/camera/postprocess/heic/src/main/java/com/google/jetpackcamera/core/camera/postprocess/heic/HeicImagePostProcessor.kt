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

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.jetpackcamera.core.camera.postprocess.ImagePostProcessor
import com.google.jetpackcamera.core.camera.postprocess.ImagePostProcessorFeatureKey
import com.google.jetpackcamera.model.ImageOutputFormat
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Key identifying the HEIC transcoding post-processor. */
object HeicPostProcessorKey : ImagePostProcessorFeatureKey

/**
 * Turns a freshly captured JPEG into HEIC when the user selected [ImageOutputFormat.HEIC].
 *
 * The camera pipeline always delivers JPEG (CameraX has no HEIC output format); the capture path
 * registers the MediaStore row with the HEIC MIME type and `.heic` display name up front, so this
 * processor only has to look at the row's MIME type to know whether to act. Captures saved to an
 * explicit `Uri` or to the cache directory are never touched.
 *
 * Behaviour:
 * 1. Read the saved bytes. Skip if they are not a JPEG (already HEIC, DNG, ...).
 * 2. Encode to a temporary HEIC in the app cache and atomically overwrite the MediaStore entry,
 *    toggling `IS_PENDING` on Android 10+ so gallery apps never observe a half-written file.
 * 3. If the device cannot encode HEIC or the encoder fails, the JPEG is kept **as is** and the
 *    MediaStore row is corrected back to `image/jpeg` / `.jpg` so the photo remains valid.
 */
class HeicImagePostProcessor(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
    private val transcoder: HeicTranscoder = HeifWriterTranscoder(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val quality: Int = DEFAULT_QUALITY
) : ImagePostProcessor {

    override suspend fun postProcessImage(uri: Uri) {
        if (!uri.isMediaStoreUri()) return
        val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (!ImageOutputFormat.HEIC.mimeType.equals(mimeType, ignoreCase = true)) return

        withContext(ioDispatcher) {
            val jpeg = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (jpeg == null || !jpeg.isJpeg()) {
                Log.d(TAG, "Skipping $uri: content is not a JPEG")
                return@withContext
            }

            if (!transcoder.isSupported) {
                Log.w(TAG, "HEIC encoding unsupported on this device, keeping JPEG for $uri")
                revertToJpeg(uri)
                return@withContext
            }

            val tempFile = File.createTempFile("JCA_HEIC_", ".heic", cacheDir)
            try {
                transcoder.transcode(jpeg, tempFile, quality)
                replaceContents(uri, tempFile)
                Log.d(TAG, "Transcoded $uri to HEIC (${jpeg.size} B -> ${tempFile.length()} B)")
            } catch (e: Exception) {
                Log.e(TAG, "HEIC transcode failed for $uri, keeping JPEG", e)
                revertToJpeg(uri)
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun replaceContents(uri: Uri, source: File) {
        setPending(uri, pending = true)
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("ContentResolver returned null output stream for $uri")
        } finally {
            setPending(uri, pending = false)
        }
    }

    /** Points the MediaStore row back at what is really stored: a JPEG. */
    private fun revertToJpeg(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, ImageOutputFormat.JPEG.mimeType)
            currentDisplayName(uri)?.let { name ->
                val base = name.substringBeforeLast('.', name)
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    base + ImageOutputFormat.JPEG.fileExtension
                )
            }
        }
        runCatching { contentResolver.update(uri, values, null, null) }
            .onFailure { Log.w(TAG, "Unable to correct MIME type for $uri", it) }
    }

    private fun currentDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun setPending(uri: Uri, pending: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
        }
        runCatching { contentResolver.update(uri, values, null, null) }
    }

    private fun Uri.isMediaStoreUri(): Boolean =
        scheme == ContentResolver.SCHEME_CONTENT && authority == MediaStore.AUTHORITY

    private fun ByteArray.isJpeg(): Boolean =
        size > 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

    companion object {
        private const val TAG = "HeicImagePostProcessor"

        /**
         * Visually lossless for a camera still while keeping the ~50% size saving over JPEG.
         * Matches the level used by the Pixel camera's HEIC "storage saver" mode.
         */
        const val DEFAULT_QUALITY = 90
    }
}
