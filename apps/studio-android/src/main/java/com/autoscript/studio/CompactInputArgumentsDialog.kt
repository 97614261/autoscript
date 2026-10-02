package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject

@Composable
internal fun CompactInputArgumentsDialog(contract: BlockContract, arguments: JsonObject,
    onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String) {
    var inputs by remember(contract.kind, arguments) { mutableStateOf(blockPropertyTexts(contract, arguments)) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp).imePadding()) {
            Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight).align(Alignment.Center),
                color = Color.White, shape = RoundedCornerShape(4.dp), shadowElevation = 9.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("按键 · ${contract.title}", color = AutoScriptPalette.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("×", modifier = Modifier.size(32.dp).clickable(onClick = onDismiss).wrapContentSize(Alignment.Center), fontSize = 22.sp)
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        contract.properties.chunked(2).forEach { properties ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                properties.forEach { field ->
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(field.label, fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                                        Box(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
                                            .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                            BasicTextField(inputs[field.path].orEmpty(), { inputs = inputs + (field.path to it.take(128)); error = null },
                                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                                textStyle = TextStyle(fontSize = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
                                        }
                                        if (field.editor in setOf(BlockPropertyEditor.ENUM, BlockPropertyEditor.INTEGER_ENUM, BlockPropertyEditor.BOOLEAN)) {
                                            val choices = if (field.editor == BlockPropertyEditor.BOOLEAN) listOf("true", "false") else field.choices
                                            choices.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                row.forEach { choice -> Text(choice, color = AutoScriptPalette.Accent, fontSize = 10.sp,
                                                    modifier = Modifier.clickable { inputs = inputs + (field.path to choice); error = null }.padding(vertical = 3.dp)) }
                                            } }
                                        }
                                    }
                                }
                                if (properties.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (contract.properties.isEmpty()) Text("此动作没有坐标参数。", fontSize = 12.sp)
                        Text("修改不会触发触摸；坐标单位沿用原积木。按下、移动、弹起按程序顺序执行。", fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
                        error?.let { Text(it, fontSize = 11.sp, color = Color.Red) }
                    }
                    HorizontalDivider(color = AutoScriptPalette.Divider)
                    Row(Modifier.fillMaxWidth().height(40.dp)) {
                        listOf("取消" to onDismiss, confirmLabel to {
                            val parsed = parseBlockArguments(contract, inputs, null, emptyMap(), emptyList())
                            if (parsed.first == null) error = parsed.second else onConfirm(requireNotNull(parsed.first))
                        }).forEach { (label, action) ->
                            Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = action), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 13.sp, color = AutoScriptPalette.Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
