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
package com.google.jetpackcamera.model

val DEFAULT_HDR_IMAGE_OUTPUT = ImageOutputFormat.JPEG_ULTRA_HDR

/** MIME type and file extension of an Adobe DNG raw file. */
const val DNG_MIME_TYPE = "image/x-adobe-dng"
const val DNG_FILE_EXTENSION = ".dng"

/**
 * WARNING: The string representation of this enum is serialized and persisted in Preferences DataStore.
 * Renaming constants will break compatibility with existing saved settings.
 */
enum class ImageOutputFormat(
    /** MIME type written to MediaStore for the *primary* capture in this format. */
    val mimeType: String,
    /** File extension (including the leading dot) used for the *primary* capture in this format. */
    val fileExtension: String
) {
    JPEG(mimeType = "image/jpeg", fileExtension = ".jpg"),

    // Ultra HDR is a backwards-compatible JPEG (gain map embedded), so it keeps the JPEG MIME.
    JPEG_ULTRA_HDR(mimeType = "image/jpeg", fileExtension = ".jpg"),

    /**
     * Captures a developed JPEG **and** an Adobe DNG raw file from the same shutter press.
     *
     * The primary output (what the rest of the app treats as "the photo": thumbnail, image well,
     * post-capture) is the JPEG; the DNG is written alongside it with [DNG_MIME_TYPE] and
     * [DNG_FILE_EXTENSION]. Requires `REQUEST_AVAILABLE_CAPABILITIES_RAW` on the sensor.
     */
    RAW_JPEG(mimeType = "image/jpeg", fileExtension = ".jpg"),

    /**
     * HEIC (HEVC-in-HEIF) still image. The camera HAL delivers a JPEG which is transcoded to
     * HEIC on a background dispatcher; if the device has no HEVC image encoder the capture is
     * kept as JPEG. Roughly half the size of JPEG at equal quality (Pixel "storage saver").
     */
    HEIC(mimeType = "image/heic", fileExtension = ".heic");

    /** Whether captures in this format produce a secondary DNG file. */
    val producesRaw: Boolean
        get() = this == RAW_JPEG

    /** Whether the JPEG delivered by the camera must be transcoded after saving. */
    val requiresTranscode: Boolean
        get() = this == HEIC

    /** Whether the primary output embeds an Ultra HDR gain map. */
    val isUltraHdr: Boolean
        get() = this == JPEG_ULTRA_HDR
}
