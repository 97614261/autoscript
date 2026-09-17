package com.autoscript.studio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.BackupSlot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 槽位上的三个写操作，都要先过二次确认（对照 `dk.java` 里三个按钮统一走 `s(...)` 确认框）。 */
private enum class SlotAction(val title: String, val confirm: String) {
    BACKUP("备份到槽位", "开始备份"),
    RESTORE("从槽位恢复", "恢复为新项目"),
    DELETE("清空槽位", "确认清空"),
}

private data class SlotConfirm(val action: SlotAction, val slot: Int)

/**
 * `activity_backup_slots.xml` + `item_backup_slot.xml`：58dp 标题栏（项目名 + “可用 N 槽位”）、
 * 11sp 提示条、minHeight 92 的槽位卡（48dp 标题块 + 30dp 备注行 + 40dp 按钮行，三个 28dp 按钮），
 * 备份/恢复进行时底部 58dp 进度面板。
 *
 * 行为对照 `dk.java`：空槽把“恢复/删除”设为 GONE（备份按钮因此撑满整行）；占用槽的备注行恒显示，
 * 备注为空时填默认文案；三个按钮都先弹确认框，备份那次还带备注输入。
 */
@Composable
internal fun ProjectBackupSlotsScreen(
    projectName: String,
    localProjectExists: Boolean,
    slots: List<BackupSlot>,
    progressLabel: String?,
    message: String?,
    onBack: () -> Unit,
    onBackup: (Int, String?) -> Unit,
    onRestore: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onExportFile: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<SlotConfirm?>(null) }
    val busy = progressLabel != null
    BackHandler(enabled = !busy, onBack = onBack)
    val timestamp = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val free = slots.count { !it.occupied }
    Column(modifier.fillMaxSize().background(AutoScriptPalette.PageBackground)) {
        UtilityLightHeader(projectName, "可用 $free 槽位", onBack) {
            if (onExportFile != null) {
                Text(
                    "导出文件",
                    color = AutoScriptPalette.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp).clickable(enabled = !busy, onClick = onExportFile).padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
        Text(
            if (localProjectExists) "备份会覆盖所选槽位；恢复会导入为一个新项目，不覆盖本地项目" else "本机没有这个项目；恢复会把槽位导入为新项目",
            color = AutoScriptPalette.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp),
        )
        message?.let {
            Text(
                it,
                color = if (it.startsWith("已")) AutoScriptPalette.Accent else AutoScriptPalette.Danger,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(slots, key = BackupSlot::index) { slot ->
                Surface(Modifier.fillMaxWidth().heightIn(min = 92.dp), color = Color.White, shape = RoundedCornerShape(14.dp)) {
                    Column {
                        Column(
                            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 13.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("备份槽位 ${slot.index}", color = AutoScriptPalette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (slot.occupied) {
                                    "${timestamp.format(Date(slot.createdAt ?: 0L))} · ${formatBackupSize(slot.bytes ?: 0L)}" +
                                        (slot.projectName?.takeIf { it != projectName }?.let { " · $it" } ?: "")
                                } else {
                                    "尚未备份"
                                },
                                color = AutoScriptPalette.TextSecondary,
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                        // dk.java：占用槽位时备注行恒显示，备注为空时填默认文案，不是整行隐藏。
                        if (slot.occupied) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 3.dp).height(30.dp)
                                    .background(AutoScriptPalette.AccentSoft, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("备注", color = AutoScriptPalette.Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    slot.remark?.takeIf { it.isNotBlank() } ?: "未填写备注",
                                    color = AutoScriptPalette.TextPrimary,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 9.dp),
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SlotButton("备份", SlotButtonStyle.PRIMARY, enabled = localProjectExists && !busy, Modifier.weight(1f)) {
                                confirm = SlotConfirm(SlotAction.BACKUP, slot.index)
                            }
                            if (slot.occupied) {
                                SlotButton("恢复", SlotButtonStyle.SECONDARY, enabled = !busy, Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                    confirm = SlotConfirm(SlotAction.RESTORE, slot.index)
                                }
                                SlotButton("删除", SlotButtonStyle.DANGER, enabled = !busy, Modifier.weight(1f)) {
                                    confirm = SlotConfirm(SlotAction.DELETE, slot.index)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (progressLabel != null) {
            Column(Modifier.fillMaxWidth().height(58.dp).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(progressLabel, color = AutoScriptPalette.TextPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(4.dp),
                    color = AutoScriptPalette.Accent,
                    trackColor = AutoScriptPalette.AccentSoft,
                )
            }
        }
    }

    confirm?.let { pending ->
        val slot = slots.firstOrNull { it.index == pending.slot }
        SlotConfirmDialog(
            action = pending.action,
            slot = pending.slot,
            occupied = slot?.occupied == true,
            initialRemark = slot?.remark.orEmpty(),
            onDismiss = { confirm = null },
            onConfirm = { remark ->
                confirm = null
                when (pending.action) {
                    SlotAction.BACKUP -> onBackup(pending.slot, remark)
                    SlotAction.RESTORE -> onRestore(pending.slot)
                    SlotAction.DELETE -> onDelete(pending.slot)
                }
            },
        )
    }
}

/**
 * `service_tk_prompt_dialog.xml`：20dp 上内边距、18sp `#304ffe` 标题（marginLeft 25）、
 * 25dp 边距正文、右对齐的“取消 / 确定”文字按钮（`#2962ff`，padding 15/8，确定 marginRight 15）。
 *
 * 备份那次额外带一个备注输入，样式沿用 `service_tk_new_file` 的输入框（15sp、左右 30dp、maxLength 30）。
 */
@Composable
private fun SlotConfirmDialog(
    action: SlotAction,
    slot: Int,
    occupied: Boolean,
    initialRemark: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var remark by remember(slot) { mutableStateOf(initialRemark) }
    val body = when (action) {
        SlotAction.BACKUP ->
            if (occupied) "槽位 $slot 已有备份，继续将覆盖它。备份只包含权威清单、源码和已登记资源。"
            else "把当前项目导出到槽位 $slot。备份只包含权威清单、源码和已登记资源。"
        SlotAction.RESTORE -> "槽位 $slot 的备份会导入为一个新项目，不会覆盖本机已有项目。"
        SlotAction.DELETE -> "清空槽位 $slot 后，该备份无法恢复。"
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(2.dp),
                shadowElevation = 9.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(
                        action.title,
                        color = Color(0xFF304FFE),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(start = 25.dp, end = 25.dp),
                    )
                    Text(
                        body,
                        color = Color.Black,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 25.dp, top = 10.dp, end = 25.dp, bottom = 10.dp),
                    )
                    if (action == SlotAction.BACKUP) {
                        BasicTextField(
                            value = remark,
                            onValueChange = { remark = it.take(MAX_SLOT_REMARK_INPUT) },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = AutoScriptPalette.TextPrimary),
                            cursorBrush = SolidColor(AutoScriptPalette.Accent),
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 30.dp, end = 30.dp, bottom = 5.dp)
                                .background(AutoScriptPalette.PageBackground, RoundedCornerShape(2.dp))
                                .border(1.dp, AutoScriptPalette.Border, RoundedCornerShape(2.dp))
                                .padding(horizontal = 9.dp, vertical = 10.dp),
                            decorationBox = { field ->
                                Box {
                                    if (remark.isEmpty()) {
                                        Text("备注（可选）", color = AutoScriptPalette.TextSecondary, fontSize = 15.sp)
                                    }
                                    field()
                                }
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SlotDialogTextButton("取消", onDismiss)
                        SlotDialogTextButton(action.confirm, Modifier.padding(end = 15.dp)) {
                            onConfirm(remark.trim().takeIf { it.isNotEmpty() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotDialogTextButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF2962FF),
        fontSize = 14.sp,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 8.dp),
    )
}

@Composable
private fun SlotDialogTextButton(label: String, onClick: () -> Unit) = SlotDialogTextButton(label, Modifier, onClick)

/** `ProjectStore.MAX_BACKUP_REMARK_CHARS` 是 64，输入框留同样的上限。 */
private const val MAX_SLOT_REMARK_INPUT = 64

private enum class SlotButtonStyle { PRIMARY, SECONDARY, DANGER }

/**
 * `theme_backup_primary / secondary / danger`：28dp 高、10sp 加粗。
 *
 * 三种样式在参考截图里**都带描边**（浅底 + 同色轮廓），只填底色会比参考“平”；
 * 禁用态去掉描边并转灰，避免看起来仍可点。
 */
@Composable
private fun SlotButton(label: String, style: SlotButtonStyle, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (background, foreground) = when (style) {
        SlotButtonStyle.PRIMARY -> AutoScriptPalette.AccentSoft to AutoScriptPalette.Accent
        SlotButtonStyle.SECONDARY -> Color.White to AutoScriptPalette.Accent
        SlotButtonStyle.DANGER -> Color(0xFFFFF1F2) to AutoScriptPalette.Danger
    }
    Box(
        modifier
            .height(28.dp)
            .background(if (enabled) background else Color(0xFFF3F4F6), RoundedCornerShape(8.dp))
            .border(
                1.dp,
                if (enabled) foreground.copy(alpha = .55f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) foreground else AutoScriptPalette.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
