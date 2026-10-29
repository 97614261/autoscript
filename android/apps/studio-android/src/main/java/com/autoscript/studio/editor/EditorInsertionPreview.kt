package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class EditorInsertionPreviewLine(val label: String, val depth: Int, val marker: Boolean = false)
internal data class EditorInsertionPreviewData(val hint: String, val lines: List<EditorInsertionPreviewLine>)

/** Project the exact sibling/child boundary; never use the next flattened node as "after". */
internal fun editorInsertionPreview(nodes: List<EditorTreeNode>, anchorId: String?, position: EditorInsertPosition,
    childSlot: String? = null): EditorInsertionPreviewData {
    val rows = editorTreeDisplayRows(nodes.map { it.copy(expanded = true) })
    val anchor = rows.indexOfFirst { it.nodeId == anchorId && anchorId != null }
    fun subtreeEnd(index: Int): Int {
        var end = index + 1
        while (end < rows.size && rows[end].depth > rows[index].depth) end++
        return end
    }
    var boundary = rows.size
    var depth = 0
    var hint = "加入插件末尾"
    if (position != EditorInsertPosition.LIST_BOTTOM) {
        if (anchor < 0) return EditorInsertionPreviewData("原选择行已不存在，请重新选择", emptyList())
        val node = (rows[anchor] as EditorTreeDisplayRow.Node).node
        val anchorEnd = subtreeEnd(anchor)
        depth = rows[anchor].depth
        when (position) {
            EditorInsertPosition.ABOVE -> { boundary = anchor; hint = "加入本行之前" }
            EditorInsertPosition.BELOW -> { boundary = anchorEnd; hint = "加入本行及其子树之后" }
            EditorInsertPosition.REPLACE -> {
                boundary = anchor
                hint = if (node.childSlots.isNotEmpty()) "替换本行及原子树" else "替换本行"
            }
            EditorInsertPosition.INSIDE -> {
                if (childSlot != null && childSlot !in node.childSlots)
                    return EditorInsertionPreviewData("原分支已不存在，请重新选择", emptyList())
                val slot = childSlot?.takeIf { it in node.childSlots } ?: node.childSlots.singleOrNull()
                    ?: return EditorInsertionPreviewData("请在下一步明确选择分支，不会默认第一个", emptyList())
                depth++
                hint = "加入${childBlockLabel(slot)}末尾"
                val header = rows.indexOfFirst { it is EditorTreeDisplayRow.Branch && it.ownerId == anchorId && it.slot == slot }
                boundary = if (header >= 0) {
                    depth = rows[header].depth + 1
                    var end = header + 1
                    while (end < rows.size && rows[end].depth > rows[anchor].depth &&
                        !(rows[end] is EditorTreeDisplayRow.Branch && (rows[end] as EditorTreeDisplayRow.Branch).ownerId == anchorId)) end++
                    end
                } else {
                    val lastChild = rows.indices.lastOrNull { index ->
                        val member = (rows[index] as? EditorTreeDisplayRow.Node)?.node
                        index > anchor && index < anchorEnd && member?.depth == node.depth + 1 && member.childSlot == slot
                    }
                    lastChild?.let(::subtreeEnd) ?: (anchor + 1)
                }
            }
            else -> Unit
        }
    }
    return EditorInsertionPreviewData(hint, buildList {
        val first = (boundary - 2).coerceAtLeast(0)
        val last = (boundary + 2).coerceAtMost(rows.size)
        for (index in first..last) {
            if (index == boundary) add(EditorInsertionPreviewLine(hint, depth, marker = true))
            if (index < last) {
                val row = rows[index]
                val label = when (row) {
                    is EditorTreeDisplayRow.Branch -> childBlockLabel(row.slot)
                    is EditorTreeDisplayRow.Node -> row.node.displayRow?.let { parts ->
                        parts.title + parts.details.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                    } ?: row.node.label
                }
                add(EditorInsertionPreviewLine(label, row.depth))
            }
        }
    })
}

@Composable
internal fun EditorInsertionPreview(nodes: List<EditorTreeNode>, anchorId: String?, position: EditorInsertPosition,
    childSlot: String? = null) {
    val preview = remember(nodes, anchorId, position, childSlot) { editorInsertionPreview(nodes, anchorId, position, childSlot) }
    Column(Modifier.fillMaxWidth().background(Color(0xFFF4F7FF)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text("落点预览 · ${preview.hint}", fontSize = 10.sp, color = EditorBlue)
        preview.lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(start = (line.depth.coerceIn(0, 12) * 6).dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (line.marker) Box(Modifier.width(12.dp).height(2.dp).background(EditorBlue))
                Text(if (line.marker) "  ${line.label}" else line.label, fontSize = 10.sp,
                    color = if (line.marker) EditorBlue else Color(0xFF747D90), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
