package com.autoscript.studio

import com.autoscript.project.store.ProjectDesign
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class RecordedAction(
    val kind: Int, val startMs: Int, val durationMs: Int,
    val x1: Int, val y1: Int, val x2: Int, val y2: Int, val keyCode: Int,
) {
    val label: String get() = when (kind) {
        1 -> "点击 ($x1, $y1)"
        2 -> "长按 ($x1, $y1) · ${durationMs}ms"
        3 -> "滑动 ($x1, $y1) → ($x2, $y2) · ${durationMs}ms"
        4 -> "设备按键 $keyCode"
        else -> "无效动作"
    }
}

/** Untrusted IPC bytes: reject the entire draft, never salvage/truncate a corrupt recording. */
internal fun decodeRecordedActions(bytes: ByteArray, width: Int, height: Int, elapsedMs: Int): List<RecordedAction> {
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 33_554_432) { "录制分辨率无效" }
    require(elapsedMs in 0..60_000 && bytes.size <= 512 * 32 && bytes.size % 32 == 0) { "录制结果超过预算或格式无效" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val actions = ArrayList<RecordedAction>(bytes.size / 32)
    var previousEnd = 0L
    while (buffer.hasRemaining()) {
        val action = RecordedAction(buffer.int, buffer.int, buffer.int, buffer.int, buffer.int, buffer.int, buffer.int, buffer.int)
        val end = action.startMs.toLong() + action.durationMs
        require(action.kind in 1..4 && action.startMs >= previousEnd && action.durationMs in 0..5000 && end <= elapsedMs) { "录制动作时间或类型无效" }
        if (action.kind == 4) {
            require(action.keyCode in com.autoscript.runtime.api.RecordingReply.KEY_CODES && action.durationMs == 0) { "录制按键不在执行白名单" }
            require(listOf(action.x1,action.y1,action.x2,action.y2).all { it == 0 }) { "按键录制数据含非法坐标" }
        } else {
            require(action.x1 in 0 until width && action.x2 in 0 until width && action.y1 in 0 until height && action.y2 in 0 until height && action.keyCode == 0) { "录制坐标越界" }
            require(when (action.kind) { 1 -> action.durationMs in 1..499; 2 -> action.durationMs in 500..5000; else -> action.durationMs in 50..5000 }) { "手势时长无法执行" }
            require(action.kind == 3 || (action.x1 == action.x2 && action.y1 == action.y2)) { "点击/长按不能包含拖动坐标" }
        }
        actions += action
        previousEnd = end
    }
    return actions
}

private fun mapRecordedPoint(x: Int, y: Int, width: Int, height: Int, design: ProjectDesign): Pair<Int,Int> {
    require(design.width in 1..8192 && design.height in 1..8192 && design.scaleMode in setOf("letterbox","crop","stretch")) { "项目坐标配置无效" }
    val sx = width.toDouble() / design.width
    val sy = height.toDouble() / design.height
    val scaleX = when (design.scaleMode) { "letterbox" -> minOf(sx,sy); "crop" -> maxOf(sx,sy); else -> sx }
    val scaleY = if (design.scaleMode == "stretch") sy else scaleX
    val px = (x - (width - design.width * scaleX) / 2) / scaleX
    val py = (y - (height - design.height * scaleY) / 2) / scaleY
    require(px >= 0 && py >= 0 && px < design.width && py < design.height) { "录制点位落在项目留黑区域，不能夹到边缘冒充原点位" }
    return px.roundToInt().coerceIn(0, design.width - 1) to py.roundToInt().coerceIn(0, design.height - 1)
}

internal fun recordedSnippet(actions: List<RecordedAction>, width: Int, height: Int, design: ProjectDesign, keepIntervals: Boolean): ImageToolCodeGen.Snippet {
    require(actions.isNotEmpty() && actions.size <= 512) { "没有录制动作" }
    val blocks = mutableListOf<ImageToolCodeGen.FlowBlockInsertion>()
    val code = mutableListOf<String>()
    var previousEnd = actions.first().startMs
    actions.forEach { action ->
        val delay = action.startMs - previousEnd
        require(delay >= 0) { "录制动作时间重叠" }
        if (keepIntervals && delay > 0) {
            blocks += ImageToolCodeGen.FlowBlockInsertion("task.sleep",mapOf("milliseconds" to delay), exactTiming = true)
            code += "Task.sleep($delay)"
        }
        if (action.kind == 4) {
            blocks += ImageToolCodeGen.FlowBlockInsertion("input.keyevent",mapOf("keyCode" to action.keyCode))
            code += "Input.keyEvent(${action.keyCode})"
        } else {
            val start = mapRecordedPoint(action.x1,action.y1,width,height,design)
            val end = mapRecordedPoint(action.x2,action.y2,width,height,design)
            if (action.kind == 1) {
                blocks += ImageToolCodeGen.FlowBlockInsertion("input.tap",mapOf("x" to start.first,"y" to start.second))
                code += "Input.tap(${start.first}, ${start.second})"
            } else {
                val args = mapOf("x1" to start.first,"y1" to start.second,"x2" to end.first,"y2" to end.second,"durationMs" to action.durationMs)
                blocks += ImageToolCodeGen.FlowBlockInsertion("input.swipe",args)
                code += "Input.swipe(${start.first}, ${start.second}, ${end.first}, ${end.second}, ${action.durationMs})"
            }
        }
        previousEnd = action.startMs + action.durationMs
    }
    require(blocks.size <= 256) { "本次需要${blocks.size}个积木，最多一次加入256个；请删除部分动作或分次录制" }
    val lua = code.joinToString("\n", postfix = "\n")
    return ImageToolCodeGen.Snippet(lua,"录制 ${actions.size} 个动作 · ${blocks.size} 个积木",LuaSnippetGate.reject(lua),blocks)
}

internal fun recordingFailureLabel(status: Int): String = when (status) {
    1 -> "设备没有受支持的 Root 输入事件源"
    2 -> "录制检测到多指操作，本批仅支持单指"
    3 -> "录制超过事件/动作预算，请分段录制"
    4 -> "录制已取消，未加入任何动作"
    5 -> "录制到达时限，请重新分段录制"
    6 -> "存在未完成触摸，未提交草稿"
    7 -> "输入事件无效或录制期间屏幕方向变化"
    8 -> "该手势不能映射为现有可取消输入动作"
    9 -> "录制会话已变化，结果作废"
    10 -> "运行或录制正在使用输入后端"
    else -> "录制引擎失败（$status）"
}
