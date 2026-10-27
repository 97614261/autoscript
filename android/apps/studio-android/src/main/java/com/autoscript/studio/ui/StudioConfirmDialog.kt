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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette

internal data class StudioConfirmAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

internal fun studioConfirmActionsStacked(widthDp: Float, fontScale: Float, count: Int): Boolean {
    val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f
    return count > 1 && (!widthDp.isFinite() || widthDp < count * 104f * scale)
}

/** Common confirmation shell; callers retain their validation and destructive-action guards. */
@Composable
internal fun StudioConfirmDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.White,
    shape: Shape = RoundedCornerShape(3.dp),
    actions: List<StudioConfirmAction>? = null,
    compact: Boolean = false,
    prompt: Boolean = true,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false), prompt = prompt) {
        BoxWithConstraints(Modifier.fillMaxWidth().imePadding(), contentAlignment = Alignment.Center) {
            val configuration = LocalConfiguration.current
            val fontScale = LocalDensity.current.fontScale
            val panelHeight = studioPanelMaxHeight(maxHeight.value, configuration.screenHeightDp).dp
            val footerHeight = studioCompactControlHeight(if (compact) 36 else 44, fontScale).dp
            MaterialTheme(typography = MaterialTheme.typography.copy(labelLarge = TextStyle(
                fontSize = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))) {
                Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(.92f).heightIn(max = panelHeight), color = containerColor,
                    shape = shape, shadowElevation = 10.dp) {
                    Column {
                        if (title != null) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 12.dp), contentAlignment = Alignment.Center) {
                                ProvideTextStyle(TextStyle(color = AutoScriptPalette.Accent, fontSize = if (compact) 14.sp else 16.sp,
                                    fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false))) { title() }
                            }
                            HorizontalDivider(color = AutoScriptPalette.Divider)
                        }
                        if (text != null) Box(Modifier.weight(1f, fill = false).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 14.dp)) {
                            ProvideTextStyle(TextStyle(color = AutoScriptPalette.TextPrimary, fontSize = if (compact) 12.sp else 13.sp,
                                lineHeight = if (compact) 17.sp else 19.sp)) { text() }
                        }
                        HorizontalDivider(color = AutoScriptPalette.Divider)
                        if (actions != null) {
                            StudioConfirmActions(actions, footerHeight.value, panelHeight.value / 2f, fontScale)
                        } else Row(Modifier.fillMaxWidth().heightIn(min = footerHeight), verticalAlignment = Alignment.CenterVertically) {
                            if (dismissButton != null) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dismissButton() }
                                VerticalDivider(Modifier.height(footerHeight), color = AutoScriptPalette.Divider)
                            }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { confirmButton() }
                        }
                    }
                }
            }
        }
    }
}

/** Each action owns one cell; multiple buttons must not be nested in a dismiss-button slot. */
@Composable
private fun StudioConfirmActions(actions: List<StudioConfirmAction>, heightDp: Float, maxHeightDp: Float, fontScale: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = maxHeightDp.dp)) {
        val stacked = studioConfirmActionsStacked(maxWidth.value, fontScale, actions.size)
        if (stacked) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) HorizontalDivider(color = AutoScriptPalette.Divider)
                    StudioConfirmActionButton(action, Modifier.fillMaxWidth().heightIn(min = heightDp.dp))
                }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            actions.forEachIndexed { index, action ->
                if (index > 0) VerticalDivider(Modifier.height(heightDp.dp), color = AutoScriptPalette.Divider)
                StudioConfirmActionButton(action, Modifier.weight(1f).heightIn(min = heightDp.dp))
            }
        }
    }
}

@Composable
private fun StudioConfirmActionButton(action: StudioConfirmAction, modifier: Modifier) {
    TextButton(onClick = action.onClick, enabled = action.enabled, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Text(action.label, fontSize = 12.sp, textAlign = TextAlign.Center,
            color = when {
                !action.enabled -> AutoScriptPalette.TextSecondary.copy(alpha = .5f)
                action.destructive -> AutoScriptPalette.Danger
                else -> AutoScriptPalette.Accent
            }, style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
