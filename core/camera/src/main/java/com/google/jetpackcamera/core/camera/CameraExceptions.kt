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

/**
 * Base type for recoverable errors surfaced by [CameraSystem].
 *
 * These are *expected* failure modes (a use case not bound yet, unsupported configuration, ...)
 * that callers must handle by informing the user, never by crashing the process.
 */
open class CameraSystemException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Thrown by [CameraSystem.takePicture] when there is no bound `ImageCapture` use case.
 *
 * Typical causes: the app is in a video-only capture mode, or a capture was requested during the
 * short window in which the session is being re-bound (lens flip, aspect ratio / HDR change).
 */
class ImageCaptureUnavailableException :
    CameraSystemException("Image capture is not available in the current camera configuration")

/**
 * Thrown by [CameraSystem.startVideoRecording] when there is no bound `VideoCapture` use case
 * (e.g. the app is in image-only capture mode).
 */
class VideoCaptureUnavailableException :
    CameraSystemException("Video capture is not available in the current camera configuration")
