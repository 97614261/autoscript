package com.autoscript.runtime.api

/** Failure-only text payload. Never propagate SDK exception messages or paths through this channel. */
object RecognitionPreviewErrors {
    private val codes = setOf("VISION_COMPONENT_NOT_INSTALLED", "VISION_COMPONENT_MISSING", "VISION_COMPONENT_CHECKING",
        "VISION_COMPONENT_CORRUPT", "VISION_COMPONENT_INCOMPATIBLE", "VISION_COMPONENT_DEVICE_UNSUPPORTED",
        "VISION_COMPONENT_LOAD_FAILED", "VISION_COMPONENT_RESTART_REQUIRED", "VISION_COMPONENT_BUSY",
        "VISION_INITIALIZATION_FAILED", "VISION_LIBRARY_UNAVAILABLE", "VISION_CANCELLED_OR_TIMEOUT",
        "VISION_CLEANUP_FAILED", "VISION_CLOSED", "VISION_BUSY", "VISION_OPERATION_FAILED")
    fun accepted(value: String): String = value.takeIf { it.length <= 64 && it in codes }.orEmpty()
}
