package com.autoscript.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

@Composable
internal fun EditorProblemDialog(message: String, code: String?, target: EditorProblemTarget?,
    canEdit: Boolean, onDismiss: () -> Unit, onLocate: (Boolean) -> String?) {
    var details by remember(message) { mutableStateOf(false) }
    var navigationError by remember(message) { mutableStateOf<String?>(null) }
    var copied by remember(message) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val original = remember(message, code) { listOfNotNull(code, message).joinToString("\n").take(65_536) }
    val advice = remember(message, code) { editorProblemAdvice(message, code) }
    StudioConfirmDialog(onDismissRequest = onDismiss, title = { Text("编辑提示") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(advice?.summary ?: message.take(65_536), fontSize = 13.sp)
            advice?.let { Text(it.suggestion, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary) }
            target?.let { Text("${it.flowName} · 第${it.line}行 · ${it.title}", fontSize = 11.sp, color = EditorBlue) }
            if (advice != null || code != null) {
                Text(if (details) "收起原始详情" else "查看原始详情", fontSize = 11.sp, color = EditorBlue,
                    modifier = Modifier.clickable { details = !details }.padding(vertical = 3.dp))
                if (details) SelectionContainer { Text(original, fontSize = 10.sp) }
            }
            if (copied) Text("已复制原始详情${if (message.length > 65_536) "（最多64Ki字符）" else ""}", fontSize = 10.sp, color = EditorBlue)
            navigationError?.let { Text(it, fontSize = 11.sp, color = AutoScriptPalette.Danger) }
        }
    }, actions = buildList {
        add(StudioConfirmAction("关闭", onDismiss))
        add(StudioConfirmAction("复制详情", { clipboard.setText(AnnotatedString(original)); copied = true }))
        if (target != null) {
            add(StudioConfirmAction("定位积木", { navigationError = onLocate(false) }))
            add(StudioConfirmAction("打开参数", { navigationError = onLocate(true) }, enabled = canEdit))
        }
    })
}
