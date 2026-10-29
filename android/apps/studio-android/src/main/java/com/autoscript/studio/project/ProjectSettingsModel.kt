package com.autoscript.studio

import com.google.gson.JsonObject
import com.autoscript.project.store.ProjectManifestDocument
import com.autoscript.project.store.ProjectDebugSettings
import com.autoscript.project.store.ProjectRandomization

internal data class ProjectExecutionBaseline(val capabilities: Set<String>, val debug: ProjectDebugSettings,
    val randomization: ProjectRandomization) {
    companion object {
        fun from(manifest: ProjectManifestDocument) = ProjectExecutionBaseline(manifest.capabilities.toSet(), manifest.debugSettings, manifest.randomization)
    }
}

/** Resource/preview refreshes must not replace unsaved execution settings or weaken conflict checks. */
internal data class ProjectExecutionSettingsDraft(val baseline: ProjectExecutionBaseline,
    val capabilities: Set<String>, val run: ProjectRunSettingsDraft, val random: RandomizationForm) {
    val isDirty: Boolean get() = capabilities != baseline.capabilities || run != ProjectRunSettingsDraft.from(baseline.debug) ||
        random != RandomizationForm.from(baseline.randomization)
    fun conflictsWith(manifest: ProjectManifestDocument) = baseline != ProjectExecutionBaseline.from(manifest)
    fun observe(manifest: ProjectManifestDocument): ProjectExecutionSettingsDraft = if (!isDirty && conflictsWith(manifest)) from(manifest) else this
    companion object {
        fun from(manifest: ProjectManifestDocument): ProjectExecutionSettingsDraft {
            val base = ProjectExecutionBaseline.from(manifest)
            return ProjectExecutionSettingsDraft(base, base.capabilities, ProjectRunSettingsDraft.from(base.debug), RandomizationForm.from(base.randomization))
        }
    }
}

internal enum class ProjectSettingsPage(val title: String) {
    RESOURCES("项目资源"), CAPABILITIES("脚本能力"), INTERFACE("运行界面"), RUN("运行设置"),
}

internal fun defaultScriptCapabilities(): Set<String> =
    BlockCatalog.insertable.flatMap { it.requiredCapabilities }.toSet()

/** Keep manifest-only capabilities visible; opening settings must never silently drop them. */
internal fun settingsCapabilityOptions(supported: List<String>, declared: List<String>): List<String> =
    (supported + declared).distinct().sorted()

internal fun filterSettingsResources(resources: List<JsonObject>, kind: String?): List<JsonObject> =
    resources.filter { kind == null || it.get("kind")?.asString == kind }

internal fun settingsCapabilityTitle(capability: String): String = when (capability) {
    "core.task" -> "任务与提示"
    "core.math" -> "数值与随机工具"
    "device.display" -> "设备显示尺寸"
    "device.info" -> "只读设备与授权状态"
    "network.http" -> "HTTP与HTTPS网络请求"
    "device.apps" -> "应用查询与启动"
    "device.clipboard" -> "文字剪贴板"
    "audio.control" -> "音频播放与音量"
    "input.basic" -> "按键与触摸"
    "input.ime" -> "智构输入法状态与文字提交"
    "input.multi" -> "多指触摸输入"
    "screen.capture" -> "屏幕截图"
    "screen.capture.series" -> "连续帧采集"
    "storage.data" -> "项目私有文件"
    "ui.control" -> "脚本界面与控件"
    "ui.floating" -> "浮窗与绘图"
    "ui.floating.interactive" -> "可点击拖动图片浮窗"
    "vision.pixel" -> "像素与颜色"
    "vision.pixel.legacy" -> "兼容颜色识别"
    "vision.template" -> "模板找图"
    "vision.opencv" -> "灰度找图"
    "ocr.glyph" -> "字库识别"
    "ocr.onnx" -> "字母数字识别"
    else -> capability
}
