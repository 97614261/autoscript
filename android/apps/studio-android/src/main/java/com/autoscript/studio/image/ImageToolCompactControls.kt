package com.autoscript.studio

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** Controlled inputs keep quick fills visible while focused; large fonts may increase row height. */
@Composable
internal fun ImageToolCompactInput(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    numeric: Boolean = false, trailing: (@Composable () -> Unit)? = null, dense: Boolean = false, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.heightIn(min = if (dense) 28.dp else 34.dp).background(AutoScriptPalette.VisualEditor.ControlBackground, shape)
        .border(1.dp, if (focused) AutoScriptPalette.Accent else AutoScriptPalette.VisualEditor.Border, shape)
        .padding(horizontal = 8.dp, vertical = if (dense) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) Text(label, Modifier.widthIn(max = 108.dp), fontSize = 11.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentDescription = label.ifBlank { "输入值" } },
            textStyle = TextStyle(fontSize = if (dense) 11.sp else 12.sp, lineHeight = if (dense) 14.sp else 16.sp, color = AutoScriptPalette.TextPrimary),
            interactionSource = interaction,
            cursorBrush = SolidColor(AutoScriptPalette.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
        trailing?.invoke()
    }
}

@Composable
internal fun ImageToolChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean,
    onChange: (String) -> Unit) = StudioCompactChoice(label, value, options, enabled, onChange)

@Composable
internal fun ImageToolToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit) {
    Row(modifier.heightIn(min = 28.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(horizontal = 5.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(checkedColor = AutoScriptPalette.Accent,
                uncheckedColor = AutoScriptPalette.TextSecondary))
        Text(label, fontSize = 11.sp, lineHeight = 14.sp,
            color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary)
    }
}

@Composable
internal fun ImageToolHelp(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.heightIn(min = 26.dp).clickable { expanded = !expanded }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_help_outline_24), null, Modifier.size(14.dp), tint = AutoScriptPalette.TextSecondary)
        Text(if (expanded) "收起说明" else "使用说明", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp).rotate(if (expanded) 180f else 0f), tint = AutoScriptPalette.TextSecondary)
    }
    if (expanded) Text(text, color = AutoScriptPalette.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
}

@Composable
internal fun ImageToolCompactAction(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TextButton(onClick, modifier.defaultMinSize(minWidth = 1.dp).heightIn(min = 28.dp), enabled = enabled,
            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text(text, fontSize = 11.sp, lineHeight = 14.sp) }
    }
}

@Composable
internal fun ImageToolNumberFields(fields: List<Triple<String, String, String>>, enabled: Boolean,
    onChange: (String, String) -> Unit) {
    StudioCompactGrid(fields, cellDp = 144, maximum = 2, modifier = Modifier.fillMaxWidth()) { (key, label, value), cell ->
        ImageToolCompactInput(label, value, enabled, cell, numeric = true) { onChange(key, it) }
    }
}
