package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RuntimeFailureHintTest {
    @Test fun captureFailuresAreExplainedBeforeGenericLuaErrors() {
        val prefix = "LUA_RUNTIME_ERROR SCREEN_CAPTURE_FAILED "
        assertTrue(runtimeFailureHint(prefix + "CAPTURE_DEADLINE").contains("尚未进入找图"))
        assertTrue(runtimeFailureHint(prefix + "CAPTURE_CANCELLED").contains("停止或取消"))
        assertTrue(runtimeFailureHint(prefix + "CAPTURE_CONTROL_UNAVAILABLE").contains("停止通道"))
        assertFalse(runtimeFailureHint(prefix + "CAPTURE_CONTROL_UNAVAILABLE").contains("采集超过5秒"))
        assertTrue(runtimeFailureHint(prefix + "CAPTURE_PROTOCOL").contains("认证"))
        assertTrue(runtimeFailureHint(prefix + "CAPTURE_PROCESS_EXIT").contains("回退"))
        assertTrue(runtimeFailureHint(prefix).contains("不能判定"))
    }
    @Test fun visionTimeoutBudgetAndCancellationHaveDistinctHintsBeforeGenericLuaError() {
        assertTrue(runtimeFailureHint("LUA_RUNTIME_ERROR FRAME_OPERATION_FAILED VISION_SEARCH_TIMEOUT").contains("超过5秒"))
        assertTrue(runtimeFailureHint("LUA_RUNTIME_ERROR VISION_COMPARISON_BUDGET_EXCEEDED").contains("像素比较预算"))
        assertTrue(runtimeFailureHint("LUA_RUNTIME_ERROR VISION_SEARCH_CANCELLED").contains("停止或取消"))
    }
    @Test fun currentVisualEditorAndRootReadinessAreExplained() {
        assertTrue(runtimeFailureHint("CAPABILITY_DENIED input.basic").contains("可视化编辑器"))
        for (code in listOf("ROOT_NOT_READY", "ROOT_DISCONNECTED", "ROOT_INPUT_FAILED")) {
            assertTrue(runtimeFailureHint(code).contains("连接成功不代表 Root 已就绪"))
        }
        assertTrue(runtimeFailureHint("LUA_RUNTIME_ERROR").contains("不要直接修改构建产物"))
    }

    @Test fun diagnosticHintDoesNotCopyPrivateErrorPayload() {
        val privatePayload = "PRIVATE_SCRIPT_TEXT_123"
        assertFalse(runtimeFailureHint("LUA_RUNTIME_ERROR $privatePayload").contains(privatePayload))
    }
    @Test fun realStopCleanupFailureIsNotReportedAsNormalCancellation() {
        val hint = runtimeFailureHint("ROOT_STOP_CLEANUP_FAILED")
        assertTrue(hint.contains("禁止继续输入"))
        assertTrue(hint.contains("不是正常停止"))
    }
}
