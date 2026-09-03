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

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

// Robolectric no soporta todavia compileSdk 37 / targetSdk 36: fijamos un SDK soportado
// (>= Q, necesario para la ruta IS_PENDING de MediaStore que ejercita el test).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class HeicImagePostProcessorTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var cacheDir: File
    private lateinit var mediaStore: FakeMediaStoreProvider

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        val context = ApplicationProvider.getApplicationContext<Context>()
        contentResolver = context.contentResolver
        cacheDir = context.cacheDir
        mediaStore = FakeMediaStoreProvider()
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, mediaStore)
    }

    @Test
    fun postProcessImage_ignoresNonMediaStoreUris() = runTest {
        val transcoder = FakeTranscoder(supported = true)
        val processor = newProcessor(transcoder)

        processor.postProcessImage(Uri.parse("file:///tmp/IMG_1.heic"))

        assertThat(transcoder.calls).isEqualTo(0)
    }

    @Test
    fun postProcessImage_ignoresJpegMimeType() = runTest {
        val uri = insertMediaStoreImage("IMG_1.jpg", "image/jpeg", JPEG_BYTES)
        val transcoder = FakeTranscoder(supported = true)

        newProcessor(transcoder).postProcessImage(uri)

        assertThat(transcoder.calls).isEqualTo(0)
    }

    @Test
    fun postProcessImage_transcodesHeicRow() = runTest {
        val uri = insertMediaStoreImage("IMG_1.heic", "image/heic", JPEG_BYTES)
        val transcoder = FakeTranscoder(supported = true, output = HEIC_BYTES)

        newProcessor(transcoder).postProcessImage(uri)

        assertThat(transcoder.calls).isEqualTo(1)
        assertThat(transcoder.lastQuality).isEqualTo(HeicImagePostProcessor.DEFAULT_QUALITY)
        assertThat(transcoder.lastInput).isEqualTo(JPEG_BYTES)
        assertThat(readBytes(uri)).isEqualTo(HEIC_BYTES)
        assertThat(contentResolver.getType(uri)).isEqualTo("image/heic")
        assertThat(displayName(uri)).isEqualTo("IMG_1.heic")
    }

    @Test
    fun postProcessImage_unsupportedDevice_revertsRowToJpeg() = runTest {
        val uri = insertMediaStoreImage("IMG_1.heic", "image/heic", JPEG_BYTES)
        val transcoder = FakeTranscoder(supported = false)

        newProcessor(transcoder).postProcessImage(uri)

        assertThat(transcoder.calls).isEqualTo(0)
        assertThat(readBytes(uri)).isEqualTo(JPEG_BYTES)
        assertThat(contentResolver.getType(uri)).isEqualTo("image/jpeg")
        assertThat(displayName(uri)).isEqualTo("IMG_1.jpg")
    }

    @Test
    fun postProcessImage_encoderFailure_keepsJpegBytes() = runTest {
        val uri = insertMediaStoreImage("IMG_1.heic", "image/heic", JPEG_BYTES)
        val transcoder = FakeTranscoder(supported = true, failWith = IllegalStateException("boom"))

        newProcessor(transcoder).postProcessImage(uri)

        assertThat(transcoder.calls).isEqualTo(1)
        assertThat(readBytes(uri)).isEqualTo(JPEG_BYTES)
        assertThat(contentResolver.getType(uri)).isEqualTo("image/jpeg")
        assertThat(displayName(uri)).isEqualTo("IMG_1.jpg")
    }

    @Test
    fun postProcessImage_skipsWhenContentIsNotJpeg() = runTest {
        val uri = insertMediaStoreImage("IMG_1.heic", "image/heic", HEIC_BYTES)
        val transcoder = FakeTranscoder(supported = true)

        newProcessor(transcoder).postProcessImage(uri)

        assertThat(transcoder.calls).isEqualTo(0)
        assertThat(readBytes(uri)).isEqualTo(HEIC_BYTES)
    }

    private fun newProcessor(transcoder: HeicTranscoder) = HeicImagePostProcessor(
        contentResolver = contentResolver,
        cacheDir = cacheDir,
        transcoder = transcoder
    )

    private fun insertMediaStoreImage(
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        val uri = checkNotNull(
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        )
        val sink = ByteArrayOutputStream()
        shadowOf(contentResolver).registerInputStream(uri, bytes.inputStream())
        shadowOf(contentResolver).registerOutputStream(uri, sink)
        mediaStore.contents[uri] = bytes
        sinks[uri] = sink
        return uri
    }

    private val sinks = mutableMapOf<Uri, ByteArrayOutputStream>()

    private fun displayName(uri: Uri): String? =
        mediaStore.rows.getValue(uri).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)

    /** Bytes the row currently holds: what was written through the resolver, else the original. */
    private fun readBytes(uri: Uri): ByteArray {
        val written = sinks.getValue(uri).toByteArray()
        return if (written.isNotEmpty()) written else mediaStore.contents.getValue(uri)
    }

    /**
     * Minimal MediaStore stand-in: keeps MIME type / display name per row so `getType`, `update`
     * and `query` behave like the real provider for the columns the processor touches.
     */
    private class FakeMediaStoreProvider : ContentProvider() {
        val rows = mutableMapOf<Uri, ContentValues>()
        val contents = mutableMapOf<Uri, ByteArray>()
        private var nextId = 1L

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val rowUri = Uri.withAppendedPath(uri, (nextId++).toString())
            rows[rowUri] = ContentValues(values)
            return rowUri
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            val row = rows[uri] ?: return 0
            values?.let { row.putAll(it) }
            return 1
        }

        override fun getType(uri: Uri): String? =
            rows[uri]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val cursor = MatrixCursor(columns)
            rows[uri]?.let { row -> cursor.addRow(columns.map { row.getAsString(it) }) }
            return cursor
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            if (rows.remove(uri) != null) 1 else 0
    }

    private class FakeTranscoder(
        private val supported: Boolean,
        private val output: ByteArray = HEIC_BYTES,
        private val failWith: Exception? = null
    ) : HeicTranscoder {
        var calls = 0
        var lastQuality = -1
        var lastInput: ByteArray? = null

        override val isSupported: Boolean
            get() = supported

        override fun transcode(jpeg: ByteArray, output: File, quality: Int) {
            calls++
            lastQuality = quality
            lastInput = jpeg
            failWith?.let { throw it }
            output.writeBytes(this.output)
        }
    }

    private companion object {
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val HEIC_BYTES = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63)
    }
}
