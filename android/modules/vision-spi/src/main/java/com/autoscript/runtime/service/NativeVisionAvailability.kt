package com.autoscript.runtime.service

/** Stable status vocabulary shared by Studio, runtime-service and providers. */
enum class NativeVisionAvailabilityState {
    AVAILABLE, MISSING, INCOMPATIBLE, CORRUPT, DEVICE_UNSUPPORTED,
    CHECKING, INSTALLED, LOAD_FAILED, RESTART_REQUIRED, BUSY,
}

data class NativeVisionAvailability(
    val kind: String,
    val state: NativeVisionAvailabilityState,
    val detail: String? = null,
)
