package com.autoscript.studio

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptDimens
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RunnerUiDesignerScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    var draft by remember(snapshot.manifest.projectId) {
        mutableStateOf(runnerUiDesignerDraft(snapshot.manifest.runnerUi))
    }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var addVisible by remember { mutableStateOf(false) }
    var paletteMore by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = !busy, onBack = onBack)

    Row(modifier.fillMaxSize().background(Color(0xFFEEF2F7))) {
      Column(Modifier.weight(1f).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFFF7F8FB))) {
            listOf(
                R.drawable.visual_back_24 to "退出", R.drawable.visual_page_24 to "页面", R.drawable.editor_delete_24 to "删除",
                R.drawable.visual_adjust_24 to "调整", R.drawable.visual_multi_24 to "多选", R.drawable.visual_preview_24 to "预览",
                R.drawable.visual_code_24 to "编辑", R.drawable.editor_swap_24 to "属性",
            ).forEach { (icon, label) ->
                VisualToolbarItem(icon, label, Modifier.weight(1f)) { if (label == "退出") onBack() }
            }
            VisualToolbarItem(R.drawable.visual_save_24, if (busy) "保存中" else "保存", Modifier.weight(1f)) {
                val candidate = runCatching { draft.toRunnerUiJson() }.getOrElse { error = it.message ?: "界面配置无效"; return@VisualToolbarItem }
                busy = true; error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { val current = latestSnapshot; store.updateRunnerUi(current.manifest.projectId, candidate, current.manifest.runnerUi) } }
                        .onSuccess { onSnapshotChanged(it); notice = if (candidate == null) "脚本界面已清空" else "脚本界面已保存" }
                        .onFailure { error = it.message ?: "脚本界面保存失败" }
                    busy = false
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(100.dp).fillMaxSize().background(Color.White)) {
                LazyColumn {
                    itemsIndexed(draft.fields, key = { _, field -> field.id }) { index, field ->
                        Text(field.label, modifier = Modifier.fillMaxWidth().height(30.dp).clickable(enabled = !busy) { selectedIndex = index }.padding(horizontal = 6.dp, vertical = 8.dp), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(380.dp).fillMaxSize().background(Color.White).border(1.dp, Color(0xFF3A6EFF))) {
                    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.fields.forEachIndexed { index, field ->
                            Box(
                                Modifier.fillMaxWidth().height(42.dp)
                                    .border(if (selectedIndex == index) 1.dp else 0.dp, if (selectedIndex == index) Color(0xFF3A6EFF) else Color.Transparent)
                                    .clickable(enabled = !busy) { selectedIndex = index }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Column { Text(field.label, fontSize = 11.sp); RunnerUiFieldPreview(field) }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFFF3F6FB))) {
            Box(Modifier.width(100.dp).fillMaxSize().background(Color(0xFFE5EBF3)))
            VisualToolbarItem(R.drawable.visual_copy_24, "复制", Modifier.weight(1f)) {}
            VisualToolbarItem(R.drawable.visual_align_24, "对齐", Modifier.weight(1f)) {}
            Text("坐标显示", modifier = Modifier.weight(5f).padding(top = 13.dp), fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color(0xFF46536A))
            VisualToolbarItem(R.drawable.visual_undo_24, "撤销", Modifier.weight(1f)) {}
            VisualToolbarItem(R.drawable.visual_redo_24, "恢复", Modifier.weight(1f)) {}
        }
      }
      Column(Modifier.width(150.dp).fillMaxSize().background(Color.White)) {
          Row(Modifier.fillMaxWidth().height(40.dp)) {
              PaletteTab("常用", !paletteMore, Modifier.weight(1f)) { paletteMore = false }
              PaletteTab("更多", paletteMore, Modifier.weight(1f)) { paletteMore = true }
          }
          val palette = if (!paletteMore) listOf(
              Triple(R.drawable.visual_control_label, "标签", RunnerUiControlKind.TEXT),
              Triple(R.drawable.visual_control_button, "按钮", RunnerUiControlKind.TEXT),
              Triple(R.drawable.visual_control_radio, "单选框", RunnerUiControlKind.CHOICE),
              Triple(R.drawable.visual_control_checkbox, "复选框", RunnerUiControlKind.BOOLEAN),
              Triple(R.drawable.visual_control_input, "单行文本", RunnerUiControlKind.TEXT),
              Triple(R.drawable.visual_control_textarea, "多行文本", RunnerUiControlKind.TEXT),
              Triple(R.drawable.visual_control_select, "下拉列表", RunnerUiControlKind.CHOICE),
          ) else listOf(
              Triple(R.drawable.visual_control_list, "列表框", RunnerUiControlKind.CHOICE),
              Triple(R.drawable.visual_control_anchor, "锚点导航", RunnerUiControlKind.CHOICE),
              Triple(R.drawable.visual_control_container, "容器", RunnerUiControlKind.TEXT),
              Triple(R.drawable.visual_control_slider, "滑块", RunnerUiControlKind.INTEGER),
              Triple(R.drawable.visual_control_progress, "进度条", RunnerUiControlKind.INTEGER),
          )
          LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
              itemsIndexed(palette) { _, item ->
                  val (icon, label, kind) = item
                  PaletteControl(icon, label, enabled = !busy && draft.fields.size < 32) {
                      draft = draft.add(kind)
                      selectedIndex = draft.fields.lastIndex
                      notice = null
                  }
              }
          }
      }
    }

    if (addVisible) {
        AlertDialog(
            onDismissRequest = { addVisible = false },
            title = { Text("添加控件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RunnerUiControlKind.entries.forEach { kind ->
                        OutlinedButton(
                            onClick = {
                                draft = draft.add(kind)
                                selectedIndex = draft.fields.lastIndex
                                addVisible = false
                                notice = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(kind.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addVisible = false }) { Text("取消") } },
        )
    }

    draft.fields.getOrNull(selectedIndex)?.let { selected ->
        RunnerUiFieldDialog(
            field = selected,
            onDismiss = { selectedIndex = -1 },
            onDelete = {
                draft = draft.remove(selectedIndex)
                selectedIndex = -1
                notice = null
            },
            onConfirm = { updated ->
                draft = draft.update(selectedIndex, updated)
                selectedIndex = -1
                notice = null
            },
        )
    }
}

@Composable
private fun VisualToolbarItem(@DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = Color(0xFF4F6687), modifier = Modifier.size(24.dp))
        Text(label, fontSize = 8.sp, color = Color(0xFF303A4A))
    }
}

@Composable
private fun PaletteTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (selected) Color(0xFF3A6EFF) else Color(0xFF59667A), fontSize = 11.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Box(Modifier.fillMaxWidth().height(if (selected) 2.dp else 0.dp).background(Color(0xFF3A6EFF)))
    }
}

@Composable
private fun PaletteControl(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(30.dp).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(1.dp),
    ) {
        Row(
            Modifier.fillMaxSize().background(Color.White).padding(start = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp))
            Text(label, color = Color(0xFF3A6EFF), fontSize = 8.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RunnerUiFieldPreview(field: RunnerUiFieldDraft) {
    when (field.kind) {
        RunnerUiControlKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = field.initialText.toBooleanStrictOrNull() == true,
                onCheckedChange = null,
                enabled = false,
            )
            Text("默认 ${field.initialText}", style = MaterialTheme.typography.labelSmall)
        }
        else -> Text(
            if (field.initialText.isBlank()) "默认值为空" else "默认：${field.initialText}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunnerUiFieldDialog(
    field: RunnerUiFieldDraft,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (RunnerUiFieldDraft) -> Unit,
) {
    var edited by remember(field) { mutableStateOf(field) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("控件属性") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("类型：${edited.kind.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = edited.id,
                    onValueChange = { edited = edited.copy(id = it) },
                    label = { Text("字段 ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = edited.label,
                    onValueChange = { edited = edited.copy(label = it) },
                    label = { Text("显示标题") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = edited.initialText,
                    onValueChange = { edited = edited.copy(initialText = it) },
                    label = { Text("默认值") },
                    singleLine = true,
                )
                if (edited.kind == RunnerUiControlKind.INTEGER) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = edited.minimum,
                            onValueChange = { edited = edited.copy(minimum = it) },
                            label = { Text("最小值") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = edited.maximum,
                            onValueChange = { edited = edited.copy(maximum = it) },
                            label = { Text("最大值") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
                if (edited.kind == RunnerUiControlKind.CHOICE) {
                    OutlinedTextField(
                        value = edited.optionsText,
                        onValueChange = { edited = edited.copy(optionsText = it) },
                        label = { Text("选项（逗号分隔）") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = edited.required,
                        onCheckedChange = { edited = edited.copy(required = it) },
                    )
                    Text("必填")
                }
                TextButton(onClick = onDelete) { Text("删除控件", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(edited) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
