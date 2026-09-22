package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette

/** Shared compact editor panel used by the visual editor and the Lua debug editor. */
@Composable
internal fun StudioPanelDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "关闭",
    onConfirm: () -> Unit = onDismiss,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.92f).widthIn(max = 540.dp),
            color = Color.White,
            shape = RoundedCornerShape(3.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("×", color = AutoScriptPalette.TextSecondary, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 8.dp))
                }
                HorizontalDivider(color = AutoScriptPalette.Divider)
                content()
                HorizontalDivider(color = AutoScriptPalette.Divider)
                Text(
                    confirmLabel,
                    textAlign = TextAlign.Center,
                    color = AutoScriptPalette.Accent,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onConfirm).padding(top = 11.dp),
                )
            }
        }
    }
}
