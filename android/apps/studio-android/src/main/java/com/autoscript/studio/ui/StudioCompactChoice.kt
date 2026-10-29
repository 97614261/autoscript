package com.autoscript.studio

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** Keep stored keys intact while displaying their readable labels. Long lists get a searchable picker. */
@Composable
internal fun StudioCompactChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    onChange: (String) -> Unit) {
    StudioChoiceAnchor(label.ifBlank { "选择选项" }, value, options, enabled, onChange) { open ->
        val shape = RoundedCornerShape(6.dp)
        Row(Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(shape)
            .background(AutoScriptPalette.VisualEditor.ControlBackground, shape)
            .border(1.dp, AutoScriptPalette.VisualEditor.Border, shape)
            .clickable(enabled = enabled, onClick = open)
            .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (label.isNotBlank()) Text(label, Modifier.widthIn(max = 108.dp), fontSize = 11.sp,
                lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
            Text(options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "请选择" }, Modifier.weight(1f),
                fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = AutoScriptPalette.TextSecondary)
        }
    }
}

/** A single picker also serves the icon embedded in editable variable fields. */
@Composable
internal fun StudioChoiceAnchor(title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    onChange: (String) -> Unit, anchor: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Box {
        anchor { if (enabled) { focus.clearFocus(); query = ""; expanded = true } }
        if (options.size <= 8) DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 240.dp)) {
            if (options.isEmpty()) Text("没有可选内容", fontSize = 11.sp, modifier = Modifier.padding(8.dp))
            options.forEach { (key, optionTitle) ->
                DropdownMenuItem(text = { Text(optionTitle, fontSize = 12.sp, lineHeight = 16.sp) },
                    trailingIcon = { if (key == value) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = AutoScriptPalette.Accent) },
                    enabled = enabled, onClick = { expanded = false; onChange(key) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 30.dp))
            }
        }
    }
    if (expanded && options.size > 8) StudioPanelDialog(title, onDismiss = { expanded = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InspectorSearchField(query, { query = it.take(128) }, "筛选选项")
            val visible = options.filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (visible.isEmpty()) item { Text("没有可选内容", fontSize = 11.sp, modifier = Modifier.padding(8.dp)) }
                itemsIndexed(visible) { _, option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)
                        .background(if (value == option.first) AutoScriptPalette.AccentSoft else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(5.dp)).clickable(enabled = enabled) {
                        if (enabled && options.any { it.first == option.first }) { expanded = false; onChange(option.first) }
                    }.padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(option.second, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp)
                        if (value == option.first) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = AutoScriptPalette.Accent)
                    }
                }
            }
        }
    }
}

