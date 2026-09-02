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
package com.google.jetpackcamera.model

/**
 * Vendor camera extension (CameraX Extensions / Camera2 `CameraExtensionSession`) applied to the
 * whole session.
 *
 * These are the OEM-implemented computational modes exposed by the device: on a Pixel they map to
 * Night Sight, Portrait blur, HDR+ and Face retouching; on Samsung devices to the equivalent
 * One UI camera modes. When a mode other than [NONE] is active the vendor pipeline owns exposure
 * and processing, so manual (Pro) controls, Ultra HDR and RAW are unavailable and the app falls
 * back to a single JPEG.
 *
 * WARNING: The string representation of this enum is persisted in Preferences DataStore. Renaming
 * constants breaks compatibility with existing saved settings.
 */
enum class CameraExtensionMode {
    /** No vendor extension; the regular CameraX pipeline is used. */
    NONE,

    /** Long-exposure / multi-frame low-light mode (Pixel "Night Sight"). */
    NIGHT,

    /** Depth-based background blur for portraits. */
    BOKEH,

    /** Vendor multi-frame HDR (distinct from Ultra HDR output and HLG10 video). */
    HDR,

    /** Skin smoothing / face retouching, typically for the front camera. */
    FACE_RETOUCH;

    /** Whether a vendor extension pipeline replaces the standard one. */
    val isEnabled: Boolean
        get() = this != NONE

    companion object {
        /** Modes offered to the user, in UI order. [NONE] is always implied. */
        val SELECTABLE_MODES: List<CameraExtensionMode> = listOf(NIGHT, BOKEH, HDR, FACE_RETOUCH)
    }
}
