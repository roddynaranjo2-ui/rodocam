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

import android.content.Context
import com.google.jetpackcamera.core.camera.postprocess.ImagePostProcessor
import com.google.jetpackcamera.core.camera.postprocess.ImagePostProcessorFeatureKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.AbstractMap
import javax.inject.Provider

/**
 * Hilt module contributing [HeicImagePostProcessor] to the image post-processor map consumed by
 * the camera system.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object HeicPostProcessModule {
    @Provides
    @IntoSet
    fun provideHeicPostProcessorEntry(
        @ApplicationContext context: Context
    ): Map.Entry<
        ImagePostProcessorFeatureKey,
        @JvmSuppressWildcards Provider<ImagePostProcessor>
        > =
        AbstractMap.SimpleImmutableEntry(
            HeicPostProcessorKey,
            Provider {
                HeicImagePostProcessor(
                    contentResolver = context.contentResolver,
                    cacheDir = context.cacheDir
                )
            }
        )
}
