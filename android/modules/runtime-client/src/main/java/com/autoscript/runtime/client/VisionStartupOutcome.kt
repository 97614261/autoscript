package com.autoscript.runtime.client

import com.autoscript.runtime.api.RuntimeProtocol as P

internal data class VisionStartupOutcome(val ready: Boolean, val message: String)

internal fun visionStartupOutcome(status: Int, component: String, state: String): VisionStartupOutcome {
    if (status == P.VISION_PREPARE_ACCEPTED && component.isEmpty() && state.isEmpty()) return VisionStartupOutcome(true, "")
    if (status == P.VISION_PREPARE_FAILED) {
        val kind = when (component) { "opencv" -> "灰度找图"; "onnx" -> "字母数字识别"; else -> return VisionStartupOutcome(false, "视觉组件准备返回无效结果") }
        val reason = when (state) {
            "MISSING" -> "未选择组件"; "INCOMPATIBLE" -> "组件版本不兼容"; "CORRUPT" -> "组件损坏"
            "DEVICE_UNSUPPORTED" -> "设备不支持"; "RESTART_REQUIRED" -> "组件已更换，请重启智构"
            "BUSY" -> "组件正在使用，请稍后重试"; "LOAD_FAILED" -> "加载或初始化失败"
            else -> return VisionStartupOutcome(false, "视觉组件准备返回无效结果")
        }
        return VisionStartupOutcome(false, "$kind：$reason")
    }
    if (component.isNotEmpty() || state.isNotEmpty()) return VisionStartupOutcome(false, "视觉组件准备返回无效结果")
    return VisionStartupOutcome(false, when (status) {
        P.VISION_PREPARE_CANCELLED -> "视觉组件准备已取消"
        P.VISION_PREPARE_BUSY -> "已有视觉组件准备任务，请稍后重试"
        P.VISION_PREPARE_INVALID -> "视觉组件需求无效，请重新编译"
        P.VISION_PREPARE_STALE -> "运行环境已变化，请重试"
        P.VISION_PREPARE_TIMEOUT -> "视觉组件准备超时，请重试或检查组件"
        else -> "视觉组件准备返回无效结果"
    })
}
