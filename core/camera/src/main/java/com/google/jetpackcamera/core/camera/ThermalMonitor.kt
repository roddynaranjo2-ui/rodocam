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
package com.google.jetpackcamera.core.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.jetpackcamera.model.ThermalStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

private const val TAG = "ThermalMonitor"

/**
 * Source of the device [ThermalStatus]. The default implementation wraps
 * [PowerManager.addThermalStatusListener] (API 29+); on older devices the status is always
 * [ThermalStatus.UNKNOWN], which the [com.google.jetpackcamera.model.ThermalPolicy] treats as
 * "no restriction".
 */
fun interface ThermalMonitor {
    /**
     * Emits the current status immediately, then every change. Completes only when cancelled.
     */
    fun thermalStatus(): Flow<ThermalStatus>

    companion object {
        /** Monitor that never reports throttling (tests, unsupported platforms). */
        val NONE = ThermalMonitor { flowOf(ThermalStatus.UNKNOWN) }

        /** Platform-backed monitor for [context]. */
        fun create(context: Context): ThermalMonitor {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return NONE
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return NONE
            return PowerManagerThermalMonitor(powerManager)
        }
    }
}

/** [ThermalMonitor] backed by [PowerManager] thermal status callbacks. */
@RequiresApi(Build.VERSION_CODES.Q)
internal class PowerManagerThermalMonitor(
    private val powerManager: PowerManager
) : ThermalMonitor {
    override fun thermalStatus(): Flow<ThermalStatus> = callbackFlow {
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            val mapped = ThermalStatus.fromPlatformStatus(status)
            Log.d(TAG, "Thermal status changed: $status -> $mapped")
            trySend(mapped)
        }
        // The platform invokes the listener once with the current status on registration.
        trySend(ThermalStatus.fromPlatformStatus(powerManager.currentThermalStatus))
        powerManager.addThermalStatusListener(listener)
        awaitClose { powerManager.removeThermalStatusListener(listener) }
    }.distinctUntilChanged()
}
