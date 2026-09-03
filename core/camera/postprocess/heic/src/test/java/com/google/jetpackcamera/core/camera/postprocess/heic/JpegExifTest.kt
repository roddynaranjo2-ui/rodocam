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

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class JpegExifTest {

    @Test
    fun extractExifBlock_returnsNullForNonJpeg() {
        assertThat(JpegExif.extractExifBlock(byteArrayOf(0x00, 0x01, 0x02, 0x03))).isNull()
        assertThat(JpegExif.extractExifBlock(ByteArray(0))).isNull()
    }

    @Test
    fun extractExifBlock_returnsNullWhenNoApp1Segment() {
        val jpeg = buildJpeg(app1 = null)
        assertThat(JpegExif.extractExifBlock(jpeg)).isNull()
    }

    @Test
    fun extractExifBlock_returnsExifPayload() {
        val exif = buildExifBlock(orientation = 6, ByteOrder.BIG_ENDIAN)
        val jpeg = buildJpeg(app1 = exif)

        val extracted = JpegExif.extractExifBlock(jpeg)

        assertThat(extracted).isEqualTo(exif)
    }

    @Test
    fun extractExifBlock_skipsPrecedingApp0Segment() {
        val exif = buildExifBlock(orientation = 3, ByteOrder.LITTLE_ENDIAN)
        val jpeg = buildJpeg(app1 = exif, includeApp0 = true)

        assertThat(JpegExif.extractExifBlock(jpeg)).isEqualTo(exif)
    }

    @Test
    fun withOrientation_patchesBigEndianTag() {
        val exif = buildExifBlock(orientation = 6, ByteOrder.BIG_ENDIAN)

        val patched = JpegExif.withOrientation(exif)

        assertThat(readOrientation(patched)).isEqualTo(JpegExif.ORIENTATION_NORMAL.toInt())
        // Everything but the orientation value is untouched.
        assertThat(patched.size).isEqualTo(exif.size)
        assertThat(readOrientation(exif)).isEqualTo(6)
    }

    @Test
    fun withOrientation_patchesLittleEndianTag() {
        val exif = buildExifBlock(orientation = 8, ByteOrder.LITTLE_ENDIAN)

        val patched = JpegExif.withOrientation(exif, orientation = 1)

        assertThat(readOrientation(patched)).isEqualTo(1)
    }

    @Test
    fun withOrientation_returnsInputWhenMalformed() {
        val garbage = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00, 0x00)
        assertThat(JpegExif.withOrientation(garbage)).isSameInstanceAs(garbage)
    }

    private fun readOrientation(exif: ByteArray): Int {
        val tiff = ByteBuffer.wrap(exif, 6, exif.size - 6).slice()
        tiff.order(
            if ((tiff.getShort(0).toInt() and 0xFFFF) == 0x4949) {
                ByteOrder.LITTLE_ENDIAN
            } else {
                ByteOrder.BIG_ENDIAN
            }
        )
        val ifd0 = tiff.getInt(4)
        val count = tiff.getShort(ifd0).toInt()
        var entry = ifd0 + 2
        repeat(count) {
            if ((tiff.getShort(entry).toInt() and 0xFFFF) == 0x0112) {
                return tiff.getShort(entry + 8).toInt() and 0xFFFF
            }
            entry += 12
        }
        error("orientation tag missing")
    }

    /** Builds `Exif\0\0` + TIFF header + IFD0 with two entries (Make placeholder + Orientation). */
    private fun buildExifBlock(orientation: Int, order: ByteOrder): ByteArray {
        val tiff = ByteBuffer.allocate(8 + 2 + 12 * 2 + 4).order(order)
        tiff.putShort(if (order == ByteOrder.LITTLE_ENDIAN) 0x4949 else 0x4D4D)
        tiff.putShort(0x002A)
        tiff.putInt(8) // IFD0 offset
        tiff.putShort(2) // entry count
        // Entry 1: ImageWidth (LONG, 1) = 4000 -> exercises the loop skipping non-matching tags.
        tiff.putShort(0x0100)
        tiff.putShort(4)
        tiff.putInt(1)
        tiff.putInt(4000)
        // Entry 2: Orientation (SHORT, 1)
        tiff.putShort(0x0112)
        tiff.putShort(3)
        tiff.putInt(1)
        tiff.putShort(orientation.toShort())
        tiff.putShort(0)
        tiff.putInt(0) // next IFD
        return byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff.array()
    }

    private fun buildJpeg(app1: ByteArray?, includeApp0: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        if (includeApp0) {
            val jfif = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0, 1, 0, 1, 0, 0)
            out.write(0xFF)
            out.write(0xE0)
            writeLength(out, jfif.size + 2)
            out.write(jfif)
        }
        if (app1 != null) {
            out.write(0xFF)
            out.write(0xE1)
            writeLength(out, app1.size + 2)
            out.write(app1)
        }
        // Start of scan followed by a trailing EOI so the parser has somewhere to stop.
        out.write(0xFF)
        out.write(0xDA)
        writeLength(out, 2)
        out.write(0xFF)
        out.write(0xD9)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        out.write((length shr 8) and 0xFF)
        out.write(length and 0xFF)
    }
}
