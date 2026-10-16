package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** One bounded row regardless of selection count; keep the canvas height stable while editing. */
@Composable
internal fun DesignerCanvasActions(
    multi: Boolean, count: Int, enabled: Boolean, canPosition: Boolean, canAlign: Boolean,
    onMulti: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
    onPosition: (String) -> Unit, onAlign: (String) -> Unit, onDistribute: (Boolean) -> Unit,
    organize: List<DesignerMenuAction>,
) {
    Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(4.dp))
        .background(DesignerColors.Panel).border(1.dp, DesignerColors.Line, RoundedCornerShape(4.dp))
        .horizontalScroll(rememberScrollState()).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        DesignerAction("多选", selected = multi, primary = true, enabled = enabled, onClick = onMulti)
        DesignerAction("复制", enabled = enabled && count > 0, onClick = onCopy)
        DesignerAction("删除", enabled = enabled && count > 0, onClick = onDelete)
        Box(Modifier.width(1.dp).height(14.dp).background(DesignerColors.Line))
        DesignerMenu("定位", listOf("靠左" to "left", "水平居中" to "centerX", "靠右" to "right",
            "靠上" to "top", "垂直居中" to "centerY", "靠下" to "bottom", "正中" to "center").map { (label, key) ->
            DesignerMenuAction(label, enabled && canPosition) { onPosition(key) }
        })
        DesignerAction("横中", enabled = enabled && canPosition) { onPosition("centerX") }
        DesignerAction("竖中", enabled = enabled && canPosition) { onPosition("centerY") }
        DesignerMenu("对齐", listOf("左对齐" to "left", "水平中心对齐" to "centerX", "右对齐" to "right",
            "上对齐" to "top", "垂直中心对齐" to "centerY", "下对齐" to "bottom").map { (label, key) ->
            DesignerMenuAction(label, enabled && canAlign) { onAlign(key) }
        } + listOf(DesignerMenuAction("水平等距", enabled && canAlign && count > 2) { onDistribute(true) },
            DesignerMenuAction("垂直等距", enabled && canAlign && count > 2) { onDistribute(false) }))
        DesignerMenu("整理", organize)
    }
}
