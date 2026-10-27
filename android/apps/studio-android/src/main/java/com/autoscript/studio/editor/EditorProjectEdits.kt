package com.autoscript.studio

internal data class EditorHistoryEntry(val source: String, val group: Long? = null)
internal data class EditorStateCheckpoint(val source: String, val primary: String?, val selection: Set<String>,
    val undo: List<EditorHistoryEntry>, val redo: List<EditorHistoryEntry>)

/** One grouped history boundary, without replacing local history or owning the editor session. */
internal class EditorProjectEdits {
    private var nextGroup = 0L
    private val groups = linkedMapOf<Long, Map<String, VisualEditorState>>()

    fun apply(before: Map<String, String>, after: Map<String, String>, editors: Map<String, VisualEditorState>): String? {
        val targets = after.filter { (id, source) -> source != before[id] }
        if (targets.isEmpty()) return "没有需要修改的行"
        if (targets.size > 32 || targets.entries.sumOf { (id, source) ->
                source.toByteArray(Charsets.UTF_8).size.toLong() + before[id].orEmpty().toByteArray(Charsets.UTF_8).size } > 32L * 1_048_576)
            return "整批修改超过32个插件或32MiB，请缩小范围"
        val participants = targets.keys.associateWith { editors[it] ?: return "插件已不存在，未修改" }
        if (participants.any { (id, editor) -> !editor.canReplaceSource(before[id].orEmpty(), targets.getValue(id)) })
            return "插件内容或结构已变化，请重新预览"
        groups.entries.removeAll { (token, members) -> members.values.none { token in it.historyGroups() } }
        if (participants.size > 1 && groups.size >= 64) return "整批撤销记录已满，请完成当前编辑会话后再操作"
        val checkpoints = participants.mapValues { it.value.checkpoint() }
        try {
            participants.forEach { (id, editor) ->
                check(editor.replaceRawSource(before.getValue(id), targets.getValue(id)) == null)
                check(editor.currentSource != checkpoints.getValue(id).source)
            }
            if (participants.size > 1) {
                val token = ++nextGroup
                participants.values.forEach { it.tagLastUndo(token) }
                groups[token] = participants
            }
        } catch (_: Exception) {
            participants.forEach { (id, editor) -> editor.restoreCheckpoint(checkpoints.getValue(id)) }
            return "整批修改未完成，所有插件均已恢复原草稿和撤销记录"
        }
        return null
    }

    fun moveHistory(editor: VisualEditorState, redo: Boolean, editors: Map<String, VisualEditorState>): String? {
        val token = (if (redo) editor.redoGroup else editor.undoGroup) ?: return "没有整批撤销记录"
        val members = groups[token] ?: return "整批记录已失效，不能只撤回其中一个插件"
        if (members.any { (id, state) -> editors[id] !== state || (if (redo) state.redoGroup else state.undoGroup) != token })
            return "其他插件还有后续修改或历史已变化，请先处理后续修改；本次没有改动任何插件"
        val checkpoints = members.mapValues { it.value.checkpoint() }
        try {
            members.values.forEach { check(if (redo) it.redo(token) else it.undo(token)) }
        } catch (_: Exception) {
            members.forEach { (id, state) -> state.restoreCheckpoint(checkpoints.getValue(id)) }
            return "整批撤销未完成，已保留全部原内容"
        }
        return null
    }
}
