package com.autoscript.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val EntryBlue = Color(0xFF3D5AFE)
private val EntryAccent = Color(0xFF3A6EFF)
private val EntryBorder = Color(0xFFC7CBD1)
private val EntryMuted = Color(0xFF727B8B)
private val EntryPanel = Color(0xFFF8F9FB)

private data class PickerRequest(
    val title: String,
    val options: List<String>,
    val selected: String? = null,
    val onSelected: (String) -> Unit,
)

@Composable
internal fun EditorEntryDialog(
    entry: LegacyToolDialog,
    projectName: String,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val panelModifier = when (entry) {
            LegacyToolDialog.FILES -> Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f).widthIn(max = 520.dp)
            LegacyToolDialog.JUDGMENT -> Modifier.width(132.dp).height(132.dp)
            LegacyToolDialog.COMMON,
            LegacyToolDialog.DEBUG,
            -> Modifier.fillMaxWidth(.96f).widthIn(max = 560.dp).height(390.dp)
            LegacyToolDialog.IMAGE -> Modifier.fillMaxWidth(.96f).widthIn(max = 550.dp).height(310.dp)
            LegacyToolDialog.LOOP -> Modifier.fillMaxWidth(.96f).widthIn(max = 540.dp).height(350.dp)
            else -> Modifier.fillMaxWidth(.90f).widthIn(max = 520.dp).height(
                when (entry) {
                    LegacyToolDialog.AI -> 520.dp
                    else -> 390.dp
                },
            )
        }
        Surface(
            modifier = panelModifier,
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 8.dp,
        ) {
            when (entry) {
                LegacyToolDialog.FILES -> FileManagerPage(projectName, onDismiss, onInsert)
                LegacyToolDialog.TOOLS -> ToolSettingsPage(onDismiss, onInsert)
                LegacyToolDialog.IMAGE -> ImageRecognitionPage(onDismiss, onInsert)
                LegacyToolDialog.JUDGMENT -> JudgmentPage(onDismiss, onInsert)
                LegacyToolDialog.LOOP -> LoopPage(onDismiss, onInsert)
                LegacyToolDialog.COMMON -> CommonPage(onDismiss, onInsert)
                LegacyToolDialog.DEBUG -> DebugPage(onDismiss, onInsert)
                LegacyToolDialog.AI -> AiProgrammingPage(onDismiss, onInsert)
                LegacyToolDialog.DATA_BACKFILL -> DataBackfillPage(onDismiss, onInsert)
                LegacyToolDialog.VARIABLE_CHECK -> VariableCheckPage(onDismiss)
                LegacyToolDialog.RUNTIME_VARIABLES -> RuntimeVariablesPage(onDismiss)
                else -> Unit
            }
        }
    }
}

@Composable
private fun FileManagerPage(projectName: String, onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuVisible by remember { mutableStateOf(false) }
    var reverseSort by remember { mutableStateOf(false) }
    val projectRoot = remember(projectName) { "/项目文件/$projectName" }
    var currentPath by remember(projectName) { mutableStateOf(projectRoot) }
    val rootFiles = listOf(
        Triple("..", true, ""),
        Triple("Lua", true, "2026-09-04 16:25"),
        Triple("临时文件", true, "2026-09-04 16:25"),
        Triple("图片", true, "2026-09-04 16:25"),
        Triple("字库", true, "2026-09-04 16:25"),
        Triple("撤销恢复", true, "2026-09-14 15:26"),
        Triple("源文件", true, "2026-09-14 15:30"),
        Triple("界面", true, "2026-09-04 16:25"),
        Triple("config.json", false, "2026-09-04 19:14    155B"),
        Triple("变量.json", false, "2026-09-04 16:25    0B"),
        Triple("控制台日志输出.log", false, "2026-09-14 15:30    0B"),
    )
    val nestedFiles = listOf(Triple("..", true, ""), Triple("暂无文件", false, "0B"))
    val source = if (currentPath == projectRoot) rootFiles else nestedFiles
    val files = source.filter { query.isBlank() || it.first.contains(query, ignoreCase = true) }
        .let { if (reverseSort) it.reversed() else it }
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 12.sp), modifier = Modifier.weight(1f))
                HeaderAction("取消") { searching = false; query = "" }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 15.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(currentPath, color = EntryBlue, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Box {
                    HeaderIconAction(R.drawable.editor_menu_24, "文件菜单") { menuVisible = true }
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }, containerColor = Color.White) {
                        DropdownMenuItem(text = { Text("新建", fontSize = 13.sp) }, onClick = {
                            menuVisible = false
                            onInsert("-- new file\n")
                        })
                        DropdownMenuItem(text = { Text("刷新", fontSize = 13.sp) }, onClick = { menuVisible = false })
                        DropdownMenuItem(text = { Text("搜索", fontSize = 13.sp) }, onClick = { menuVisible = false; searching = true })
                        DropdownMenuItem(text = { Text("排序", fontSize = 13.sp) }, onClick = { menuVisible = false; reverseSort = !reverseSort })
                    }
                }
            }
        }
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            files.forEach { (name, folder, detail) ->
                Row(
                    Modifier.fillMaxWidth().height(58.dp).clickable {
                        when {
                            name == ".." -> currentPath = currentPath.substringBeforeLast('/', currentPath)
                            folder -> currentPath += "/$name"
                            name != "暂无文件" -> onInsert("-- file: $name\n")
                        }
                    }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (folder) R.drawable.editor_folder_24 else R.drawable.editor_code_file_24),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.padding(end = 10.dp).size(if (folder) 36.dp else 28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 14.sp)
                        if (detail.isNotEmpty()) Text(detail, color = EntryMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        FooterButtons(listOf("取消" to onDismiss), fontSize = 13)
    }
}

@Composable
private fun FileManagerBottomAction(icon: Int, label: String, onClick: () -> Unit = {}) {
    Column(Modifier.width(44.dp).fillMaxHeight().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), contentDescription = label, tint = EntryAccent, modifier = Modifier.size(23.dp))
        Text(label, fontSize = 8.sp, color = EntryAccent)
    }
}

@Composable
private fun HeaderIconAction(icon: Int, description: String, onClick: () -> Unit) {
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = Color.Unspecified,
        modifier = Modifier.size(36.dp).clickable(onClick = onClick).padding(6.dp),
    )
}

@Composable
private fun DataBackfillPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    EditorColumnPage("数据回填", onDismiss, "回填", {
        onInsert("${LEGACY_ENTRY_COMMAND_PREFIX}DATA_BACKFILL\n")
        onDismiss()
    }) {
        Text("把调试或识别结果回填到当前节点参数", color = EntryMuted, fontSize = 10.sp)
        EntryField("数据来源", "最近一次调试结果") {}
        EntryField("目标节点", "当前选中节点") {}
        EntryField("回填字段", "自动匹配") {}
        SettingChoice("覆盖已有值", "否")
    }
}

@Composable
private fun VariableCheckPage(onDismiss: () -> Unit) {
    EditorColumnPage("变量检查", onDismiss, "关闭", onDismiss) {
        Text("检查未声明、未赋值和类型不匹配的变量", color = EntryMuted, fontSize = 10.sp)
        Text("当前未发现变量问题", color = EntryAccent, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 30.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun RuntimeVariablesPage(onDismiss: () -> Unit) {
    EditorColumnPage("变量信息", onDismiss, "关闭", onDismiss) {
        Text("运行后在这里显示变量名、类型和当前值", color = EntryMuted, fontSize = 10.sp)
        Text("暂无运行变量", color = EntryAccent, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 30.dp), textAlign = TextAlign.Center)
    }
}

private const val LEGACY_ENTRY_COMMAND_PREFIX = "--@autoscript-editor:"

@Composable
private fun ToolSettingsPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    var captureDelay by remember { mutableStateOf("0秒") }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("工具", color = EntryBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 14.dp, vertical = 11.dp))
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 15.dp)) {
            ToolPanel("截屏工具") {
                ToolActionRow(R.drawable.editor_capture_camera_24, "截屏延迟", trailing = captureDelay) {
                    picker = PickerRequest("截屏延迟", listOf("0秒", "1秒", "2秒", "3秒", "5秒"), captureDelay) { captureDelay = it }
                }
            }
            Spacer(Modifier.height(4.dp))
            ToolPanel("识别工具") {
                ToolActionRow(R.drawable.editor_recognition_preview_24, "测试识别") {
                    picker = PickerRequest("测试识别", listOf("找图", "找色", "字库找字", "ONNX OCR")) {}
                }
            }
            Spacer(Modifier.height(4.dp))
            ToolPanel("图像工具") {
                ToolActionRow(R.drawable.editor_recognition_preview_24, "标注截屏") {
                    picker = PickerRequest("标注截屏", listOf("立即截屏", "延迟截屏", "导入图片")) {}
                }
                DividerLine()
                ToolActionRow(R.drawable.editor_annotation_24, "打开标注库") {
                    picker = PickerRequest("标注库", listOf("图片标注", "OCR 标注", "目标检测标注")) {}
                }
                DividerLine()
                ToolActionRow(R.drawable.editor_image_processing_24, "图像处理") {
                    picker = PickerRequest("图像处理", listOf("裁剪", "缩放", "灰度", "二值化", "颜色过滤")) {}
                }
            }
        }
        FooterButtons(listOf("移除悬浮球" to onDismiss, "屏幕截图" to { onInsert("Capture.open()\n"); onDismiss() }), fontSize = 13)
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

@Composable
private fun ToolPanel(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp))) {
        Text(title, color = EntryAccent, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, bottom = 8.dp))
        content()
    }
}

@Composable
private fun ToolActionRow(icon: Int, label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(EntryAccent, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(17.dp))
        }
        Text(label, fontSize = 13.sp, modifier = Modifier.padding(start = 9.dp).weight(1f))
        if (trailing != null) {
            DenseSpinner(trailing, Modifier.width(118.dp), arrow = false, onClick = onClick)
        } else {
            Text("›", color = EntryMuted, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun ImageRecognitionPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val pages = listOf("区域找图", "多点找色", "字库识字", "ONNX OCR")
    var page by remember { mutableStateOf(1) }
    var recognitionFrequency by remember { mutableStateOf("识别频率[1]") }
    var template by remember { mutableStateOf("未选择") }
    var imageMode by remember { mutableStateOf("普通找图") }
    var colorMode by remember { mutableStateOf("多点找色") }
    var filterMode by remember { mutableStateOf("未加载") }
    var onnxModel by remember { mutableStateOf("RapidOCR.onnx") }
    var ocrLanguage by remember { mutableStateOf("中英文") }
    var similarity by remember { mutableStateOf("0.8") }
    var direction by remember { mutableStateOf("左上到右下") }
    var successAction by remember { mutableStateOf("不执行") }
    var returnType by remember { mutableStateOf("无") }
    var range by remember { mutableStateOf("全屏  0,0,-1,-1") }
    var colorData by remember { mutableStateOf("请选择多点数据") }
    var glyphLibrary by remember { mutableStateOf("未选择") }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DenseSpinner(
                value = recognitionFrequency,
                modifier = Modifier.weight(1f),
                arrow = false,
                onClick = {
                    picker = PickerRequest(
                        "识别频率",
                        (1..12).map { "循环${it}次，识别1次" },
                        "循环${recognitionFrequency.substringAfter('[').substringBefore(']')}次，识别1次",
                    ) { selected ->
                        recognitionFrequency = "识别频率[${selected.substringAfter("循环").substringBefore("次")}]"
                    }
                },
                centered = true,
            )
            Text("图像识别", color = EntryBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(.72f))
            DenseSpinner(
                value = pages[page],
                modifier = Modifier.weight(1f),
                onClick = {
                picker = PickerRequest("识别页面", pages, pages[page]) { page = pages.indexOf(it).coerceAtLeast(0) }
                },
            )
        }
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 6.dp, top = 2.dp, end = 6.dp, bottom = 4.dp)) {
            when (page) {
                0 -> {
                    DenseImageSourceBlock(
                        firstLabel = "模版选择:", firstValue = template, secondLabel = "识别方式:", secondValue = imageMode, preview = "预览",
                        onFirst = { picker = PickerRequest("选择模板图片", listOf("template.png", "button_start.png", "target.png", "从截屏中创建"), template) { template = it } },
                        onSecond = { picker = PickerRequest("识别方式", listOf("普通找图", "透明图", "灰度图", "特征匹配"), imageMode) { imageMode = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量", "跟随上次结果"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseSuccessRow(successAction, returnType,
                        onAction = { picker = PickerRequest("识别成功", successActions, successAction) { successAction = it } },
                        onReturnType = { picker = PickerRequest("返回值", imageReturnTypes, returnType) { returnType = it } },
                    )
                    if (successAction == "找到后点击") DenseSingleVariableRow("随机偏移:", "5")
                    when (returnType) {
                        "图像" -> DenseSingleVariableRow("图像变量:", "选择图像变量")
                        "布尔值" -> DenseSingleVariableRow("结果变量:", "选择布尔变量")
                        else -> DenseVariableRow("坐标变量:", "选择X变量", "选择Y变量")
                    }
                }
                1 -> {
                    DenseImageSourceBlock(
                        firstLabel = "多点数据:", firstValue = colorData, secondLabel = "识别方式:", secondValue = colorMode, preview = "预览",
                        onFirst = { picker = PickerRequest("颜色数据", listOf("请选择多点数据", "从屏幕取色", "编辑多点颜色", "从文件导入"), colorData) { colorData = it } },
                        onSecond = { picker = PickerRequest("识别方式", listOf("单点找色", "多点找色", "多点比色", "区域颜色统计"), colorMode) { colorMode = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseSuccessRow(successAction, returnType,
                        onAction = { picker = PickerRequest("识别成功", successActions, successAction) { successAction = it } },
                        onReturnType = { picker = PickerRequest("返回值", imageReturnTypes, returnType) { returnType = it } },
                    )
                    if (successAction == "找到后点击") DenseSingleVariableRow("随机偏移:", "5")
                    when (returnType) {
                        "图像" -> DenseSingleVariableRow("图像变量:", "选择图像变量")
                        "布尔值" -> DenseSingleVariableRow("结果变量:", "选择布尔变量")
                        else -> DenseVariableRow("坐标变量:", "选择X变量", "选择Y变量")
                    }
                }
                2 -> {
                    DenseImageSourceBlock(
                        firstLabel = "字库选择:", firstValue = if (glyphLibrary == "未选择") "请选择字库" else glyphLibrary,
                        secondLabel = "滤色数组:", secondValue = filterMode, preview = "首字",
                        onFirst = { picker = PickerRequest("选择字库", listOf("default.txt", "数字字库.txt", "新建字库", "导入字库"), glyphLibrary) { glyphLibrary = it } },
                        onSecond = { picker = PickerRequest("滤色数组", listOf("未加载", "不处理", "二值化", "指定颜色", "灰度", "反色"), filterMode) { filterMode = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseTextReturnRow()
                }
                else -> {
                    DenseImageSourceBlock(
                        firstLabel = "模型选择:", firstValue = onnxModel,
                        secondLabel = "识别语言:", secondValue = ocrLanguage, preview = "OCR",
                        onFirst = { picker = PickerRequest("ONNX 模型", listOf("RapidOCR.onnx", "PP-OCRv4-mobile", "导入模型"), onnxModel) { onnxModel = it } },
                        onSecond = { picker = PickerRequest("识别语言", listOf("中英文", "中文", "英文", "数字"), ocrLanguage) { ocrLanguage = it } },
                    )
                    DenseRangeRow(range) { picker = PickerRequest("识别范围", listOf("全屏  0,0,-1,-1", "手动选择区域", "使用范围变量"), range) { range = it } }
                    DenseDirectionRow(direction, similarity,
                        onDirection = { picker = PickerRequest("查找方向", imageDirections, direction) { direction = it } },
                        onSimilarity = { picker = PickerRequest("相似度", similarities, similarity) { similarity = it } },
                    )
                    DenseTextReturnRow()
                }
            }
        }
        FooterButtons(listOf("取消" to onDismiss, "图像对比" to {}, "加入" to {
            val code = when (page) {
                0 -> "Vision.findImage(\"${legacyLuaEscape(template.takeUnless { it == "未选择" } ?: "template.png")}\", ${similarity.toDoubleOrNull() ?: 0.90})\n"
                1 -> "Vision.findColor(\"${legacyLuaEscape(colorData.substringBefore('-'))}\")\n"
                2 -> "Vision.findText(\"${legacyLuaEscape(glyphLibrary.takeUnless { it == "未选择" } ?: "default.txt")}\", ${similarity.toDoubleOrNull() ?: 0.90})\n"
                else -> "Vision.findTextOnnx(\"${legacyLuaEscape(onnxModel)}\", \"${legacyLuaEscape(ocrLanguage)}\", ${similarity.toDoubleOrNull() ?: 0.90})\n"
            }
            onInsert(code); onDismiss()
        }), fontSize = 10)
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

private val imageDirections = listOf("左上到右下", "右上到左下", "左下到右上", "右下到左上", "中心向四周")
private val similarities = listOf("1.0", "0.98", "0.95", "0.9", "0.85", "0.8")
private val successActions = listOf("不执行", "找到后点击", "找到后停止脚本", "找到后继续")
private val imageReturnTypes = listOf("无", "坐标", "图像", "布尔值")

@Composable
private fun DenseImageSourceBlock(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String,
    preview: String,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            DenseLabeledRow(firstLabel, firstValue, onFirst)
            Spacer(Modifier.height(6.dp))
            DenseLabeledRow(secondLabel, secondValue, onSecond, arrow = true)
        }
        Box(
            Modifier.padding(start = 8.dp).size(70.dp)
                .background(Color(0xFFF4F6F9), RoundedCornerShape(2.dp))
                .border(1.dp, EntryBorder, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(preview, fontSize = 10.sp, color = EntryMuted)
        }
    }
}

@Composable
private fun DenseLabeledRow(label: String, value: String, onClick: () -> Unit, arrow: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(value, Modifier.weight(1f), arrow, onClick)
    }
}

@Composable
private fun DenseRangeRow(range: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("选取范围:")
        val values = if (range.startsWith("全屏")) listOf("x:0", "y:0", "w:0", "h:0") else listOf("x:--", "y:--", "w:--", "h:--")
        values.forEachIndexed { index, value ->
            DenseSpinner(
                value,
                Modifier.weight(1f).padding(start = if (index == 0) 0.dp else 2.dp, end = if (index == values.lastIndex) 0.dp else 2.dp),
                arrow = false,
                onClick = onClick,
                centered = true,
            )
        }
    }
}

@Composable
private fun DenseDirectionRow(
    direction: String,
    similarity: String,
    onDirection: () -> Unit,
    onSimilarity: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("查找方向:")
        DenseSpinner(direction, Modifier.weight(1f), arrow = true, onClick = onDirection)
        DenseLabel("相似度:", Modifier.padding(start = 8.dp))
        DenseSpinner(similarity, Modifier.weight(.55f), arrow = true, onClick = onSimilarity, centered = true)
    }
}

@Composable
private fun DenseSuccessRow(action: String, returnType: String, onAction: () -> Unit, onReturnType: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("识别成功:")
        DenseSpinner(action, Modifier.weight(1.3f), arrow = true, onClick = onAction)
        DenseLabel("返回值:", Modifier.padding(start = 8.dp))
        DenseSpinner(returnType, Modifier.weight(1f), arrow = true, onClick = onReturnType)
    }
}

@Composable
private fun DenseSingleVariableRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(value, Modifier.weight(1f), arrow = false, onClick = {})
    }
}

@Composable
private fun DenseTextReturnRow() {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel("字符变量:")
        DenseSpinner("选择字符变量", Modifier.weight(1.2f), arrow = false, onClick = {})
        DenseLabel("返回值:", Modifier.padding(start = 8.dp))
        DenseSpinner("文本", Modifier.weight(1f), arrow = true, onClick = {})
    }
}

@Composable
private fun DenseVariableRow(label: String, first: String, second: String) {
    Row(Modifier.fillMaxWidth().height(38.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DenseLabel(label)
        DenseSpinner(first, Modifier.weight(1f), onClick = {})
        DenseSpinner(second, Modifier.weight(1f).padding(start = 6.dp), onClick = {})
    }
}

@Composable
private fun DenseLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFF20242C), fontSize = 10.sp, modifier = modifier.padding(end = 5.dp))
}

@Composable
private fun DenseSpinner(
    value: String,
    modifier: Modifier = Modifier,
    arrow: Boolean = true,
    onClick: () -> Unit,
    centered: Boolean = false,
) {
    Box(
        modifier = modifier.height(30.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
            .border(1.dp, EntryBorder, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = if (arrow) "$value  ▾" else value,
            color = Color(0xFF20242C),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
private fun JudgmentPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        listOf(
            "如果" to "if value == true then\n    \nend\n",
            "否则如果" to "-- 否则如果\n",
            "否则" to "-- 否则\n",
        ).forEach { (label, snippet) ->
            Text(
                label,
                color = Color(0xFF171B25),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().weight(1f).clickable {
                    onInsert(snippet)
                    onDismiss()
                }.padding(horizontal = 14.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun LoopPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    var variableMode by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf("3") }
    var time by remember { mutableStateOf("3") }
    var timeUnit by remember { mutableStateOf("秒") }
    var picker by remember { mutableStateOf<PickerRequest?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("循环类型", color = EntryBlue, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(40.dp).padding(top = 9.dp))
        DividerLine()
        Box(Modifier.padding(top = 10.dp)) {
            LoopValueModeChoice(variableMode) { variableMode = it }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)) {
            DenseLoopRow("无限循环", mode == 0, { mode = 0 }) { Spacer(Modifier.fillMaxWidth()) }
            DenseLoopRow("限次循环", mode == 1, { mode = 1 }) {
                if (variableMode) DenseVariableSelector("整", "未选择")
                else DenseLoopValue(count, { count = it }, "$count(次)") {}
            }
            DenseLoopRow("限时循环", mode == 2, { mode = 2 }) {
                if (variableMode) DenseVariableSelector("浮", "未选择")
                else DenseLoopValue(time, { time = it }, "$time($timeUnit)") {
                    picker = PickerRequest("时间单位", listOf("毫秒", "秒", "分钟"), timeUnit) { timeUnit = it }
                }
            }
            DenseLoopRow("获取循环次数", mode == 3, { mode = 3 }) { DenseVariableSelector("整", "未选择") }
            DenseLoopRow("获取循环时间", mode == 4, { mode = 4 }) { DenseVariableSelector("浮", "未选择") }
        }
        FooterButtons(listOf("取消" to onDismiss, "加入" to {
            val snippet = when (mode) {
                0 -> "while value == true do\n    \nend\n-- maxIterations=10000\n"
                1 -> "for i = 1, ${count.toLongOrNull()?.coerceAtLeast(1) ?: 1} do\n    \nend\n"
                2 -> {
                    val multiplier = when (timeUnit) { "分钟" -> 60_000L; "秒" -> 1_000L; else -> 1L }
                    val durationMs = ((time.toDoubleOrNull() ?: 0.0) * multiplier).toLong().coerceAtLeast(1)
                    "while elapsedMs < $durationMs do\n    \nend\n-- maxIterations=10000\n"
                }
                3 -> "Runtime.getLoopCount()\n"
                else -> "Runtime.getLoopElapsedMs()\n"
            }
            onInsert(snippet); onDismiss()
        }))
    }
    picker?.let { request -> PickerDialog(request) { picker = null } }
}

@Composable
private fun LoopValueModeChoice(variableMode: Boolean, onSelected: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp)
            .background(Color(0xFFF3F5F8), RoundedCornerShape(6.dp)).padding(3.dp),
    ) {
        listOf(false to "固定值", true to "变量").forEach { (value, label) ->
            Text(
                label,
                color = if (variableMode == value) EntryBlue else EntryMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(if (variableMode == value) Color.White else Color.Transparent, RoundedCornerShape(5.dp))
                    .clickable { onSelected(value) }.padding(top = 4.dp),
            )
        }
    }
}

private fun legacyLuaEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

@Composable
private fun DenseLoopRow(label: String, selected: Boolean, onSelect: () -> Unit, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.size(30.dp))
            Text(label, fontSize = 13.sp, color = Color(0xFF20242C), maxLines = 1)
        }
        Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) { value() }
    }
}

@Composable
private fun DenseLoopValue(value: String, onValue: (String) -> Unit, unit: String, onUnit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF20242C), textAlign = TextAlign.Center),
            modifier = Modifier.weight(1f).height(30.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
                .border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(vertical = 7.dp),
        )
        DenseSpinner(unit, Modifier.padding(start = 6.dp).weight(1.15f), arrow = true, onClick = onUnit, centered = true)
    }
}

@Composable
private fun DenseVariableSelector(type: String, name: String) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp))
            .border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type, color = EntryAccent, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(.28f))
        DividerVertical()
        Text(name, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        DividerVertical()
        Text("选择变量", fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(.72f).fillMaxHeight().clickable { }.padding(top = 8.dp))
    }
}

@Composable
private fun CommonPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val tabs = listOf("流程控制", "设置调用参数", "获取调用参数", "设置返回参数", "获取返回参数")
    var selected by remember { mutableStateOf(0) }
    var action by remember { mutableStateOf(0) }
    var waitValue by remember { mutableStateOf("500") }
    SplitEntryPage("常用功能", tabs, selected, { selected = it }, onDismiss, {
        val snippet = when (selected) {
            1 -> "Runtime.setParameter(1, value)\n"
            2 -> "local value = Runtime.getParameter(1)\n"
            3 -> "Runtime.setReturnParameter(1, value)\n"
            4 -> "local value = Runtime.getReturnParameter(1)\n"
            else -> when (action) { 0 -> "break\n"; 1 -> "::label::\n"; 2 -> "return\n"; 3 -> "goto label\n"; else -> "Task.sleep($waitValue)\n" }
        }
        onInsert(snippet); onDismiss()
    }) {
        when (selected) {
            0 -> {
                DenseFlowActions(action) { action = it }
                if (action == 4) {
                    SegmentedChoice("固定值", "变量", false) {}
                    DenseTypedValue("毫秒", waitValue, { waitValue = it }, "500毫秒")
                } else if (action == 1) {
                    SegmentedChoice("自动标记", "自定义", false) {}
                    DenseTypedValue("名", "", {}, "")
                } else if (action == 3) {
                    DenseNotice("当前文件没有标记")
                } else {
                    DenseNotice(if (action == 0) "只能在循环体内使用。" else "结束当前调用并返回上层。", title = "使用说明")
                }
            }
            1 -> {
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置调用参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("+ 添加参数", color = EntryAccent, fontSize = 10.sp)
                }
                DenseTypedValue("序", "1", {}, "选择变量")
                DenseTypedValue("值", "未选择", {}, "选择")
            }
            2 -> {
                Text("获取调用参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseTypedValue("序", "1", {}, "变量")
                DenseTypedValue("接", "未选择", {}, "选择")
            }
            3 -> {
                Text("设置返回参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseTypedValue("序", "1", {}, "选择变量")
                DenseTypedValue("值", "未选择", {}, "选择")
            }
            else -> {
                Text("获取返回参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseTypedValue("序", "1", {}, "变量")
                DenseTypedValue("接", "未选择", {}, "选择")
            }
        }
    }
}

@Composable
private fun DenseFlowActions(selected: Int, onSelected: (Int) -> Unit) {
    val actions = listOf("跳出循环", "放置标记", "返回上层", "跳转标记", "等待时间")
    Column(Modifier.fillMaxWidth().border(1.dp, EntryBorder, RoundedCornerShape(3.dp))) {
        actions.chunked(2).forEachIndexed { rowIndex, rowItems ->
            if (rowIndex > 0) DividerLine()
            Row(Modifier.fillMaxWidth().height(35.dp)) {
                rowItems.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * 2 + columnIndex
                    if (columnIndex > 0) DividerVertical()
                    Row(Modifier.weight(1f).fillMaxHeight().clickable { onSelected(index) }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(index == selected, { onSelected(index) }, modifier = Modifier.size(26.dp))
                        Text(label, fontSize = 10.sp)
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DenseTypedValue(type: String, value: String, onValue: (String) -> Unit, action: String) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(top = 6.dp)
            .background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type, color = EntryAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
        DividerVertical()
        BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF20242C)), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (action.isNotEmpty()) {
            DividerVertical()
            Text(action, fontSize = 9.sp, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp).fillMaxHeight().clickable { }.padding(top = 8.dp))
        }
    }
}

@Composable
private fun DenseNotice(text: String, title: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(Color(0xFFFAFBFC), RoundedCornerShape(3.dp)).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 7.dp)) {
        title?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        Text(text, color = EntryMuted, fontSize = 10.sp, modifier = Modifier.padding(top = if (title == null) 0.dp else 4.dp))
    }
}

@Composable
private fun DenseDebugSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChecked, modifier = Modifier.size(45.dp, 26.dp))
    }
}

@Composable
private fun DenseRuntimeMode(label: String, options: List<String>, selected: Int) {
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Row(Modifier.weight(1f).height(28.dp).background(Color(0xFFF5F7FA), RoundedCornerShape(6.dp))) {
            options.forEachIndexed { index, option ->
                Text(
                    option,
                    color = if (index == selected) Color.White else EntryMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .background(if (index == selected) EntryAccent else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(top = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun DebugPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val tabs = listOf("运行输出", "调试设置", "开发环境")
    var selected by remember { mutableStateOf(0) }
    var outputConsole by remember { mutableStateOf(false) }
    var outputText by remember { mutableStateOf("") }
    var debugRun by remember { mutableStateOf(true) }
    var hideEditor by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf(false) }
    var crosshair by remember { mutableStateOf(false) }
    var performance by remember { mutableStateOf(false) }
    var volumeStop by remember { mutableStateOf(false) }
    var returnEntry by remember { mutableStateOf(true) }
    SplitEntryPage("调试功能", tabs, selected, { selected = it }, onDismiss, {
        onInsert(if (selected == 0) "Log.info(\"${legacyLuaEscape(outputText)}\")\n" else "Debug.checkpoint()\n"); onDismiss()
    }, headerHint = "调试/运行延迟，控制台日志开启会降低运行速度，仅开发时生效！") {
        when (selected) {
            0 -> {
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF3F5F8), RoundedCornerShape(6.dp)).padding(3.dp)) {
                        listOf(false to "运行提示", true to "控制台日志").forEach { (value, label) ->
                            Text(label, color = if (outputConsole == value) EntryBlue else EntryMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).fillMaxHeight().background(if (outputConsole == value) Color.White else Color.Transparent, RoundedCornerShape(5.dp)).clickable { outputConsole = value }.padding(top = 3.dp))
                        }
                    }
                    Text("选择变量", color = EntryBlue, fontSize = 10.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 8.dp).width(95.dp).fillMaxHeight().border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).clickable { }.padding(top = 7.dp))
                }
                BasicTextField(
                    value = outputText,
                    onValueChange = { outputText = it },
                    textStyle = TextStyle(fontSize = 11.sp, color = Color(0xFF202839)),
                    modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 7.dp).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(8.dp),
                    decorationBox = { field -> Box { if (outputText.isEmpty()) Text("请输入运行提示内容", color = Color(0xFFB3BAC7), fontSize = 11.sp); field() } },
                )
            }
            1 -> {
                DenseDebugSwitch("调试运行", debugRun) { debugRun = it }
                DenseDebugSwitch("运行时隐藏编程窗口", hideEditor) { hideEditor = it }
                DenseDebugSwitch("控制台日志", console) { console = it }
                DenseDebugSwitch("显示按键准星", crosshair) { crosshair = it }
                DenseDebugSwitch("性能信息", performance) { performance = it }
                DenseDebugSwitch("无障碍音量停止", volumeStop) { volumeStop = it }
                DenseDebugSwitch("停止后返回入口", returnEntry) { returnEntry = it }
                SettingChoice("运行延迟", "0毫秒")
            }
            else -> {
                Text("开发环境", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(24.dp))
                DenseRuntimeMode("截图服务", listOf("系统录屏", "Root"), 0)
                DenseRuntimeMode("按键服务", listOf("无障碍", "Root"), 0)
                DenseRuntimeMode("截屏显示", listOf("自动", "悬浮窗", "应用内"), 0)
                DenseDebugSwitch("始终只显示一个弹窗", true) {}
            }
        }
    }
}

@Composable
private fun AiProgrammingPage(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    var requirement by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var sidePanel by remember { mutableStateOf<String?>(null) }
    var attachments by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 编程助手", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("服务 · 模型", fontSize = 8.sp, color = EntryMuted)
            }
            HeaderAction("会话") { sidePanel = "会话" }
            HeaderAction("配置") { sidePanel = "配置" }
            HeaderAction("×", onDismiss)
        }
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("任务要求", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SmallOutlineAction("路径") { attachments++ }
                SmallOutlineAction("附件 $attachments/999") { attachments++ }
                SmallOutlineAction("截屏") { attachments++ }
            }
            MultilineField(requirement, "描述目标、问题和期望结果", 96) { requirement = it }
            Text("可选：添加文件或截图", color = EntryMuted, fontSize = 9.sp, modifier = Modifier.padding(vertical = 7.dp))
            Text("等待输入", color = EntryMuted, fontSize = 10.sp, modifier = Modifier.height(30.dp))
            Text("结果 · 可视化节点", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.height(26.dp))
            MultilineField(result, "", 180) { result = it }
        }
        FooterButtons(listOf("复制" to {}, "插入" to { if (result.isNotBlank()) onInsert(result) }, "发送" to {
            result = if (requirement.isBlank()) "请先描述任务要求" else "-- AI 服务尚未配置\n-- $requirement"
        }))
    }
    sidePanel?.let { AiSidePanelDialog(it) { sidePanel = null } }
}

@Composable
private fun EditorColumnPage(title: String, onDismiss: () -> Unit, primary: String, onPrimary: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 14.dp, vertical = 11.dp))
        DividerLine()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        FooterButtons(listOf("取消" to onDismiss, primary to onPrimary))
    }
}

@Composable
private fun SplitEntryPage(title: String, tabs: List<String>, selected: Int, onSelected: (Int) -> Unit, onDismiss: () -> Unit, onAdd: () -> Unit, headerHint: String? = null, showAdd: Boolean = true, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            headerHint?.let { Text(it, color = EntryMuted, fontSize = 8.sp) }
        }
        DividerLine()
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(70.dp).fillMaxHeight().background(EntryPanel)) {
                tabs.forEachIndexed { index, tab ->
                    Box(Modifier.fillMaxWidth().height(36.dp).clickable { onSelected(index) }) {
                        Box(Modifier.align(Alignment.CenterStart).width(1.5.dp).fillMaxHeight()
                            .background(if (selected == index) EntryAccent else Color.Transparent))
                        Text(
                            tab,
                            fontSize = 10.sp,
                            fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected == index) EntryAccent else Color(0xFF283140),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(top = 10.dp),
                        )
                    }
                }
            }
            DividerVertical()
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { content() }
        }
        FooterButtons(if (showAdd) listOf("取消" to onDismiss, "加入" to onAdd) else listOf("关闭" to onDismiss))
    }
}

@Composable private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).padding(bottom = 6.dp)) {
        Text(title, color = EntryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        content()
    }
}

@Composable private fun SettingChoice(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(42.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(value, color = EntryAccent, fontSize = 11.sp)
    }
}

@Composable
private fun PickerDialog(request: PickerRequest, onDismiss: () -> Unit) {
    var selected by remember(request.title, request.selected) { mutableStateOf(request.selected) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(.78f).widthIn(max = 390.dp),
            color = Color.White,
            shape = RoundedCornerShape(2.dp),
            shadowElevation = 10.dp,
        ) {
            Column {
                Text(request.title, color = EntryBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp, vertical = 10.dp))
                DividerLine()
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).height((request.options.size.coerceAtMost(6) * 40).dp)) {
                    request.options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().height(40.dp)
                                .background(if (selected == option) Color(0xFFEAF0FF) else Color.White)
                                .clickable { selected = option }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option, color = Color(0xFF202839), fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (selected == option) Text("✓", color = EntryAccent, fontSize = 15.sp)
                        }
                        DividerLine()
                    }
                }
                FooterButtons(listOf("取消" to onDismiss, "确定" to {
                    selected?.let(request.onSelected)
                    onDismiss()
                }))
            }
        }
    }
}

@Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f)); Switch(checked, onChecked, modifier = Modifier.size(38.dp, 24.dp))
    }
}

@Composable private fun EntryField(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f)); Text(value, fontSize = 11.sp, color = EntryAccent)
    }
}

@Composable private fun EntryEditField(label: String, value: String, onValue: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(38.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = EntryMuted, modifier = Modifier.weight(1f))
        BasicTextField(value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 11.sp, color = EntryAccent, textAlign = TextAlign.End), modifier = Modifier.width(100.dp))
    }
}

@Composable private fun CompactChoice(text: String, modifier: Modifier, onClick: () -> Unit) = Text(text, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = modifier.height(34.dp).border(1.dp, EntryBorder, RoundedCornerShape(2.dp)).clickable(onClick = onClick).padding(top = 9.dp))

@Composable private fun SegmentedChoice(left: String, right: String, selectedRight: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp).background(Color(0xFFEEF1F7), RoundedCornerShape(15.dp))) {
        listOf(false to left, true to right).forEach { (rightSide, label) ->
            Text(label, color = if (selectedRight == rightSide) Color.White else EntryMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).fillMaxHeight().background(if (selectedRight == rightSide) EntryAccent else Color.Transparent, RoundedCornerShape(15.dp)).clickable { onSelect(rightSide) }.padding(top = 7.dp))
        }
    }
}

@Composable private fun RadioLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, modifier = Modifier.size(30.dp)); Text(label, fontSize = 12.sp)
    }
}

@Composable private fun MultilineField(value: String, hint: String, minHeight: Int, onValue: (String) -> Unit) {
    BasicTextField(value, onValue, textStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF202839), fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().height(minHeight.dp).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).padding(8.dp), decorationBox = { field -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = EntryMuted, fontSize = 10.sp); field() } })
}

@Composable private fun HeaderAction(label: String, onClick: () -> Unit) = Text(label, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(46.dp).height(30.dp).clickable(onClick = onClick).padding(top = 8.dp))
@Composable private fun SmallOutlineAction(label: String, onClick: () -> Unit) = Text(label, color = EntryAccent, fontSize = 9.sp, modifier = Modifier.padding(start = 5.dp).border(1.dp, EntryBorder, RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 5.dp))

@Composable
private fun AiSidePanelDialog(kind: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(.82f).widthIn(max = 420.dp).height(420.dp), color = Color.White, shape = RoundedCornerShape(2.dp), shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (kind == "配置") "AI 配置" else "会话记录", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    HeaderAction("×", onDismiss)
                }
                DividerLine()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == "配置") {
                        EntryField("服务", "未配置") {}
                        EntryField("模型", "未选择") {}
                        EntryField("输出格式", "可视化节点") {}
                        EntryField("文件访问", "每次询问") {}
                        EntryField("截图权限", "每次询问") {}
                        Text("服务地址、密钥和账号信息不会写死在客户端。", color = EntryMuted, fontSize = 9.sp)
                    } else {
                        EntryField("新会话", "＋") {}
                        Text("暂无历史会话", color = EntryMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 60.dp))
                    }
                }
                FooterButtons(listOf("关闭" to onDismiss, if (kind == "配置") "保存" to onDismiss else "新建会话" to {}))
            }
        }
    }
}

@Composable private fun FooterButtons(buttons: List<Pair<String, () -> Unit>>, fontSize: Int = 12) {
    DividerLine()
    Row(Modifier.fillMaxWidth().height(40.dp)) {
        buttons.forEachIndexed { index, (label, action) ->
            if (index > 0) DividerVertical()
            Text(label, color = if (index == buttons.lastIndex) EntryAccent else Color(0xFF202839), fontSize = fontSize.sp, fontWeight = if (index == buttons.lastIndex) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = action).padding(top = 12.dp))
        }
    }
}

@Composable private fun DividerLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE2E5EA)))
@Composable private fun DividerVertical() = Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFFE2E5EA)))
