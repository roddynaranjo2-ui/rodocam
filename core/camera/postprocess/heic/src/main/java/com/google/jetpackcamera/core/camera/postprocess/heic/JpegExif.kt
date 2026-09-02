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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, allocation-light JPEG/EXIF helpers used when transcoding a camera JPEG to HEIC.
 *
 * Only the pieces we need are implemented: locating the APP1 "Exif" segment and rewriting the
 * `Orientation` tag in IFD0. Everything else in the EXIF block (capture time, exposure, make and
 * model, GPS, ...) is copied to the HEIC untouched.
 */
object JpegExif {
    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_SOI = 0xD8
    private const val MARKER_SOS = 0xDA
    private const val MARKER_EOI = 0xD9
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_RST0 = 0xD0
    private const val MARKER_RST7 = 0xD7

    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
    private const val TIFF_HEADER_OFFSET = 6
    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3
    private const val IFD_ENTRY_SIZE = 12

    /** EXIF `Orientation` value meaning "already upright". */
    const val ORIENTATION_NORMAL: Short = 1

    /**
     * Returns the EXIF data block (starting with `Exif\0\0` followed by the TIFF header) embedded
     * in [jpeg], or `null` if the file has no APP1/Exif segment or is malformed.
     */
    fun extractExifBlock(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != MARKER_PREFIX ||
            (jpeg[1].toInt() and 0xFF) != MARKER_SOI
        ) {
            return null
        }
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if ((jpeg[pos].toInt() and 0xFF) != MARKER_PREFIX) return null
            val marker = jpeg[pos + 1].toInt() and 0xFF
            // Padding bytes / stand-alone markers carry no length field.
            if (marker == MARKER_PREFIX) {
                pos++
                continue
            }
            if (marker == MARKER_SOS || marker == MARKER_EOI) return null
            if (marker in MARKER_RST0..MARKER_RST7 || marker == 0x01) {
                pos += 2
                continue
            }
            val length =
                ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > jpeg.size) return null
            val payloadStart = pos + 4
            val payloadLength = length - 2
            if (marker == MARKER_APP1 && payloadLength > EXIF_HEADER.size &&
                startsWith(jpeg, payloadStart, EXIF_HEADER)
            ) {
                return jpeg.copyOfRange(payloadStart, payloadStart + payloadLength)
            }
            pos += 2 + length
        }
        return null
    }

    /**
     * Returns a copy of [exifBlock] whose IFD0 `Orientation` tag is set to [orientation].
     *
     * Used after the pixels have been rotated to upright so the HEIC does not carry a stale
     * rotation hint. If the block cannot be parsed or has no `Orientation` tag it is returned
     * unchanged.
     */
    fun withOrientation(exifBlock: ByteArray, orientation: Short = ORIENTATION_NORMAL): ByteArray {
        if (exifBlock.size < TIFF_HEADER_OFFSET + 8 || !startsWith(exifBlock, 0, EXIF_HEADER)) {
            return exifBlock
        }
        val tiff = ByteBuffer
            .wrap(exifBlock, TIFF_HEADER_OFFSET, exifBlock.size - TIFF_HEADER_OFFSET)
            .slice()
        val byteOrder = when (tiff.getShort(0).toInt() and 0xFFFF) {
            0x4949 -> ByteOrder.LITTLE_ENDIAN
            0x4D4D -> ByteOrder.BIG_ENDIAN
            else -> return exifBlock
        }
        tiff.order(byteOrder)
        if ((tiff.getShort(2).toInt() and 0xFFFF) != 0x002A) return exifBlock
        val ifd0Offset = tiff.getInt(4)
        if (ifd0Offset < 8 || ifd0Offset + 2 > tiff.limit()) return exifBlock
        val entryCount = tiff.getShort(ifd0Offset).toInt() and 0xFFFF
        var entry = ifd0Offset + 2
        repeat(entryCount) {
            if (entry + IFD_ENTRY_SIZE > tiff.limit()) return exifBlock
            val tag = tiff.getShort(entry).toInt() and 0xFFFF
            val type = tiff.getShort(entry + 2).toInt() and 0xFFFF
            val count = tiff.getInt(entry + 4)
            if (tag == TAG_ORIENTATION && type == TYPE_SHORT && count == 1) {
                val patched = exifBlock.copyOf()
                ByteBuffer.wrap(patched).order(byteOrder)
                    .putShort(TIFF_HEADER_OFFSET + entry + 8, orientation)
                return patched
            }
            entry += IFD_ENTRY_SIZE
        }
        return exifBlock
    }

    private fun startsWith(array: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > array.size) return false
        for (i in prefix.indices) {
            if (array[offset + i] != prefix[i]) return false
        }
        return true
    }
}
