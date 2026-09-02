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
package com.google.jetpackcamera.ui.components.capture

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
const val CAPTURE_BUTTON = "CaptureButton"
const val CAPTURE_MODE_TOGGLE_BUTTON = "CaptureModeToggleButton"
const val FLIP_CAMERA_BUTTON = "FlipCameraButton"
const val SNACKBAR_NODE_TAG = "SnackbarNodeTag"

const val IMAGE_WELL_TAG = "ImageWellTag"

const val PREVIEW_DISPLAY = "PreviewDisplay"
const val SCREEN_FLASH_OVERLAY = "ScreenFlashOverlay"
const val AUDIO_INPUT_TOGGLE = "AudioInputToggle"
const val FOCUS_METERING_INDICATOR_TAG = "FocusMeteringIndicatorTag"
const val FOCUS_LOCK_BADGE_TAG = "FocusLockBadgeTag"

enum class AudioInputState {
    OFF,
    READY,
    INCOMING
}

val AudioStateProperty = SemanticsPropertyKey<AudioInputState>("AudioState")
var SemanticsPropertyReceiver.audioState by AudioStateProperty

const val ZOOM_BUTTON_ROW_TAG = "ZoomButtonRowTag"
const val ZOOM_BUTTON_MIN_TAG = "ZoomButtonMinTag"
const val ZOOM_BUTTON_1_TAG = "ZoomButton1Tag"
const val ZOOM_BUTTON_2_TAG = "ZoomButton2Tag"
const val ZOOM_BUTTON_5_TAG = "ZoomButton5Tag"

/**
 * Builds a deterministic test tag for zoom levels that don't have a dedicated constant
 * (e.g. 3x optical telephoto, 10x). `3.0f` -> "ZoomButton3Tag", `0.6f` -> "ZoomButton0_6Tag".
 */
fun zoomButtonTestTagFor(zoomRatio: Float): String {
    val normalized = if (zoomRatio == zoomRatio.toInt().toFloat()) {
        zoomRatio.toInt().toString()
    } else {
        zoomRatio.toString().replace('.', '_')
    }
    return "ZoomButton${normalized}Tag"
}

// debug component tags

const val ELAPSED_TIME_TAG = "ElapsedTimeTag"
const val VIDEO_QUALITY_TAG = "VideoQualityTag"

// quick settings tags
// todo(kc): rename quick_settings_drop_down to something more appropriate?
const val QUICK_SETTINGS_DROP_DOWN = "QuickSettingsDropDown"
const val SETTINGS_BUTTON = "SettingsButton"

const val QUICK_SETTINGS_BOTTOM_SHEET = "QuickSettingsBottomSheet"

const val QUICK_SETTINGS_RATIO_3_4_BUTTON = "QuickSettingsRatio3:4Button"
const val QUICK_SETTINGS_RATIO_9_16_BUTTON = "QuickSettingsRatio9:16Button"
const val QUICK_SETTINGS_RATIO_1_1_BUTTON = "QuickSettingsRatio1:1Button"

// quick settings capture mode
const val BTN_QUICK_SETTINGS_CAPTURE_MODE_OPTION_STANDARD =
    "quick_settings_capture_mode_btn_option_standard"
const val BTN_QUICK_SETTINGS_CAPTURE_MODE_OPTION_VIDEO_ONLY =
    "quick_settings_capture_mode_btn_option_video_only"
const val BTN_QUICK_SETTINGS_CAPTURE_MODE_OPTION_IMAGE_ONLY =
    "quick_settings_capture_mode_btn_option_image_only"

const val BTN_QUICK_SETTINGS_FLASH_OPTION_OFF = "btn_quick_settings_flash_option_off"
const val BTN_QUICK_SETTINGS_FLASH_OPTION_ON = "btn_quick_settings_flash_option_on"
const val BTN_QUICK_SETTINGS_FLASH_OPTION_AUTO = "btn_quick_settings_flash_option_auto"
const val BTN_QUICK_SETTINGS_FLASH_OPTION_LOW_LIGHT_BOOST =
    "btn_quick_settings_flash_option_low_light_boost"

const val BTN_QUICK_SETTINGS_HDR_OPTION_ON = "btn_quick_settings_hdr_option_on"
const val BTN_QUICK_SETTINGS_HDR_OPTION_OFF = "btn_quick_settings_hdr_option_off"

const val BTN_QUICK_SETTINGS_EXTENSION_OPTION_OFF = "btn_quick_settings_extension_option_off"
const val BTN_QUICK_SETTINGS_EXTENSION_OPTION_NIGHT = "btn_quick_settings_extension_option_night"
const val BTN_QUICK_SETTINGS_EXTENSION_OPTION_BOKEH = "btn_quick_settings_extension_option_bokeh"
const val BTN_QUICK_SETTINGS_EXTENSION_OPTION_HDR = "btn_quick_settings_extension_option_hdr"
const val BTN_QUICK_SETTINGS_EXTENSION_OPTION_FACE_RETOUCH =
    "btn_quick_settings_extension_option_face_retouch"

const val ROW_QUICK_SETTINGS_CAPTURE_MODE = "row_quick_settings_capture_mode"
const val ROW_QUICK_SETTINGS_HDR = "row_quick_settings_hdr"
const val ROW_QUICK_SETTINGS_EXTENSION_MODE = "row_quick_settings_extension_mode"
const val ROW_QUICK_SETTINGS_ASPECT_RATIO = "row_quick_settings_aspect_ratio"
const val ROW_QUICK_SETTINGS_FLASH = "row_quick_settings_flash"

// Pro (manual) controls
const val PRO_MODE_TOGGLE_TAG = "pro_mode_toggle_tag"
const val PRO_PANEL_TAG = "pro_panel_tag"
const val PRO_CHIP_ISO_TAG = "pro_chip_iso_tag"
const val PRO_CHIP_SHUTTER_TAG = "pro_chip_shutter_tag"
const val PRO_CHIP_EV_TAG = "pro_chip_ev_tag"
const val PRO_CHIP_WB_TAG = "pro_chip_wb_tag"
const val PRO_CHIP_FOCUS_TAG = "pro_chip_focus_tag"
const val PRO_CHIP_AE_LOCK_TAG = "pro_chip_ae_lock_tag"
const val PRO_RESET_TAG = "pro_reset_tag"
const val PRO_SLIDER_ISO_TAG = "pro_slider_iso_tag"
const val PRO_SLIDER_SHUTTER_TAG = "pro_slider_shutter_tag"
const val PRO_SLIDER_EV_TAG = "pro_slider_ev_tag"
const val PRO_SLIDER_FOCUS_TAG = "pro_slider_focus_tag"
const val PRO_WB_CHIP_PREFIX = "pro_wb_chip_"
const val PRO_CHIP_SHADOWS_TAG = "pro_chip_shadows_tag"
const val PRO_SLIDER_SHADOWS_TAG = "pro_slider_shadows_tag"
const val PRO_CHIP_WB_KELVIN_TAG = "pro_chip_wb_kelvin_tag"
const val PRO_SLIDER_WB_KELVIN_TAG = "pro_slider_wb_kelvin_tag"
