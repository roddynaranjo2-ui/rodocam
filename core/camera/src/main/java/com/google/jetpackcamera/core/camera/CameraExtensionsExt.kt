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
package com.google.jetpackcamera.core.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import com.google.jetpackcamera.model.CameraExtensionMode

private const val TAG = "CameraExtensionsExt"

/** Maps the app-level extension mode to the CameraX [ExtensionMode] constant. */
fun CameraExtensionMode.toCameraXExtensionMode(): Int = when (this) {
    CameraExtensionMode.NONE -> ExtensionMode.NONE
    CameraExtensionMode.NIGHT -> ExtensionMode.NIGHT
    CameraExtensionMode.BOKEH -> ExtensionMode.BOKEH
    CameraExtensionMode.HDR -> ExtensionMode.HDR
    CameraExtensionMode.FACE_RETOUCH -> ExtensionMode.FACE_RETOUCH
}

/**
 * Obtains the process-wide [ExtensionsManager], or `null` when the vendor extension library is
 * missing or fails to initialise (common on emulators and some OEM builds). Failures are logged
 * and treated as "no extensions available" so the rest of the pipeline keeps working.
 */
suspend fun getExtensionsManagerOrNull(
    context: Context,
    cameraProvider: CameraProvider
): ExtensionsManager? = try {
    ExtensionsManager.getInstance(context, cameraProvider)
} catch (e: Exception) {
    Log.w(TAG, "CameraX Extensions unavailable", e)
    null
}

/**
 * Returns the [CameraExtensionMode]s the camera selected by [cameraSelector] can bind.
 *
 * Probing each mode is cheap once the manager is initialised; any per-mode exception (seen on
 * devices with partially implemented vendor libraries) simply excludes that mode.
 */
fun ExtensionsManager.supportedExtensionModes(
    cameraSelector: CameraSelector
): Set<CameraExtensionMode> = CameraExtensionMode.SELECTABLE_MODES.filterTo(mutableSetOf()) {
    try {
        isExtensionAvailable(cameraSelector, it.toCameraXExtensionMode())
    } catch (e: Exception) {
        Log.w(TAG, "Unable to query availability of $it", e)
        false
    }
}

/**
 * Wraps [baseSelector] so binding it starts a vendor extension session for [mode].
 *
 * Returns [baseSelector] unchanged for [CameraExtensionMode.NONE], when the mode is not available
 * on that camera, or if the extensions library throws (the caller then gets a normal session
 * instead of a crash).
 */
fun ExtensionsManager?.extensionEnabledSelector(
    baseSelector: CameraSelector,
    mode: CameraExtensionMode
): CameraSelector {
    if (this == null || !mode.isEnabled) return baseSelector
    val cxMode = mode.toCameraXExtensionMode()
    return try {
        if (isExtensionAvailable(baseSelector, cxMode)) {
            getExtensionEnabledCameraSelector(baseSelector, cxMode)
        } else {
            Log.w(TAG, "$mode requested but not available on selected camera")
            baseSelector
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create extension selector for $mode", e)
        baseSelector
    }
}
