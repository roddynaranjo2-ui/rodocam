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
package com.google.jetpackcamera.ui.controller.impl

import android.os.SystemClock
import android.util.Log
import androidx.tracing.Trace
import com.google.jetpackcamera.core.camera.CameraSystem
import com.google.jetpackcamera.core.common.traceFirstFramePreview
import com.google.jetpackcamera.model.DeviceRotation
import com.google.jetpackcamera.ui.controller.CameraController
import com.google.jetpackcamera.ui.uistate.capture.compound.CaptureUiState
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "CameraControllerImpl"

/**
 * Implementation of [CameraController] that manages the camera lifecycle.
 *
 * Start/stop requests are serialized through a [Mutex] and the previous session is always
 * cancelled **and joined** before a new one is started. This guarantees that the previous
 * session's `unbindAll()` cleanup has fully completed before the new session calls
 * `bindToLifecycle`, avoiding the race where a stale cleanup unbinds the freshly bound
 * use cases (black viewfinder after rotation / returning from Settings).
 *
 * Any failure while initializing or running the camera (e.g. `IllegalArgumentException` from an
 * unsupported surface combination on LIMITED devices, `CameraUnavailableException`, HAL errors)
 * is caught and reported through [onCameraError] instead of crashing the process.
 *
 * @param initializationDeferred A [Deferred] that completes when the camera system is initialized.
 * @param captureUiState The [StateFlow] of the capture UI state.
 * @param cameraSystem The [CameraSystem] to interact with.
 * @param coroutineContext The [CoroutineContext] for launching coroutines.
 * @param onCameraError Callback invoked (from a coroutine in this controller's scope) when the
 * camera could not be initialized or its session failed unexpectedly.
 */
class CameraControllerImpl(
    private val initializationDeferred: Deferred<Unit>,
    private val captureUiState: StateFlow<CaptureUiState>,
    private val cameraSystem: CameraSystem,
    coroutineContext: CoroutineContext,
    private val onCameraError: (Throwable) -> Unit = { throwable ->
        Log.e(TAG, "Unhandled camera error", throwable)
    }
) : CameraController {
    private var runningCameraJob: Job? = null
    private val lifecycleMutex = Mutex()
    private val job = Job(parent = coroutineContext[Job])
    private val scope = CoroutineScope(coroutineContext + job)

    override fun startCamera() {
        Log.d(TAG, "startCamera")
        scope.launch {
            lifecycleMutex.withLock {
                // Make sure the previous session has completely torn down before starting a
                // new one. cancelAndJoin() (instead of cancel()) is what prevents the double-bind
                // race described in the class docs.
                runningCameraJob?.cancelAndJoin()
                runningCameraJob = launchCameraSession()
            }
        }
    }

    private fun CoroutineScope.launchCameraSession(): Job = launch {
        if (Trace.isEnabled()) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                val startTraceTimestamp: Long = SystemClock.elapsedRealtimeNanos()
                traceFirstFramePreview(cookie = 1) {
                    captureUiState.transformWhile {
                        var continueCollecting = true
                        (it as? CaptureUiState.Ready)?.let { uiState ->
                            if (uiState.sessionFirstFrameTimestamp > startTraceTimestamp) {
                                emit(Unit)
                                continueCollecting = false
                            }
                        }
                        continueCollecting
                    }.collect {}
                }
            }
        }
        try {
            // Ensure CameraSystem is initialized before starting camera
            initializationDeferred.await()
            cameraSystem.runCamera()
        } catch (e: CancellationException) {
            // Normal teardown (stopCamera / scope cancellation). Never swallow cancellation.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Camera session failed", e)
            onCameraError(e)
        }
    }

    override fun stopCamera() {
        Log.d(TAG, "stopCamera")
        scope.launch {
            lifecycleMutex.withLock {
                runningCameraJob?.cancelAndJoin()
                runningCameraJob = null
            }
        }
    }

    override fun tapToFocus(x: Float, y: Float) {
        Log.d(TAG, "tapToFocus")
        scope.launch {
            try {
                cameraSystem.tapToFocus(x, y)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Focus/metering can legitimately fail (camera closing, unsupported region).
                // It must never take the whole viewfinder down.
                Log.w(TAG, "tapToFocus failed", e)
            }
        }
    }

    override fun setDisplayRotation(deviceRotation: DeviceRotation) {
        scope.launch {
            cameraSystem.setDeviceRotation(deviceRotation)
        }
    }

    /**
     * Initiates the cancellation of this controller's scope and returns its Job.
     * To wait for cancellation to complete, call .join() on the returned Job.
     */
    fun cancelScope(): Job {
        scope.cancel()
        return scope.coroutineContext.job
    }
}
