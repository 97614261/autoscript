package com.autoscript.studio

/** Desired Runner contents, not proof of component installation or device support. */
internal enum class RunnerTemplateFamily { BASE, OPENCV, ONNX, FULL }

internal data class RunnerDependencyPlan(val openCv: Boolean, val onnx: Boolean) {
    val family: RunnerTemplateFamily get() = when {
        openCv && onnx -> RunnerTemplateFamily.FULL
        openCv -> RunnerTemplateFamily.OPENCV
        onnx -> RunnerTemplateFamily.ONNX
        else -> RunnerTemplateFamily.BASE
    }
    val componentLabels: List<String> get() = buildList {
        add("基础 Runner / Rust 图像")
        if (openCv) add("OpenCV")
        if (onnx) { add("ONNX Runtime"); add("字母数字模型") }
    }

    companion object {
        fun fromCompiledRequirements(components: Set<String>): RunnerDependencyPlan {
            require(components.size <= 2 && components.all { it == "opencv" || it == "onnx" }) { "视觉组件需求无效" }
            return RunnerDependencyPlan("opencv" in components, "onnx" in components)
        }
        fun fromCapabilities(capabilities: Collection<String>) = RunnerDependencyPlan(
            openCv = "vision.opencv" in capabilities,
            onnx = "ocr.onnx" in capabilities,
        )
    }
}
