package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalImageWorkspaceStyling = staticCompositionLocalOf { false }

private val ImageWorkspaceScheme = darkColorScheme(
    primary = Color(0xFF8AB5FF), onPrimary = Color(0xFF12233E),
    primaryContainer = Color(0xFF2E4770), onPrimaryContainer = Color(0xFFE5EEFF),
    secondary = Color(0xFFBDCBDE), onSecondary = Color(0xFF1B2534),
    background = Color(0xFF171D27), onBackground = Color(0xFFE8EDF5),
    surface = Color(0xFF202733), onSurface = Color(0xFFE8EDF5),
    surfaceVariant = Color(0xFF2A3342), onSurfaceVariant = Color(0xFFAEBBD0),
    surfaceContainer = Color(0xFF252F3E), surfaceContainerHighest = Color(0xFF303B4D),
    outline = Color(0xFF455268), outlineVariant = Color(0xFF354154),
    error = Color(0xFFFFA5A5), onError = Color(0xFF431B20),
)

/** Image inspectors follow the screenshot toolbar without changing the app theme. */
@Composable
internal fun ImageWorkspaceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ImageWorkspaceScheme) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, LocalImageWorkspaceStyling provides true) {
            ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, lineHeight = 16.sp,
            ), content)
        }
    }
}

@Composable
internal fun ImageWorkspaceSection(
    title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        content()
    }
}

/** Feedback stays visible; long details can be read without permanently filling the inspector. */
@Composable
internal fun ImageWorkspaceHint(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, Modifier.weight(1f), fontSize = 10.sp, lineHeight = 14.sp,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded || error) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Filled.KeyboardArrowDown, if (expanded) "收起详情" else "展开详情",
            Modifier.size(14.dp).rotate(if (expanded) 180f else 0f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ImageWorkspaceFooter(content: @Composable RowScope.() -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun ImageProcessingApplyFooter(label: String, enabled: Boolean, onDismiss: () -> Unit,
    onApply: () -> Unit, onApplyAndSave: (() -> Unit)? = null, closeEnabled: Boolean = true) {
    ImageWorkspaceFooter {
        ImageToolCompactAction("取消", closeEnabled, onClick = onDismiss)
        Spacer(Modifier.weight(1f))
        if (onApplyAndSave == null) GlyphMakerPrimaryAction(label, enabled, onClick = onApply)
        else {
            ImageToolCompactAction("应用", enabled, onClick = onApply)
            GlyphMakerPrimaryAction("应用并存图", enabled, onClick = onApplyAndSave)
        }
    }
}

@Composable
internal fun ImageProcessingPreviewBar(original: Boolean, onToggleOriginal: () -> Unit,
    message: String, enabled: Boolean = true, error: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ImageToolCompactAction(if (original) "查看效果" else "对照原图", enabled, onClick = onToggleOriginal)
        if (!error) Text(message, Modifier.weight(1f), fontSize = 10.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (error) ImageWorkspaceHint(message, error = true)
}
