package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette

internal data class ImageEntryAction(val title: String, val kind: String)
internal const val GLYPH_MAKER_ENTRY = "studio.glyphmaker"
internal const val COLOR_FILTER_ENTRY = "studio.colorfilter"
internal val imageEntryGroups = listOf(
    "找图" to listOf(ImageEntryAction("区域找图", "vision.findimage"), ImageEntryAction("灰度找图", "vision.findgray"),ImageEntryAction("多尺度找图","vision.findscaled"),ImageEntryAction("特征匹配","vision.findfeatures"),
        ImageEntryAction("多结果找图", "vision.findallimages"), ImageEntryAction("读取结果", "vision.result"), ImageEntryAction("结果动作", "vision.resultaction")),
    "颜色" to listOf(ImageEntryAction("找色", "vision.findcolor"), ImageEntryAction("多点找色", "vision.findmulticolor"),
        ImageEntryAction("取色", "vision.getcolor"), ImageEntryAction("比色", "vision.comparecolor"),
        ImageEntryAction("颜色数量", "vision.countcolor"), ImageEntryAction("多结果找色", "vision.findallcolor"),
        ImageEntryAction("批量多点找色", "vision.findallmulticolor"), ImageEntryAction("颜色连通块", "vision.components")),
    "文字" to listOf(ImageEntryAction("制作固定字库", GLYPH_MAKER_ENTRY), ImageEntryAction("字库识字", "ocr.glyph"),
        ImageEntryAction("字母 / 数字 / 符号", "ocr.alphanumeric"),
        ImageEntryAction("文本处理", TEXT_EXTRACTION_ENTRY),
        ImageEntryAction("英文数字 V5候选", "ocr.alphanumericv5"),
        ImageEntryAction("逐字结果", "ocr.glyphdetails"), ImageEntryAction("查找指定文字", "ocr.findtext"),
        ImageEntryAction("读取识别结果", RECOGNITION_RESULT_READER)),
    "处理" to listOf(ImageEntryAction("获取全屏帧", "screen.capture"), ImageEntryAction("区域截图", "screen.captureregion"),
        ImageEntryAction("释放图像帧", "screen.release"), ImageEntryAction("读取真实尺寸", "screen.frameinfo"),ImageEntryAction("加载项目图片", "screen.loadimage"), ImageEntryAction("裁剪图像", "screen.crop"),
        ImageEntryAction("滤色处理", COLOR_FILTER_ENTRY), ImageEntryAction("图像变换", "screen.transform"), ImageEntryAction("保存 PNG", "screen.savepng"),
        ImageEntryAction("连续采帧", "screen.series"), ImageEntryAction("读取系列帧", "screen.seriesframe"),
        ImageEntryAction("释放帧系列", "screen.seriesrelease"),ImageEntryAction("帧比较 / 区域等待","screen.compareframes"),ImageEntryAction("还原原屏坐标","screen.mappoint")),
)

@Composable
internal fun VisualImageHomeDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit,
    onCapture: (() -> Unit)?, onLibrary: (() -> Unit)?, onTest: (() -> Unit)?) {
    var group by remember { mutableIntStateOf(0) }
    var textTools by remember { mutableStateOf(false) }
    if (textTools) {
        TextExtractionToolsDialog({ textTools = false }) { kind -> textTools = false; onCreate(kind) }
        return
    }
    StudioPanelDialog("图像 · 识别工具", onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("截图取材后，可直接框选找图、取色或制作字库", fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
            listOf("截图取材" to onCapture, "图片库" to onLibrary, "识别测试" to onTest,
                "字库制作" to { onCreate(GLYPH_MAKER_ENTRY) }).chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (label, callback) ->
                        ImageEntryButton(label, callback != null, Modifier.weight(1f), accent = true) { callback?.invoke() }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(6.dp)).padding(3.dp)) {
                imageEntryGroups.forEachIndexed { index, (title, _) ->
                    Box(Modifier.weight(1f).height(32.dp).background(if (index == group) AutoScriptPalette.AccentSoft else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { group = index }, contentAlignment = Alignment.Center) {
                        Text(title, fontSize = 12.sp, fontWeight = if (index == group) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == group) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
                    }
                }
            }
            imageEntryGroups[group].second.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { action ->
                        Column(Modifier.weight(1f).background(AutoScriptPalette.VisualEditor.ControlBackground, RoundedCornerShape(5.dp))
                            .border(1.dp, AutoScriptPalette.Divider, RoundedCornerShape(5.dp)).clickable {
                                if (action.kind == TEXT_EXTRACTION_ENTRY) textTools = true else onCreate(action.kind)
                            }.padding(8.dp)) {
                            Text(action.title, fontSize = 12.sp, color = AutoScriptPalette.Accent, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(imageActionHint(action.kind), fontSize = 10.sp, color = AutoScriptPalette.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text(when (group) {
                0 -> "找图需保存模板；读取结果不需要再次截图。"
                1 -> "取点取色回填颜色和位置；加入后运行时截图。"
                2 -> "字库需登记资源；文字查询读取已有逐字结果，不重复识别。"
                else -> "原像素处理，不改原图；输出帧使用完请释放。"
            }, fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
        }
    }
}

internal fun imageActionHint(kind: String): String = when (kind) {
    GLYPH_MAKER_ENTRY -> "截图采样 · 数字/字母/符号 · 无模型"
    COLOR_FILTER_ENTRY -> "多组颜色 · 相对/绝对 · 黑白/透明"
    "vision.findimage" -> "单图 / 多模板 · 首个命中"
    "vision.findgray" -> "灰度最高分 · 不缩放旋转"
    "vision.findfeatures" -> "ORB / RANSAC · 尺度与透视 · 有纹理图片"
    "ocr.alphanumericv5" -> "显式候选 · 不替换默认 · 准确率依画面"
    "screen.frameinfo" -> "实际帧宽高 / 方向 / 原屏来源"
    "screen.capture" -> "采集一帧 · 后续识别选择已有图像"
    "screen.release" -> "共帧识别完成后显式释放"
    "screen.captureregion" -> "原屏区域新帧 · 局部识别后映射原屏"
    "vision.findallimages" -> "最多64项 · 成功/失败分支"
    "vision.result" -> "读取数量、坐标、得分"
    "vision.resultaction" -> "首项 / 逐项 · 原屏坐标确认"
    "vision.readresult" -> "有界专用属性 · 不重复识别"
    "vision.findcolor" -> "区域内查找指定颜色"
    "vision.findmulticolor" -> "锚点颜色 + 多个偏移点"
    "vision.getcolor" -> "读取指定点的颜色"
    "vision.comparecolor" -> "判断指定点颜色是否相符"
    "vision.countcolor" -> "统计区域内匹配像素"
    "vision.findallcolor" -> "保留多个颜色匹配点"
    "vision.findallmulticolor" -> "批量保留多点匹配位置"
    "vision.components" -> "4/8邻接 · 面积与矩形"
    "ocr.glyph" -> "使用项目字库识别文字"
    "ocr.alphanumeric" -> "单行英文数字 · 可保留括号和符号"
    TEXT_EXTRACTION_ENTRY -> "任意文本 · 规则 / 分隔 / 首项 / 全部"
    "ocr.glyphdetails" -> "逐字位置 / 分数 / 状态"
    "ocr.findtext" -> "从详细结果定位连续文字"
    RECOGNITION_RESULT_READER -> "文字 / 颜色 / 连通块 · 字段读取"
    "screen.loadimage" -> "加载已声明图片，不截图"
    "screen.crop" -> "原像素半开区间裁剪"
    "screen.transform" -> "灰度 / 二值化 / 旋转 / 缩放"
    "screen.compareframes" -> "比较已有帧 / 新鲜截图稳定等待"
    "screen.mappoint" -> "真实截图变换链 · 不自动点击"
    "vision.findscaled" -> "最多16档 · 首个达标 · 实际比例"
    "screen.savepng" -> "保存已有帧到私有 data"
    "screen.series" -> "有界采集 · 数量 / 时长 / FPS"
    "screen.seriesframe" -> "读取借用帧 · 不单独释放"
    "screen.seriesrelease" -> "释放系列及其借用帧"
    else -> "配置识别积木"
}

@Composable
private fun ImageEntryButton(label: String, enabled: Boolean, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(modifier.heightIn(min = 32.dp).padding(vertical = 2.dp).background(if (accent) AutoScriptPalette.AccentSoft else AutoScriptPalette.VisualEditor.ControlBackground, shape)
        .border(1.dp, AutoScriptPalette.Divider, shape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 11.sp, color = if (enabled) AutoScriptPalette.Accent else AutoScriptPalette.TextSecondary,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}
