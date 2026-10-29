package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.studio.StudioDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette

/** Shared compact panel for the visual editor and its plugin source view. */
@Composable
internal fun StudioPanelDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "关闭",
    onConfirm: () -> Unit = onDismiss,
    confirmEnabled: Boolean = true,
    fullScreen: Boolean = false,
    showFooter: Boolean = true,
    headerActions: @Composable () -> Unit = {},
    compactHeader: Boolean = true,
    onToggleFullScreen: (() -> Unit)? = null,
    landscapeWidthDp: Int? = null,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints((if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).imePadding(), contentAlignment = Alignment.Center) {
            val configuration = LocalConfiguration.current
            val fontScale = LocalDensity.current.fontScale
            val short = studioShortViewport(maxHeight.value)
            val denseHeader = compactHeader || short
            val barHeight = studioCompactControlHeight(if (short) 30 else 34, fontScale).dp
            val headerHeight = studioCompactControlHeight(if (denseHeader) 32 else 40, fontScale).dp
            Surface(
                modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.width(studioToolPanelWidth(
                    maxWidth.value, maxHeight.value, fontScale, landscapeWidthDp = landscapeWidthDp).dp)
                    .heightIn(max = studioPanelMaxHeight(maxHeight.value, configuration.screenHeightDp, if (short) 8 else 24).dp),
                color = Color.White,
                shape = RoundedCornerShape(if (fullScreen) 0.dp else 8.dp),
                shadowElevation = 10.dp,
            ) {
                Column {
                    val density = LocalDensity.current
                    var actionWidthPx by remember { mutableIntStateOf(0) }
                    BoxWithConstraints(Modifier.fillMaxWidth().height(headerHeight)) {
                        val controlSize = if (denseHeader) 28.dp else 36.dp
                        val pinnedWidth = 8.dp + controlSize * (if (onToggleFullScreen == null) 1 else 2)
                        val actionsMaxWidth = studioHeaderActionsMaxWidth(maxWidth.value, pinnedWidth.value).dp
                        Text(title, color = AutoScriptPalette.TextPrimary, fontSize = if (denseHeader) 13.sp else 16.sp,
                            fontWeight = if (denseHeader) FontWeight.SemiBold else FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal =
                                with(density) { actionWidthPx.toDp() }.coerceAtLeast(44.dp)),
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                        Row(Modifier.align(Alignment.CenterEnd).onSizeChanged { actionWidthPx = it.width }
                            .padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.widthIn(max = actionsMaxWidth).horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically) { headerActions() }
                            if (onToggleFullScreen != null) StudioPanelFullScreenButton(fullScreen, onToggleFullScreen, denseHeader)
                            Box(Modifier.size(controlSize).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = AutoScriptPalette.TextSecondary,
                                    modifier = Modifier.size(if (denseHeader) 16.dp else 20.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Box(Modifier.weight(1f, fill = false)) { content() }
                    if (showFooter) {
                        HorizontalDivider(color = AutoScriptPalette.Divider)
                        Box(Modifier.fillMaxWidth().height(barHeight).clickable(enabled = confirmEnabled, onClick = onConfirm), contentAlignment = Alignment.Center) {
                            Text(
                                confirmLabel,
                                textAlign = TextAlign.Center,
                                color = if (confirmEnabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shared fixed header control; toggling layout never dismisses the current dialog session. */
@Composable
internal fun StudioPanelFullScreenButton(fullScreen: Boolean, onToggle: () -> Unit, compact: Boolean = false) {
    Box(Modifier.size(if (compact) 28.dp else 36.dp).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
        Icon(painterResource(if (fullScreen) R.drawable.ic_panel_restore_20 else R.drawable.ic_panel_fullscreen_20),
            contentDescription = if (fullScreen) "还原窗口" else "最大化全屏", tint = AutoScriptPalette.Accent,
            modifier = Modifier.size(if (compact) 16.dp else 20.dp))
    }
}
