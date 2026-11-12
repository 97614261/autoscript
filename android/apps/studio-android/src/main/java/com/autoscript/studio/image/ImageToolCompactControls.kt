package com.autoscript.studio

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.project.store.ProjectSnapshot

/** Controlled inputs keep quick fills visible while focused; large fonts may increase row height. */
@Composable
internal fun ImageToolCompactInput(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    numeric: Boolean = false, trailing: (@Composable () -> Unit)? = null, dense: Boolean = false, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(modifier) {
    val labelWidth = (maxWidth * .4f).coerceAtMost(96.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = if (dense) 28.dp else 32.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
        .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .padding(horizontal = 7.dp, vertical = if (dense) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) Text(label, Modifier.widthIn(max = labelWidth), fontSize = 10.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentDescription = label.ifBlank { "输入值" } },
            textStyle = TextStyle(fontSize = if (dense) 11.sp else 12.sp, lineHeight = if (dense) 14.sp else 16.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
            interactionSource = interaction,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        trailing?.invoke()
    }
    }
}

@Composable
internal fun ImageToolChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    dense: Boolean = false, imageProject: ProjectSnapshot? = null, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(6.dp)
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = if (dense) 28.dp else 32.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, role = Role.Button) { focus.clearFocus(); query = ""; expanded = true }
            .padding(horizontal = 7.dp, vertical = if (dense) 3.dp else 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (label.isNotBlank()) Text(label, Modifier.widthIn(max = 86.dp), fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(options.firstOrNull { it.first == value }?.second ?: value.ifEmpty { "请选择" },
                Modifier.weight(1f), fontSize = 11.sp, lineHeight = 15.sp, maxLines = if (dense) 1 else 2,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (imageProject == null && options.size <= 8) DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 256.dp)) {
            if (options.isEmpty()) Text("没有可选内容", Modifier.padding(10.dp), fontSize = 11.sp)
            options.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title, fontSize = 12.sp, lineHeight = 16.sp) },
                    enabled = enabled, onClick = { expanded = false; onChange(key) },
                    modifier = Modifier.heightIn(min = 32.dp), contentPadding = PaddingValues(horizontal = 10.dp),
                    trailingIcon = { if (key == value) Icon(Icons.Filled.Check,
                        "已选择", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) })
            }
        }
    }
    if (expanded && imageProject != null) ProjectImagePickerDialog(imageProject, options.map { it.first }, value,
        { expanded = false }, { expanded = false; onChange(it) }, title = label.ifBlank { "选择图片" })
    if (expanded && imageProject == null && options.size > 8) CompositionLocalProvider(LocalImageWorkspace provides null) {
        ImageWorkspacePanel(label.ifBlank { "选择选项" }, { expanded = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ImageToolCompactInput("筛选", query, enabled) { query = it.take(128) }
                val matches = options.filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (matches.isEmpty()) item { ImageWorkspaceHint("没有匹配的选项") }
                    items(matches) { (key, title) ->
                        Row(Modifier.fillMaxWidth().background(if (key == value) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface).clickable(enabled = enabled) { expanded = false; onChange(key) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Text(title, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (key == value) Text("已选", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageToolToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit) {
    Row(modifier.heightIn(min = 28.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(horizontal = 3.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(16.dp),
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary, uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant))
        Text(label, fontSize = 11.sp, lineHeight = 14.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ImageToolHelp(text: String, content: (@Composable () -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.heightIn(min = 26.dp).clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_help_outline_24), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (expanded) "收起说明" else "使用说明", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp).rotate(if (expanded) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        content?.invoke()
    }
}

@Composable
internal fun ImageToolCompactAction(text: String, enabled: Boolean, modifier: Modifier = Modifier,
    primary: Boolean = false, dense: Boolean = false, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        if (!LocalImageWorkspaceStyling.current) {
            TextButton(onClick, modifier.defaultMinSize(minWidth = 1.dp).heightIn(min = 28.dp), enabled = enabled,
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                Text(text, fontSize = 11.sp, lineHeight = 14.sp)
            }
            return@CompositionLocalProvider
        }
        FilledTonalButton(onClick, modifier.defaultMinSize(minWidth = 1.dp).heightIn(min = if (dense) 28.dp else 30.dp), enabled = enabled,
            shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .45f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = if (dense) 2.dp else 4.dp)) {
            Text(text, fontSize = 11.sp, lineHeight = 14.sp, maxLines = if (dense) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ImageToolNumberFields(fields: List<Triple<String, String, String>>, enabled: Boolean,
    onChange: (String, String) -> Unit) {
    StudioCompactGrid(fields, cellDp = 116, maximum = 4, modifier = Modifier.fillMaxWidth()) { (key, label, value), cell ->
        ImageToolCompactInput(label, value, enabled, cell, numeric = true) { onChange(key, it) }
    }
}
