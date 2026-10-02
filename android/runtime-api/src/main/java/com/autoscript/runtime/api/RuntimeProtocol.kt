package com.autoscript.runtime.api

object RuntimeProtocol {
    const val VERSION: Int = 20
    const val SERVICE_CLASS: String = "com.autoscript.runtime.service.AutomationRuntimeService"

    /** Private same-package handoff from the Runner screenshot overlay to Studio. */
    const val ACTION_OPEN_CAPTURE_EDITOR: String =
        "com.autoscript.runtime.action.OPEN_CAPTURE_EDITOR"
    const val EXTRA_CAPTURE_PROJECT_ID: String = "capture_project_id"
    const val EXTRA_CAPTURE_TOKEN: String = "capture_token"
    const val EXTRA_CAPTURE_WIDTH: String = "capture_width"
    const val EXTRA_CAPTURE_HEIGHT: String = "capture_height"

    const val STATE_IDLE: Int = 1
    const val STATE_RUNNING: Int = 2
    const val STATE_STOPPED: Int = 3
    const val STATE_FAILED: Int = 4
    const val STATE_STOPPING: Int = 5
    const val STATE_PAUSED: Int = 6

    const val ROOT_STOPPED: Int = 1
    const val ROOT_STARTING: Int = 2
    const val ROOT_READY: Int = 3
    const val ROOT_FAILED: Int = 4
    const val INPUT_FEATURE_BASIC: Int = 1
    const val INPUT_FEATURE_SINGLE_POINTER: Int = 2

    const val STOP_ACCEPTED: Int = 0
    const val STOP_SESSION_MISMATCH: Int = 1
    const val STOP_ENGINE_ERROR: Int = 2

    const val CONTROL_ACCEPTED: Int = 0
    const val CONTROL_SESSION_MISMATCH: Int = 1
    const val CONTROL_INVALID_STATE: Int = 2
    const val CONTROL_ENGINE_ERROR: Int = 3

    const val SURFACE_ACCEPTED: Int = 0
    const val SURFACE_SESSION_MISMATCH: Int = 1
    const val SURFACE_PERMISSION_DENIED: Int = 2

    const val START_ACCEPTED: Int = 0
    const val START_SESSION_MISMATCH: Int = 1
    const val START_INVALID_SCRIPT: Int = 2
    const val START_BACKEND_NOT_READY: Int = 3
    const val START_INVALID_PROJECT: Int = 4
    const val START_FOREGROUND_UNAVAILABLE: Int = 5

    const val PREPARE_ACCEPTED: Int = 0
    const val PREPARE_SESSION_MISMATCH: Int = 1
    const val PREPARE_BUSY: Int = 2
    const val PREPARE_ENGINE_ERROR: Int = 3

    const val VALIDATION_VALID: Int = 0
    const val VALIDATION_INVALID: Int = 1
    const val VALIDATION_SESSION_MISMATCH: Int = 2
    const val VALIDATION_ENGINE_ERROR: Int = 3

    const val VISUAL_COMPILE_ACCEPTED: Int = 0
    const val VISUAL_COMPILE_INVALID: Int = 1
    const val VISUAL_COMPILE_SESSION_MISMATCH: Int = 2
    const val VISUAL_COMPILE_ENGINE_ERROR: Int = 3

    const val SCALE_LETTERBOX: Int = 0
    const val SCALE_CROP: Int = 1
    const val SCALE_STRETCH: Int = 2

    const val TEMPLATE_ACCEPTED: Int = 0
    const val TEMPLATE_SESSION_MISMATCH: Int = 1
    const val TEMPLATE_INVALID_PATH: Int = 2
    const val TEMPLATE_INVALID_IMAGE: Int = 3
    const val TEMPLATE_ENGINE_ERROR: Int = 4

    const val DICTIONARY_ACCEPTED: Int = 0
    const val DICTIONARY_SESSION_MISMATCH: Int = 1
    const val DICTIONARY_INVALID_PATH: Int = 2
    const val DICTIONARY_INVALID_FILE: Int = 3
    const val DICTIONARY_ENGINE_ERROR: Int = 4
}
