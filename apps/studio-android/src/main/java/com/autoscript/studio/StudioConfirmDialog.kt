package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette

/** Common confirmation shell; callers retain their validation and destructive-action guards. */
@Composable
internal fun StudioConfirmDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.White,
    shape: Shape = RoundedCornerShape(3.dp),
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(120).dp
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(typography = MaterialTheme.typography.copy(labelLarge = TextStyle(
            fontSize = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(.92f).heightIn(max = maxHeight), color = containerColor,
                shape = shape, shadowElevation = 10.dp) {
                Column {
                    if (title != null) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                            ProvideTextStyle(TextStyle(color = AutoScriptPalette.Accent, fontSize = 16.sp,
                                fontWeight = FontWeight.Bold, platformStyle = PlatformTextStyle(includeFontPadding = false))) { title() }
                        }
                        HorizontalDivider(color = AutoScriptPalette.Divider)
                    }
                    if (text != null) Box(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(14.dp)) {
                        ProvideTextStyle(TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)) { text() }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (dismissButton != null) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dismissButton() }
                            VerticalDivider(Modifier.height(44.dp), color = AutoScriptPalette.Divider)
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { confirmButton() }
                    }
                }
            }
        }
    }
}
