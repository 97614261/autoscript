package com.autoscript.studio

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** One-line buttons wrap as complete items; neither icon nor label depends on a fixed footer height. */
@Composable
internal fun PluginFileAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    val color = when { !enabled -> AutoScriptPalette.TextSecondary; danger -> AutoScriptPalette.Danger; else -> AutoScriptPalette.Accent }
    Row(modifier.heightIn(min = 28.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (icon != null) Icon(painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null,
            tint = color, modifier = Modifier.size(14.dp))
        Text(label, color = color, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun PluginFileInput(value: String, onValueChange: (String) -> Unit, label: String, hint: String, enabled: Boolean,
    modifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null) {
    BasicTextField(value, onValueChange, enabled = enabled, singleLine = true,
        textStyle = TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 12.sp),
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit == null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit?.invoke() }),
        modifier = modifier.semantics { contentDescription = label }.heightIn(min = 30.dp)
            .background(AutoScriptPalette.PageBackground, RoundedCornerShape(4.dp))
            .border(1.dp, AutoScriptPalette.Border, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { input -> Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            input()
        } })
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PluginFileNameDialog(title: String, value: String, suggestions: List<String>, error: String?, busy: Boolean,
    confirmLabel: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    StudioPanelDialog(title, onDismiss = { if (!busy) onDismiss() }, compactHeader = true, showFooter = false) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PluginFileInput(value, onValueChange, "插件或分组名称", "输入名称（最多64字）", !busy,
                modifier = Modifier.fillMaxWidth(), onSubmit = onConfirm)
            if (suggestions.isNotEmpty()) {
                Text("选择现有分组，或输入新分组名", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                FlowRow(Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState())) {
                    suggestions.forEach { name -> PluginFileAction(name, { onValueChange(name) }, enabled = !busy) }
                }
            }
            if (error != null) Text(error, color = AutoScriptPalette.Danger, fontSize = 11.sp)
            Row(Modifier.align(Alignment.End)) {
                PluginFileAction("取消", onDismiss, enabled = !busy)
                PluginFileAction(confirmLabel, onConfirm, enabled = !busy && value.isNotBlank())
            }
        }
    }
}
