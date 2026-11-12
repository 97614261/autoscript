package com.autoscript.studio

import androidx.compose.ui.graphics.Color

internal enum class EditorExecutionEmphasis(val label: String, val background: Color, val accent: Color) {
    NONE("", Color.White, Color(0xFF4769D6)),
    EXECUTING("执行中", Color(0xFFF0EAFF), Color(0xFF8251CF)),
    RECENT("刚执行", Color(0xFFF0F3FA), Color(0xFF7886A4)),
    NEXT("下一步", Color(0xFFFFF0CE), Color(0xFFAA741B)),
    READING("读取位置", Color(0xFFF0F3FA), Color(0xFF64758F)),
    ENDED("已结束", Color(0xFFEAF5F0), Color(0xFF2C7C63)),
    STOPPED("已停止", Color(0xFFF0F3FA), Color(0xFF64758F)),
    FAILED("执行出错", Color(0xFFFFEDED), Color(0xFFBE4343)),
}

/** Presentation only; eligible identities and pause snapshots are validated upstream. */
internal fun editorExecutionEmphasis(current: Boolean, executing: Boolean, recent: Boolean): EditorExecutionEmphasis = when {
    !current -> EditorExecutionEmphasis.NONE
    recent -> EditorExecutionEmphasis.RECENT
    executing -> EditorExecutionEmphasis.EXECUTING
    else -> EditorExecutionEmphasis.NEXT
}
