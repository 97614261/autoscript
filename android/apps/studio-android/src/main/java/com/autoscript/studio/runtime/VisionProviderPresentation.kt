package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState

/** Read only the closed status vocabulary; never display untrusted remote text. */
internal data class VisionProviderStatus(val state: String, val source: String, val loaded: Boolean) {
    val label: String get() = LABELS.getValue(state)
    companion object {
        private val LABELS = mapOf("CHECKING" to "待复查", "INSTALLED" to "已校验 · 尚未加载", "AVAILABLE" to "后端可用 · 未代表识别验收",
            "MISSING" to "缺少所选组件", "INCOMPATIBLE" to "版本不兼容", "CORRUPT" to "内容校验失败", "DEVICE_UNSUPPORTED" to "当前进程 ABI 不支持",
            "LOAD_FAILED" to "加载或初始化失败", "RESTART_REQUIRED" to "更换后需重启进程", "BUSY" to "检查忙，请重试")
        fun parse(raw: String?): VisionProviderStatus? {
            if (raw == null || raw.length > 128) return null
            val parts = raw.split(',')
            if (parts.size != 3 || parts[0] !in LABELS || !parts[1].startsWith("source=") || !parts[2].startsWith("loaded=")) return null
            val source = parts[1].removePrefix("source=").takeIf { it in setOf("bundled", "provider") } ?: return null
            val loaded = when (parts[2]) { "loaded=true" -> true; "loaded=false" -> false; else -> return null }
            return VisionProviderStatus(parts[0], source, loaded)
        }
    }
}

internal fun canManageVisionProviders(state: RuntimeConnectionState): Boolean = state.phase == RuntimeConnectionPhase.CONNECTED &&
    state.sessionGeneration != null && state.sessionGeneration != 0L &&
    state.engineState in setOf(RuntimeEngineState.IDLE, RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED)

internal class VisionProviderMutationContext(private val expected: RuntimeConnectionState) {
    fun matches(current: RuntimeConnectionState): Boolean = canManageVisionProviders(expected) && canManageVisionProviders(current) &&
        expected.sessionGeneration == current.sessionGeneration && expected.stateRevision == current.stateRevision
}
