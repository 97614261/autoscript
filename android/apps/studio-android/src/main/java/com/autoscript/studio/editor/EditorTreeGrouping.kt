package com.autoscript.studio

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class EditorTreeGroup(val key: String, val depth: Int, val first: Int, val last: Int,
    val branch: Boolean, val disabled: Boolean)
internal data class EditorTreeGroupedRow(val row: EditorTreeDisplayRow, val index: Int, val groups: List<EditorTreeGroup>) {
    val visibleGroups = groups.filter { it.depth in 0..31 }
}
internal class EditorTreeGrouping(val rows: List<EditorTreeGroupedRow>) {
    private val byKey = rows.associateBy { it.row.key }
    fun focusedGroups(key: String?): Set<String> = byKey[key]?.groups?.mapTo(linkedSetOf()) { it.key }.orEmpty()
}

/** Close each visible scope once; folding and branch order are already resolved by the projection. */
internal fun editorTreeGrouping(rows: List<EditorTreeDisplayRow>): EditorTreeGrouping {
    val depths = IntArray(rows.size) { rows[it].depth.coerceAtLeast(0) }
    val ends = IntArray(rows.size)
    val open = ArrayDeque<Int>()
    rows.indices.forEach { index ->
        while (open.isNotEmpty() && depths[open.last()] >= depths[index]) ends[open.removeLast()] = index - 1
        if (index < rows.lastIndex && depths[index + 1] > depths[index]) open.addLast(index)
    }
    while (open.isNotEmpty()) ends[open.removeLast()] = rows.lastIndex
    val ancestors = ArrayDeque<EditorTreeGroup>()
    return EditorTreeGrouping(rows.mapIndexed { index, row ->
        while (ancestors.isNotEmpty() && ancestors.last().last < index) ancestors.removeLast()
        if (ends[index] > index) ancestors.addLast(EditorTreeGroup(row.key, depths[index], index, ends[index],
            row is EditorTreeDisplayRow.Branch, when (row) {
                is EditorTreeDisplayRow.Branch -> row.disabled
                is EditorTreeDisplayRow.Node -> (row.node.displayRow ?: compactProgramRowText(row.node.label)).disabled
            }))
        EditorTreeGroupedRow(row, index, ancestors.toList())
    })
}

/** Draw inside each row so lazy scrolling never needs offscreen coordinates or extra end rows. */
internal fun Modifier.editorTreeGroups(item: EditorTreeGroupedRow, focusedGroups: Set<String>): Modifier = drawBehind {
    item.visibleGroups.lastOrNull()?.let { group ->
        val x = editorTreeGuideOffset(group.depth).dp.toPx()
        val color = if (group.disabled) Color(0xFF929AA7) else Color(0xFF6A82A2)
        if (size.width > x) drawRect(color.copy(alpha = if (group.depth % 2 == 0) .035f else .065f),
            topLeft = Offset(x, 0f), size = Size(size.width - x, size.height))
    }
    // The focus path is outer-to-inner. Only its nearest scope gets a rail.
    val focusedKey = focusedGroups.lastOrNull()
    item.visibleGroups.firstOrNull { it.key == focusedKey }?.let { group ->
        val x = editorTreeGuideOffset(group.depth).dp.toPx()
        val startY = if (item.index == group.first) center.y + if (group.branch) 0f else 6.dp.toPx() else 0f
        val endY = if (item.index == group.last) size.height - 2.dp.toPx() else size.height
        val ink = if (group.disabled) Color(0xFF929AA7) else Color(0xFF7796BE)
        if (endY > startY) drawLine(ink.copy(alpha = .7f), Offset(x, startY), Offset(x, endY), strokeWidth = 1.dp.toPx())
    }
}
