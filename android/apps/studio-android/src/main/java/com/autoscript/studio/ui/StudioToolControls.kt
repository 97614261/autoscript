package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

@Composable
internal fun StudioToolAction(title: String, icon: Int, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.heightIn(min = 44.dp).clip(shape)
        .background(if (primary) AutoScriptPalette.AccentSoft else AutoScriptPalette.VisualEditor.ControlBackground, shape)
        .border(1.dp, AutoScriptPalette.Border, shape).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), null, tint = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
            modifier = Modifier.size(19.dp))
        Text(title, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) AutoScriptPalette.TextPrimary else AutoScriptPalette.TextSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun StudioToolButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.heightIn(min = 32.dp).clip(shape)
        .background(if (selected) AutoScriptPalette.AccentSoft else AutoScriptPalette.VisualEditor.ControlBackground)
        .border(1.dp, if (selected) AutoScriptPalette.Accent.copy(alpha = .35f) else AutoScriptPalette.Border, shape)
        .semantics { this.selected = selected }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (!enabled) AutoScriptPalette.TextSecondary else if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun StudioToolSegments(value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier,
    onChange: (String) -> Unit) {
    Row(modifier.fillMaxWidth().selectableGroup()
        .background(AutoScriptPalette.PageBackground, RoundedCornerShape(6.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (key, label) ->
            val selected = value == key
            Box(Modifier.weight(1f).heightIn(min = 28.dp)
                .background(if (selected) AutoScriptPalette.Card else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp))
                .selectable(selected, role = Role.RadioButton) { if (!selected) onChange(key) }
                .padding(horizontal = 6.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.sp, lineHeight = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
            }
        }
    }
}

@Composable
internal fun StudioFormGroup(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextSecondary)
        content()
    }
}

@Composable
internal fun StudioToolRailItem(title: String, selected: Boolean, modifier: Modifier = Modifier,
    icon: Int? = null, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 28.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(if (selected) AutoScriptPalette.AccentSoft else androidx.compose.ui.graphics.Color.Transparent)
        .selectable(selected, role = Role.Tab, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(16.dp),
                tint = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary)
            Text(title, fontSize = 11.sp, lineHeight = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) AutoScriptPalette.Accent else AutoScriptPalette.TextPrimary)
        }
    }
}
