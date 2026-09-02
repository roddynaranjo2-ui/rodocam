/*
 * Copyright (C) 2023 The Android Open Source Project
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

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.legacy.kapt)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.compose.compiler)
}

/**
 * Release signing configuration.
 *
 * Resolved, in order of precedence, from:
 *  1. Environment variables (used by CI): RODOCAM_KEYSTORE_PATH, RODOCAM_KEYSTORE_PASSWORD,
 *     RODOCAM_KEY_ALIAS, RODOCAM_KEY_PASSWORD.
 *  2. An untracked `keystore.properties` file at the repository root (used locally) with the keys
 *     storeFile, storePassword, keyAlias, keyPassword.
 *
 * If neither is available the release build type falls back to the debug signing config so
 * `assembleRelease` keeps working for local smoke tests. Such an APK must NOT be uploaded to Play.
 */
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

fun resolveReleaseSigning(): ReleaseSigning? {
    val envPath = System.getenv("RODOCAM_KEYSTORE_PATH")
    if (!envPath.isNullOrBlank()) {
        val storePassword = System.getenv("RODOCAM_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("RODOCAM_KEY_ALIAS")
        val keyPassword = System.getenv("RODOCAM_KEY_PASSWORD") ?: storePassword
        if (!storePassword.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
            return ReleaseSigning(file(envPath), storePassword, keyAlias, keyPassword)
        }
        logger.warn("RODOCAM_KEYSTORE_PATH is set but the remaining RODOCAM_* variables are missing.")
    }

    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        val props = Properties().apply { propsFile.inputStream().use { load(it) } }
        val storeFile = props.getProperty("storeFile")
        val storePassword = props.getProperty("storePassword")
        val keyAlias = props.getProperty("keyAlias")
        val keyPassword = props.getProperty("keyPassword") ?: storePassword
        if (!storeFile.isNullOrBlank() && !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()
        ) {
            return ReleaseSigning(rootProject.file(storeFile), storePassword, keyAlias, keyPassword)
        }
        logger.warn("keystore.properties found but incomplete; release will use the debug key.")
    }
    return null
}

val releaseSigning: ReleaseSigning? = resolveReleaseSigning()

/**
 * versionCode is derived from the CI run number when available so every CI build produces a
 * strictly increasing code (required by Play). Locally it falls back to 1.
 */
val computedVersionCode: Int =
    System.getenv("RODOCAM_VERSION_CODE")?.toIntOrNull()
        ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        ?: 1
val computedVersionName: String = System.getenv("RODOCAM_VERSION_NAME") ?: "0.1.0"

android {
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    namespace = "com.google.jetpackcamera"

    defaultConfig {
        applicationId = "com.google.jetpackcamera"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = computedVersionCode
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseSigning != null) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "No release keystore configured (RODOCAM_* env or keystore.properties); " +
                        "release build will be signed with the debug key."
                )
                signingConfigs.getByName("debug")
            }
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    flavorDimensions += "flavor"
    productFlavors {
        create("stable") {
            dimension = "flavor"
            isDefault = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        managedDevices {
            localDevices {
                create("pixel2Api28") {
                    device = "Pixel 2"
                    apiLevel = 28
                }
                create("pixel8Api34") {
                    device = "Pixel 8"
                    apiLevel = 34
                    systemImageSource = "aosp_atd"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.tracing)
    implementation(project(":core:common"))
    implementation(project(":core:camera"))
    implementation(project(":core:camera:low-light"))
    implementation(project(":core:camera:postprocess"))
    implementation(project(":data:camera"))
    implementation(project(":data:media"))
    implementation(project(":feature:postcapture"))
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose - Material Design 3
    implementation(libs.compose.material3)

    // Compose - Android Studio Preview support
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Compose - Integration with ViewModels
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose - Integration with Activities
    implementation(libs.androidx.activity.compose)

    // Compose - Testing
    androidTestImplementation(libs.compose.junit)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.camera.lifecycle) // to reset CameraX between tests
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.testParameterInjector)
    androidTestImplementation(project(":ui:uistate"))
    androidTestImplementation(project(":ui:components:capture"))
    androidTestImplementation(project(":ui:debug"))
    androidTestUtil(libs.androidx.orchestrator)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Hilt
    implementation(libs.dagger.hilt.android)
    kapt(libs.dagger.hilt.compiler)

    // Accompanist - Permissions
    implementation(libs.accompanist.permissions)

    // Jetpack Navigation
    implementation(libs.androidx.navigation.compose)

    // Access settings & model data
    implementation(project(":data:settings"))
    implementation(project(":core:settings:datastore-prefs"))
    implementation(project(":core:settings"))
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)

    // Camera Preview
    implementation(project(":feature:preview"))

    // Settings Screen
    implementation(project(":feature:settings"))

    // Permissions Screen
    implementation(project(":feature:permissions"))
    // benchmark
    implementation(libs.androidx.profileinstaller)

    // Desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // capture components
    implementation(project(":ui:uistate"))
    implementation(project(":ui:components:capture"))
    implementation(project(":ui:debug"))

    // Low Light implementations
    implementation(project(":core:camera:low-light:low-light-di"))
    implementation(project(":core:camera:low-light-playservices-di"))

    // Postprocess implementations
    implementation(project(":core:camera:postprocess:postprocess-di"))

    implementation(project(":core:camera:low-light-playservices"))
    implementation(project(":core:camera:effects:single-stream"))
}

// Allow references to generated code
kapt {
    correctErrorTypes = true
}
