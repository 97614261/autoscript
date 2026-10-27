package com.autoscript.studio

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

/** Controlled inputs keep quick fills visible while focused; large fonts may increase row height. */
@Composable
internal fun ImageToolCompactInput(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    numeric: Boolean = false, onChange: (String) -> Unit) {
    Row(modifier.heightIn(min = 28.dp).border(1.dp, AutoScriptPalette.Divider).padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = AutoScriptPalette.TextSecondary)
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            textStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, color = AutoScriptPalette.TextPrimary),
            cursorBrush = SolidColor(AutoScriptPalette.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
    }
}

@Composable
internal fun ImageToolCompactAction(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        TextButton(onClick, modifier.defaultMinSize(minWidth = 1.dp).heightIn(min = 28.dp), enabled = enabled,
            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) { Text(text, fontSize = 11.sp, lineHeight = 14.sp) }
    }
}
