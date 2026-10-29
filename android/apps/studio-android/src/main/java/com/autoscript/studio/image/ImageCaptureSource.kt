package com.autoscript.studio

internal enum class ImageCaptureSource(val title: String, val description: String) {
    EACH_CALL("每次截图", "每次识别重新截图，用完自动释放。"),
    SHARED_IMAGE("共用图像", "读取所选图像，不截图、不自动释放；多项识别后统一释放。"),
    ;

    val capture: Boolean get() = this == EACH_CALL

    companion object {
        fun fromCapture(capture: Boolean) = if (capture) EACH_CALL else SHARED_IMAGE
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: EACH_CALL
    }
}

/** Defaults apply only while creating image-tool blocks, never when editing saved nodes. */
internal fun initialImageCaptureSource(
    creating: Boolean,
    autoCapture: Boolean,
    default: ImageCaptureSource,
): ImageCaptureSource = if (creating) default else ImageCaptureSource.fromCapture(autoCapture)

internal fun recognitionCanWrapCapture(kind: String): Boolean = kind in setOf(
    "vision.findallmulticolor", "vision.components", "ocr.glyphdetails", "vision.findscaled",
    "vision.findfeatures", "ocr.alphanumericv5",
)
