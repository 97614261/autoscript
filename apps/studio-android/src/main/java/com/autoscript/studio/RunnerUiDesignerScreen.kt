package com.autoscript.studio

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VE = AutoScriptPalette.VisualEditor

/** 右栏三个页签：`visual_drag_common_controls`（常用/更多）与 `visual_right_panel_property_page`（属性）。 */
private enum class DesignerRightTab(val label: String) { COMMON("常用"), MORE("更多"), PROPERTY("属性") }

/** 控件卡；`kind == null` 表示当前 Runner 表单没有对应字段类型，只展示不可拖入。 */
private data class DesignerPaletteEntry(@DrawableRes val icon: Int, val label: String, val kind: RunnerUiControlKind?)

private val COMMON_CONTROLS = listOf(
    DesignerPaletteEntry(R.drawable.visual_control_label, "标签", null),
    DesignerPaletteEntry(R.drawable.visual_control_button, "按钮", null),
    DesignerPaletteEntry(R.drawable.visual_control_radio, "单选框", RunnerUiControlKind.CHOICE),
    DesignerPaletteEntry(R.drawable.visual_control_checkbox, "复选框", RunnerUiControlKind.BOOLEAN),
    DesignerPaletteEntry(R.drawable.visual_control_input, "单行文本", RunnerUiControlKind.TEXT),
    DesignerPaletteEntry(R.drawable.visual_control_textarea, "多行文本", null),
    DesignerPaletteEntry(R.drawable.visual_control_select, "下拉列表", RunnerUiControlKind.CHOICE),
)

private val MORE_CONTROLS = listOf(
    DesignerPaletteEntry(R.drawable.visual_control_list, "列表框", null),
    DesignerPaletteEntry(R.drawable.visual_control_anchor, "锚点导航", null),
    DesignerPaletteEntry(R.drawable.visual_control_container, "容器", null),
    DesignerPaletteEntry(R.drawable.visual_control_slider, "整数滑块", RunnerUiControlKind.INTEGER),
    DesignerPaletteEntry(R.drawable.visual_control_progress, "进度条", null),
)

/**
 * `activity_visual_ui.xml`：40dp 顶/底工具栏（`#F3F6FB`，图标 `#46618A`，8sp 标签）、100dp 左栏元素列表
 * （容器 `#E5EBF3`、列表白）、蓝框画布、150dp 右栏（30dp Tab + 控件卡 / 属性页）。
 * 属性编辑在右栏“属性”页内完成，不再弹对话框；撤销/恢复只覆盖结构操作（添加、删除、复制、移动）。
 */
@Composable
internal fun RunnerUiDesignerScreen(
    snapshot: ProjectSnapshot,
    store: ProjectStore,
    onSnapshotChanged: (ProjectSnapshot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as? Activity
    val view = LocalView.current
    // 参考的设计器是横屏全屏，截图里没有状态栏；我们原来保留了状态栏，画布被往下挤了一条。
    // 这里进入时隐藏状态栏，退出时恢复，和横屏切换共用同一个生命周期。
    DisposableEffect(activity, view) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    var draft by remember(snapshot.manifest.projectId) {
        mutableStateOf(runnerUiDesignerDraft(snapshot.manifest.runnerUi))
    }
    var undoStack by remember(snapshot.manifest.projectId) { mutableStateOf(emptyList<RunnerUiDesignerDraft>()) }
    var redoStack by remember(snapshot.manifest.projectId) { mutableStateOf(emptyList<RunnerUiDesignerDraft>()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var rightTab by remember { mutableStateOf(DesignerRightTab.COMMON) }
    var previewMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = !busy, onBack = onBack)

    val selected = draft.fields.getOrNull(selectedIndex)

    fun commit(next: RunnerUiDesignerDraft) {
        undoStack = (undoStack + draft).takeLast(MAX_DESIGNER_HISTORY)
        redoStack = emptyList()
        draft = next
        notice = null
    }

    fun addControl(entry: DesignerPaletteEntry) {
        val kind = entry.kind
        if (kind == null) {
            notice = "“${entry.label}”在当前 Runner 表单中未开放"
            return
        }
        if (draft.fields.size >= 32) {
            error = "界面字段不能超过 32 个"
            return
        }
        commit(draft.add(kind))
        selectedIndex = draft.fields.lastIndex
        rightTab = DesignerRightTab.PROPERTY
    }

    fun duplicateSelected() {
        val source = selected ?: run { notice = "先在左栏或画布中选中一个控件"; return }
        if (draft.fields.size >= 32) { error = "界面字段不能超过 32 个"; return }
        val added = draft.add(source.kind)
        val freshId = added.fields.last().id
        commit(added.update(added.fields.lastIndex, source.copy(id = freshId, label = source.label + "副本")))
        selectedIndex = draft.fields.lastIndex
    }

    fun deleteSelected() {
        if (selected == null) { notice = "先选中要删除的控件"; return }
        commit(draft.remove(selectedIndex))
        selectedIndex = -1
        if (rightTab == DesignerRightTab.PROPERTY) rightTab = DesignerRightTab.COMMON
    }

    fun moveSelected(offset: Int) {
        val moved = draft.move(selectedIndex, offset)
        if (moved === draft) return
        commit(moved)
        selectedIndex += offset
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: run { notice = "没有可撤销的操作"; return }
        redoStack = redoStack + draft
        undoStack = undoStack.dropLast(1)
        draft = previous
        selectedIndex = selectedIndex.coerceAtMost(draft.fields.lastIndex)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: run { notice = "没有可恢复的操作"; return }
        undoStack = undoStack + draft
        redoStack = redoStack.dropLast(1)
        draft = next
        selectedIndex = selectedIndex.coerceAtMost(draft.fields.lastIndex)
    }

    fun save() {
        val candidate = runCatching { draft.toRunnerUiJson() }.getOrElse { error = it.message ?: "界面配置无效"; return }
        busy = true; error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = latestSnapshot
                    store.updateRunnerUi(current.manifest.projectId, candidate, current.manifest.runnerUi)
                }
            }.onSuccess { onSnapshotChanged(it); notice = if (candidate == null) "脚本界面已清空" else "脚本界面已保存" }
                .onFailure { error = it.message ?: "脚本界面保存失败" }
            busy = false
        }
    }

    Row(modifier.fillMaxSize().background(VE.PageBackground)) {
        Column(Modifier.weight(1f).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(40.dp).background(VE.ToolbarBackground)) {
                DesignerToolbarItem(R.drawable.visual_back_24, "退出", Modifier.weight(1f), enabled = !busy) { onBack() }
                DesignerToolbarItem(R.drawable.visual_page_24, "页面", Modifier.weight(1f)) { notice = "多页面界面未开放，当前 Runner 只渲染单页表单" }
                DesignerToolbarItem(R.drawable.editor_delete_24, "删除", Modifier.weight(1f), enabled = !busy) { deleteSelected() }
                DesignerToolbarItem(R.drawable.visual_adjust_24, "调整", Modifier.weight(1f)) {
                    if (selected == null) notice = "先选中控件，再在“属性”页中上移/下移" else rightTab = DesignerRightTab.PROPERTY
                }
                DesignerToolbarItem(R.drawable.visual_multi_24, "多选", Modifier.weight(1f)) { notice = "多选未开放" }
                DesignerToolbarItem(R.drawable.visual_preview_24, if (previewMode) "编辑态" else "预览", Modifier.weight(1f)) { previewMode = !previewMode }
                DesignerToolbarItem(R.drawable.visual_code_24, "编辑", Modifier.weight(1f)) { notice = "界面 JSON 请在“项目设置 → 脚本界面”中编辑" }
                DesignerToolbarItem(R.drawable.editor_swap_24, "属性", Modifier.weight(1f)) {
                    if (selected == null) notice = "先选中一个控件" else rightTab = DesignerRightTab.PROPERTY
                }
                DesignerToolbarItem(R.drawable.visual_save_24, if (busy) "保存中" else "保存", Modifier.weight(1f), enabled = !busy) { save() }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // 左栏：元素列表，容器 #E5EBF3、列表白、30dp 行。
                Box(Modifier.width(100.dp).fillMaxHeight().background(VE.SideBackground).padding(end = 1.dp)) {
                    LazyColumn(Modifier.fillMaxSize().background(Color.White)) {
                        itemsIndexed(draft.fields, key = { _, field -> field.id }) { index, field ->
                            val active = index == selectedIndex
                            Text(
                                field.label,
                                modifier = Modifier.fillMaxWidth().height(30.dp)
                                    .background(if (active) VE.AccentSoft else Color.Transparent)
                                    .clickable(enabled = !busy) { selectedIndex = index }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                                fontSize = 10.sp,
                                color = if (active) VE.Accent else VE.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (draft.fields.isEmpty()) {
                            item { Text("从右栏点选控件加入", fontSize = 8.sp, color = VE.TextSecondary, modifier = Modifier.padding(6.dp)) }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(380.dp).fillMaxSize().background(Color.White).border(1.dp, VE.Accent)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (draft.fields.isEmpty()) {
                                Text("空白界面：脚本运行前不会弹出参数表单", fontSize = 10.sp, color = VE.TextSecondary)
                            }
                            draft.fields.forEachIndexed { index, field ->
                                val active = !previewMode && selectedIndex == index
                                Box(
                                    Modifier.fillMaxWidth().height(42.dp)
                                        .border(1.dp, if (active) VE.Accent else Color.Transparent)
                                        .clickable(enabled = !busy) { selectedIndex = index }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Column { Text(field.label, fontSize = 11.sp, color = VE.TextPrimary); RunnerUiFieldPreview(field) }
                                }
                            }
                        }
                    }
                }
            }
            (error ?: notice)?.let { message ->
                Text(
                    message,
                    color = if (error != null) AutoScriptPalette.Danger else VE.Accent,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Row(Modifier.fillMaxWidth().height(40.dp).background(VE.ToolbarBackground)) {
                Box(Modifier.width(100.dp).fillMaxHeight().background(VE.SideBackground))
                DesignerToolbarItem(R.drawable.visual_copy_24, "复制", Modifier.weight(1f), enabled = !busy, bottomBar = true) { duplicateSelected() }
                DesignerToolbarItem(R.drawable.visual_align_24, "对齐", Modifier.weight(1f), bottomBar = true) { notice = "单列表单自动对齐，无需手动调整" }
                // line_visual_ui_xywh：9sp monospace、text_primary、居中、最多两行。
                Text(
                    if (selected == null) "坐标显示" else "第 ${selectedIndex + 1} 项 / 共 ${draft.fields.size} 项 · ${selected.kind.label}",
                    modifier = Modifier.weight(5f).fillMaxHeight().wrapContentHeight(),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    color = VE.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DesignerToolbarItem(R.drawable.visual_undo_24, "撤销", Modifier.weight(1f), enabled = !busy && undoStack.isNotEmpty(), bottomBar = true) { undo() }
                DesignerToolbarItem(R.drawable.visual_redo_24, "恢复", Modifier.weight(1f), enabled = !busy && redoStack.isNotEmpty(), bottomBar = true) { redo() }
            }
        }
        Column(Modifier.width(150.dp).fillMaxSize().background(Color.White)) {
            Row(Modifier.fillMaxWidth().height(30.dp)) {
                DesignerRightTab.entries.forEach { tab ->
                    DesignerTab(tab.label, rightTab == tab, Modifier.weight(1f)) { rightTab = tab }
                }
            }
            when (rightTab) {
                DesignerRightTab.COMMON -> DesignerPalette(COMMON_CONTROLS, enabled = !busy, onPick = ::addControl)
                DesignerRightTab.MORE -> DesignerPalette(MORE_CONTROLS, enabled = !busy, onPick = ::addControl)
                DesignerRightTab.PROPERTY -> {
                    val field = selected
                    if (field == null) {
                        Text("未选中控件", fontSize = 10.sp, color = VE.TextSecondary, modifier = Modifier.padding(12.dp))
                    } else {
                        DesignerPropertyPage(
                            field = field,
                            enabled = !busy,
                            canMoveUp = selectedIndex > 0,
                            canMoveDown = selectedIndex < draft.fields.lastIndex,
                            onChange = { draft = draft.update(selectedIndex, it); notice = null },
                            onMove = ::moveSelected,
                            onDelete = ::deleteSelected,
                        )
                    }
                }
            }
        }
    }
}

/** 顶栏标签用 `text_primary`，底栏标签用 `toolbar_icon`（`activity_visual_ui.xml` 两栏的 textColor 不同）。 */
@Composable
private fun DesignerToolbarItem(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    bottomBar: Boolean = false,
    onClick: () -> Unit,
) {
    val labelColor = if (bottomBar) VE.ToolbarIcon else VE.TextPrimary
    Column(
        modifier = modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = VE.ToolbarIcon.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 8.sp, color = labelColor.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1)
    }
}

@Composable
private fun DesignerTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) VE.Accent else VE.TextSecondary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) VE.Accent else Color.Transparent))
    }
}

/** `visual_drag_common_controls.xml`：控件卡 100×30 在等分单元格内居中。 */
@Composable
private fun DesignerPalette(entries: List<DesignerPaletteEntry>, enabled: Boolean, onPick: (DesignerPaletteEntry) -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        entries.forEach { entry ->
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val available = entry.kind != null
                Surface(
                    modifier = Modifier.width(100.dp).height(30.dp).clickable(enabled = enabled) { onPick(entry) },
                    shape = RoundedCornerShape(2.dp),
                    color = Color.White,
                    shadowElevation = 1.dp,
                ) {
                    Row(Modifier.fillMaxSize().padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        // visual_drag_common_controls：控件图标 tint=lan3，预留项用次要色。
                        Icon(
                            painterResource(entry.icon),
                            contentDescription = null,
                            tint = if (available) VE.Accent else VE.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            if (available) entry.label else "${entry.label}·预留",
                            color = if (available) VE.Accent else VE.TextSecondary,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** `visual_right_panel_property_page.xml`：右栏内滚动属性页；修改即时写入草稿。 */
@Composable
private fun DesignerPropertyPage(
    field: RunnerUiFieldDraft,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (RunnerUiFieldDraft) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("类型：${field.kind.label}", fontSize = 9.sp, color = VE.TextSecondary)
        DesignerPropertyInput("字段 ID", field.id, enabled) { onChange(field.copy(id = it)) }
        DesignerPropertyInput("显示标题", field.label, enabled) { onChange(field.copy(label = it)) }
        DesignerPropertyInput("默认值", field.initialText, enabled) { onChange(field.copy(initialText = it)) }
        if (field.kind == RunnerUiControlKind.INTEGER) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { DesignerPropertyInput("最小值", field.minimum, enabled) { onChange(field.copy(minimum = it)) } }
                Box(Modifier.weight(1f)) { DesignerPropertyInput("最大值", field.maximum, enabled) { onChange(field.copy(maximum = it)) } }
            }
        }
        if (field.kind == RunnerUiControlKind.CHOICE) {
            DesignerPropertyInput("选项（逗号分隔）", field.optionsText, enabled, singleLine = false) { onChange(field.copy(optionsText = it)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = field.required,
                onCheckedChange = { onChange(field.copy(required = it)) },
                enabled = enabled,
                colors = CheckboxDefaults.colors(checkedColor = VE.Accent),
                modifier = Modifier.size(28.dp),
            )
            Text("必填", fontSize = 10.sp, color = VE.TextPrimary, modifier = Modifier.padding(start = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesignerPropertyButton("上移", VE.Accent, Modifier.weight(1f), enabled && canMoveUp) { onMove(-1) }
            DesignerPropertyButton("下移", VE.Accent, Modifier.weight(1f), enabled && canMoveDown) { onMove(1) }
        }
        DesignerPropertyButton("删除控件", AutoScriptPalette.Danger, Modifier.fillMaxWidth(), enabled, onDelete)
    }
}

@Composable
private fun DesignerPropertyInput(
    label: String,
    value: String,
    enabled: Boolean,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(label, fontSize = 8.sp, color = VE.TextSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 10.sp, color = VE.TextPrimary),
            cursorBrush = SolidColor(VE.Accent),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                .background(VE.ControlBackground, RoundedCornerShape(3.dp))
                .border(1.dp, VE.Border, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DesignerPropertyButton(label: String, color: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = color.copy(alpha = if (enabled) 1f else 0.4f),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier.height(26.dp)
            .border(1.dp, color.copy(alpha = if (enabled) 0.6f else 0.25f), RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(top = 6.dp),
    )
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
            Text("默认 ${field.initialText}", fontSize = 9.sp, color = VE.TextSecondary)
        }
        else -> Text(
            if (field.initialText.isBlank()) "默认值为空" else "默认：${field.initialText}",
            fontSize = 9.sp,
            color = VE.TextSecondary,
        )
    }
}

private const val MAX_DESIGNER_HISTORY = 64
