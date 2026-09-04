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
 * A type-safe identifier for a camera effect.
 */
@JvmInline
value class CameraEffectId(val value: String)

/**
 * Constant representing no active camera effect.
 */
val NONE_EFFECT_ID = CameraEffectId("none")

/**
 * Identifier of the built-in viewfinder assist effect (preview-only GPU shader that draws focus
 * peaking and/or per-pixel zebra stripes). The effect is selected implicitly when
 * [ViewfinderAssistSettings.needsShaderEffect] is true and no other effect is active; it never
 * applies to photo or video outputs.
 */
val VIEWFINDER_ASSIST_EFFECT_ID = CameraEffectId("viewfinder_assist")
