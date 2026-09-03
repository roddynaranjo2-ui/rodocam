# Rodocam release R8 rules.
#
# The default `proguard-android-optimize.txt` already handles Android framework entry points,
# AndroidX and Compose (they ship their own consumer rules). This file only adds what the
# project itself needs.

# --- Debugging -------------------------------------------------------------------------------
# Keep line numbers so Play Console / Crashlytics stack traces are readable after obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin / coroutines ----------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
# kotlinx.atomicfu rewrites atomic fields at compile time; keep the runtime helpers it may reference.
-keep class kotlinx.atomicfu.** { *; }
-dontwarn kotlinx.atomicfu.**

# --- Hilt / Dagger ----------------------------------------------------------------------------
# Hilt ships consumer rules, but generated `_HiltModules` / `Hilt_*` classes are looked up by name.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-dontwarn dagger.hilt.**

# --- CameraX / Camera2 interop ----------------------------------------------------------------
# Camera2Interop + ExperimentalCamera2Interop reach into vendor extensions by reflection.
-keep class androidx.camera.camera2.interop.** { *; }
-keep class androidx.camera.camera2.internal.** { *; }
-keep class androidx.camera.extensions.** { *; }
-dontwarn androidx.camera.**

# --- JNI (core:camera:effects:single-stream) --------------------------------------------------
# Native methods must keep their names so `System.loadLibrary("opengl_debug_lib")` can bind them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.google.jetpackcamera.core.camera.effects.GLDebug { *; }

# --- Google Play Services (low-light boost) ---------------------------------------------------
-keep class com.google.android.gms.cameralowlight.** { *; }
-dontwarn com.google.android.gms.**

# --- Enums serialized into DataStore / JSON debug output --------------------------------------
# Settings are persisted by enum name; renaming them would silently reset user preferences.
-keepclassmembers enum com.google.jetpackcamera.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.google.jetpackcamera.settings.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Misc -------------------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
