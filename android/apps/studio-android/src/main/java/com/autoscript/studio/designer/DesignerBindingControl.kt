package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectManifestDocument

@Composable
internal fun DesignerBindingControl(draft: RunnerUiDesignerDraft, field: RunnerUiFieldDraft,
    manifest: ProjectManifestDocument, enabled: Boolean, onChange: (String?) -> Unit) {
    val options = draft.bindingOptions(field, manifest)
    val selected = options.firstOrNull { it.key == field.ui.binding }
    val current = field.ui.binding
    val choices = listOf(VariablePickerOption("", "不绑定")) + options.map { option ->
        VariablePickerOption(option.key, "${option.name} · ${option.owner}" + if (option.type == null) " · ${option.typeLabel}" else "",
            option.scope, option.type)
    }
    VariableChoiceAnchor("绑定变量", current.orEmpty(), choices, { key ->
        if (enabled && (key.isEmpty() || options.any { it.key == key })) onChange(key.takeIf { it.isNotEmpty() })
    }) { open ->
        Row(Modifier.fillMaxWidth().heightIn(min = 26.dp).background(DesignerColors.Workspace, RoundedCornerShape(4.dp))
            .border(1.dp, DesignerColors.Line, RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = open)
            .padding(horizontal = 5.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("绑定", fontSize = 10.sp, color = DesignerColors.Muted, modifier = Modifier.padding(end = 5.dp))
            Text(selected?.summary ?: if (current == null) "选择变量" else "原绑定不可用 · ${current.substringAfterLast(':')}",
                Modifier.weight(1f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (current != null && selected == null) Color(0xFFB26A1B) else DesignerColors.Ink)
            if (current != null) DesignerAction("解除", enabled = enabled) { onChange(null) }
            else Text("▾", fontSize = 10.sp, color = DesignerColors.Accent)
        }
    }
    if (field.kind.wireName == "boolean" && selected != null && selected.scope != "参数")
        Text("选中写入 1，未选中写入 0", fontSize = 9.sp, color = DesignerColors.Muted)
}
