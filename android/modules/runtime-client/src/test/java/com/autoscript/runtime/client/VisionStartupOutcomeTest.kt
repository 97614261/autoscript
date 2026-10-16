package com.autoscript.runtime.client

import com.autoscript.runtime.api.RuntimeProtocol as P
import org.junit.Assert.*
import org.junit.Test

class VisionStartupOutcomeTest {
    @Test fun onlyExactSuccessCanStart() {
        assertTrue(visionStartupOutcome(P.VISION_PREPARE_ACCEPTED, "", "").ready)
        assertFalse(visionStartupOutcome(P.VISION_PREPARE_ACCEPTED, "onnx", "AVAILABLE").ready)
        for (status in 1..7) assertFalse(visionStartupOutcome(status, "", "").ready)
    }
    @Test fun messagesUseOnlyFixedIdentifiers() {
        assertEquals("灰度找图：未选择组件", visionStartupOutcome(P.VISION_PREPARE_FAILED, "opencv", "MISSING").message)
        assertEquals("字母数字识别：组件已更换，请重启智构", visionStartupOutcome(P.VISION_PREPARE_FAILED, "onnx", "RESTART_REQUIRED").message)
        for ((kind, state) in listOf("private-path" to "MISSING", "onnx" to "private-exception")) {
            val result = visionStartupOutcome(P.VISION_PREPARE_FAILED, kind, state)
            assertFalse(result.ready); assertEquals("视觉组件准备返回无效结果", result.message)
        }
    }
}
