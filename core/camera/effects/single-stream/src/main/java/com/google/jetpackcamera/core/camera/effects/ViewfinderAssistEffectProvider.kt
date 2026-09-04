/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.google.jetpackcamera.core.camera.effects

import androidx.camera.core.CameraEffect
import com.google.jetpackcamera.core.camera.ViewfinderAssistAwareEffectProvider
import com.google.jetpackcamera.model.CameraEffectTarget
import com.google.jetpackcamera.model.ViewfinderAssistSettings
import kotlinx.coroutines.CoroutineScope

/**
 * Provides the viewfinder assist [CameraEffect]. Only the preview is targeted so that photos and
 * videos are never "burned" with the highlight or the stripes.
 *
 * The shader parameters are derived from the [ViewfinderAssistSettings] of the session being
 * bound (see [create]), so toggling focus peaking / zebras or moving the zebra threshold is
 * reflected on the next rebind.
 */
internal class ViewfinderAssistEffectProvider : ViewfinderAssistAwareEffectProvider {

    override val targets: Set<CameraEffectTarget> = setOf(CameraEffectTarget.PREVIEW)

    /** Creates the effect with the default (peaking-only) configuration. */
    override fun create(coroutineScope: CoroutineScope): CameraEffect =
        ViewfinderAssistEffect(coroutineScope, DEFAULT_CONFIG)

    override fun create(
        coroutineScope: CoroutineScope,
        viewfinderAssist: ViewfinderAssistSettings
    ): CameraEffect = ViewfinderAssistEffect(coroutineScope, configFor(viewfinderAssist))

    companion object {
        private val DEFAULT_CONFIG =
            ViewfinderAssistEffectConfig(peaking = FocusPeakingConfig.DEFAULT)

        /**
         * Shader configuration for [settings]. Falls back to peaking-only when the effect is
         * requested although no shader feature is enabled (settings raced with the rebind), so
         * the camera session never crashes.
         */
        internal fun configFor(settings: ViewfinderAssistSettings): ViewfinderAssistEffectConfig =
            ViewfinderAssistEffectConfig.from(settings) ?: DEFAULT_CONFIG
    }
}
