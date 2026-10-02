package com.autoscript.studio

import com.google.gson.JsonObject

internal enum class ProjectSettingsPage(val title: String) {
    RESOURCES("项目资源"), CAPABILITIES("脚本能力"), INTERFACE("运行界面"),
}

/** Keep manifest-only capabilities visible; opening settings must never silently drop them. */
internal fun settingsCapabilityOptions(supported: List<String>, declared: List<String>): List<String> =
    (supported + declared).distinct().sorted()

internal fun filterSettingsResources(resources: List<JsonObject>, kind: String?): List<JsonObject> =
    resources.filter { kind == null || it.get("kind")?.asString == kind }

internal fun settingsCapabilityTitle(capability: String): String = when (capability) {
    "core.task" -> "任务与提示"
    "input.basic" -> "按键与触摸"
    "screen.capture" -> "屏幕截图"
    "vision.pixel" -> "像素与颜色"
    "vision.pixel.legacy" -> "兼容颜色识别"
    "vision.template" -> "模板找图"
    "vision.opencv" -> "灰度找图"
    "ocr.glyph" -> "字库识别"
    "ocr.onnx" -> "字母数字识别"
    else -> capability
}
