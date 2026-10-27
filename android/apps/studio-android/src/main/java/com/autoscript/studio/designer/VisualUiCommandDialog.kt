package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiControl
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject
private val uiPositionOperations=setOf("windowPosition","bubblePosition","get:windowPosition","get:bubblePosition")
private val uiWindowOperations=setOf("show","hide","minimize","page","get:config","get:pages","get:controls","restoreConfig")+uiPositionOperations

internal fun uiCommandTargetError(model: ScriptUiDefinition?, kind: String, id: String, operation: String): String? {
    if (kind == "ui.command" && operation in uiWindowOperations)
        return if (id == "window") null else "窗口操作请选择整个窗口"
    val field = model?.fields?.singleOrNull { it.id == id } ?: return "请选择已保存界面的控件，原控件可能已删除"
    val property = operation.removePrefix("get:")
    if(property in setOf("groupVisible","groupEnabled","groupReset","groupCount")) return if(field.control==UiControl.CONTAINER) null else "组操作请选择容器"
    if(property=="backgroundImage") return if(field.ui!=null) null else "背景图片需要已保存的设计控件"
    if(property=="hint") return if(field.control in setOf(UiControl.INPUT,UiControl.PASSWORD,UiControl.TEXTAREA,UiControl.NUMBER,UiControl.DECIMAL)) null else "占位提示仅用于输入控件"
    if(property in setOf("minimum","maximum","range")) return if(field.kind in setOf("integer","number")) null else "范围仅用于数值控件"
    if(property=="step") return if(field.control==UiControl.SLIDER) null else "步长仅用于整数滑块"
    if(property in com.autoscript.script.ui.ScriptUiListCommands.writes || property in com.autoscript.script.ui.ScriptUiListCommands.reads) {
        if(field.kind !in setOf("choice","multiChoice")) return "此控件没有选择项"
        if(property=="selectedIndex" && field.kind!="choice") return "单选索引仅用于单选控件"
        if(property=="selectedIndices" && field.kind!="multiChoice") return "多选索引仅用于多选控件"
        return null
    }
    if (property == "items" && field.kind !in setOf("choice", "multiChoice")) return "此控件没有选择项"
    if (property == "progress" && field.control != UiControl.PROGRESS) return "请选择进度条控件"
    if (kind == "ui.command" && property !in setOf("value", "text", "items", "progress", "visible", "enabled") && field.ui == null)
        return "旧表单没有布局样式属性，请先在设计器保存"
    return null
}

internal fun uiApplicableOperations(model: ScriptUiDefinition?, id: String, operations: List<String>): List<String> =
    operations.filter { it in uiWindowOperations || id.isBlank() || id == "window" || uiCommandTargetError(model, "ui.command", id, it) == null }

internal fun uiDesignValue(model: ScriptUiDefinition?, id: String, operation: String): String? {
    if(operation in setOf("windowPosition","bubblePosition")) return "{\"x\":0,\"y\":0}"
    if(operation=="groupReset" || operation=="backgroundImage") return ""
    if(operation in setOf("groupVisible","groupEnabled")) return "true"
    val f = model?.fields?.find { it.id == id } ?: return null
    val p = f.ui
    return when (operation) {
        "value", "text", "progress" -> f.initialValue
        "items" -> f.options.joinToString("\n")
        "hint" -> f.label
        "minimum" -> (f.minimum ?: if(f.control in setOf(UiControl.SLIDER,UiControl.PROGRESS)) 0 else -1_000_000_000).toString()
        "maximum" -> (f.maximum ?: if(f.control in setOf(UiControl.SLIDER,UiControl.PROGRESS)) 100 else 1_000_000_000).toString()
        "step" -> "1"
        "range" -> JsonObject().apply {
            addProperty("minimum",f.minimum ?: if(f.control in setOf(UiControl.SLIDER,UiControl.PROGRESS)) 0 else -1_000_000_000)
            addProperty("maximum",f.maximum ?: if(f.control in setOf(UiControl.SLIDER,UiControl.PROGRESS)) 100 else 1_000_000_000)
            if(f.control==UiControl.SLIDER) addProperty("step",1)
        }.toString()
        "itemAdd" -> "新增选项"
        "itemInsert","itemSet" -> "{\"index\":1,\"text\":\"新选项\"}"
        "itemRemove","selectedIndex" -> "1"
        "selectedIndices" -> "[1]"
        "itemsClear" -> ""
        "visible" -> (p?.visible ?: true).toString(); "enabled" -> (p?.enabled ?: true).toString()
        "x" -> p?.x?.toString(); "y" -> p?.y?.toString()
        "width" -> p?.width?.toString(); "height" -> p?.height?.toString()
        "fontPx" -> p?.fontPx?.toString(); "textColor" -> p?.textColor
        "background" -> p?.background; "alignment" -> p?.alignment
        "fontBold" -> p?.fontBold?.toString(); "textUnderline" -> p?.textUnderline?.toString()
        "borderColor" -> p?.borderColor; "borderWidthPx" -> p?.borderWidthPx?.toString(); "cornerPx" -> p?.cornerPx?.toString()
        else -> null
    }
}

@Composable
internal fun VisualUiCommandDialog(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, currentFlowId: String,
    variables: List<String>, onManageVariables: (() -> Unit)?, onDismiss: () -> Unit,
    onConfirm: (JsonObject) -> Unit, confirmLabel: String,
) {
    val model = remember(project.manifest.runnerUi) { project.manifest.runnerUi?.let { ScriptUiDefinition.parse(it.toString()) } }
    val initial = remember(arguments, contract.kind) { blockPropertyTexts(contract, arguments) }
    var inputs by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    fun requestClose() { if (inputs != initial) discard = true else onDismiss() }
    val command = contract.kind == "ui.command"
    val operation = if (command) inputs["operation"].orEmpty() else "value"
    val window = command && operation in uiWindowOperations
    val reading = command && operation.startsWith("get:")
    val resultVariables = calculationVariables(project.manifest.variables, currentFlowId, project.manifest.flows)
        .filter { it.type == com.autoscript.project.store.ProjectVariableType.STRING }.map { it.name }
    val choices = if (window) listOf("window" to "整个窗口") else model?.let(::uiParameterChoices).orEmpty()
    val id = inputs["controlId"].orEmpty()
    fun update(key: String, value: String) { inputs = inputs + (key to value); error = null }
    StudioPanelDialog(contract.title, ::requestClose, confirmLabel, fullScreen = fullScreen,
        onToggleFullScreen = { fullScreen = !fullScreen }, onConfirm = {
        error = uiCommandTargetError(model, contract.kind, id, operation)
        if (error == null && reading && inputs["resultVariable"] !in resultVariables) error = "请选择已声明的字符结果变量"
        if (error == null) {
            val configured = if ((window && operation !in setOf("page","restoreConfig","windowPosition","bubblePosition","get:controls")) || operation=="groupReset") inputs - "valueVariable" + ("value" to "") else inputs
            val parsed = parseBlockArguments(contract, configured, null, emptyMap(), project.manifest.resources)
            error = parsed.second
            parsed.first?.let(onConfirm)
        }
    }) {
        Column(Modifier.fillMaxWidth().then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.heightIn(max = 420.dp))
            .verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (command) {
                val operations = uiApplicableOperations(model, id, contract.properties.single { it.path == "operation" }.choices)
                DesignerChoice("操作", operation, operations.map { it to uiOperationLabel(it) }, true) { next ->
                    update("operation", next)
                    if(next.startsWith("get:")) {update("value",""); update("valueVariable","")}
                    if (next in uiWindowOperations) update("controlId", "window")
                    else if (inputs["controlId"] == "window") update("controlId", "")
                    if (!next.startsWith("get:")) uiDesignValue(model, inputs["controlId"].orEmpty(), next)?.let { update("value", it) }
                }
            }
            DesignerChoice("控件", choices.find { it.first == id }?.second ?: "请选择 · $id", choices, true) { selected ->
                update("controlId", selected)
                if (command && uiCommandTargetError(model, contract.kind, selected, operation) != null) update("operation", "text")
                if (!reading) uiDesignValue(model, selected, if (command) inputs["operation"].orEmpty() else "value")?.let { update("value", it) }
            }
            if (model == null) Text("请先设计并保存脚本界面")
            if (reading) {
                DesignerChoice("结果变量", inputs["resultVariable"].orEmpty(), resultVariables.map { it to it }, true) { update("resultVariable", it) }
                Text("返回字符值；数值和布尔可用计算积木转换。")
                if(operation=="get:config") Text("导出输入参数JSON（最多8192字节），密码不包含；接文件写入可持久保存。")
                if(operation=="get:pages") Text("只读页面ID、标题及设计尺寸，不保存布局、不读取填写值。")
                if(operation=="get:controls") {
                    DesignerChoice("查询页面",inputs["value"].orEmpty().ifEmpty {"当前页"},listOf("" to "当前页")+model?.pages.orEmpty().map {it.id to it.title},true) {update("value",it);update("valueVariable","")}
                    Text("只读控件ID、类型和是否可输入；不会切页，不包含控件值或密码。")
                }
            } else if (!window || operation in setOf("page","restoreConfig","windowPosition","bubblePosition")) {
                if(operation=="restoreConfig") Text("从文件读取到字符变量后恢复；全部校验成功才应用，不恢复密码、不触发事件。")
                val sourceVariables=if(operation in setOf("range","restoreConfig","windowPosition","bubblePosition","backgroundImage")) resultVariables else variables
                DesignerChoice("值来源", inputs["valueVariable"].orEmpty().ifEmpty { "固定值" }, listOf("" to "固定值") + sourceVariables.map { it to "变量 · $it" }, true) { update("valueVariable", it) }
                if (!inputs["valueVariable"].isNullOrBlank()) Text("执行时读取所选变量，固定值不参与本次设置。")
                else when (operation) {
                    "backgroundImage" -> {
                        val images=project.manifest.resources.filter {it.get("kind")?.asString=="image"}.map {it.get("path").asString.let {path->path to path.removePrefix("assets/images/")}}
                        DesignerChoice("背景图片",inputs["value"].orEmpty(),listOf("" to "清除背景图")+images,true) {update("value",it)}
                        Text("仅使用声明图片，不改设计稿；显示采样上限512像素。")
                    }
                    "windowPosition","bubblePosition" -> {
                        val raw=inputs["value"].orEmpty()
                        val current=runCatching {require(raw.length<=128); com.google.gson.JsonParser.parseString(raw).asJsonObject}.getOrNull()
                        listOf("x","y").forEach {key ->
                            val text=runCatching {current?.get(key)?.asString}.getOrNull() ?: "0"
                            DesignerInput("${key.uppercase()} · 屏幕像素",text,true,Modifier.fillMaxWidth(),numeric=true) {next->
                                update("value",JsonObject().apply {listOf("x","y").forEach {name->addProperty(name,(if(name==key) next else runCatching {current?.get(name)?.asString}.getOrNull()).orEmpty().toIntOrNull() ?: 0)}}.toString())
                            }
                        }
                        Text("0～32768，超出屏幕会限界；设置不自动显示窗口。界面浮球不是暂停/停止控制球。")
                    }
                    "groupReset" -> Text("先校验组内全部默认值再恢复；保留样式和选项，不触发事件。")
                    "minimum","maximum","step","hint" -> {
                        val label=when(operation) {"minimum"->"下限"; "maximum"->"上限"; "step"->"步长";else->"占位提示"}
                        DesignerInput(label,inputs["value"].orEmpty(),true,Modifier.fillMaxWidth(),numeric=operation!="hint") {update("value",it)}
                        if(operation!="hint") Text("先确保当前值在新范围内且符合步长；不会自动截断或取整。")
                    }
                    "range" -> {
                        val raw=inputs["value"].orEmpty()
                        val current=runCatching {if(raw.length>256) error("范围过长"); com.google.gson.JsonParser.parseString(raw).asJsonObject}.getOrNull()
                        val field=model?.fields?.find {it.id==id}
                        val keys=if(field?.control==UiControl.SLIDER) listOf("minimum","maximum","step") else listOf("minimum","maximum")
                        keys.forEach {key ->
                            val text=runCatching {current?.get(key)?.asString}.getOrNull().orEmpty()
                            DesignerInput(when(key) {"minimum"->"下限"; "maximum"->"上限";else->"步长"},text,true,Modifier.fillMaxWidth(),numeric=true) {next->
                                val value=JsonObject()
                                keys.forEach {name->value.addProperty(name,(if(name==key) next else runCatching {current?.get(name)?.asString}.getOrNull()).orEmpty().toLongOrNull() ?: 0)}
                                update("value",value.toString())
                            }
                        }
                        Text("上下界与步长一次应用；当前值不符合则拒绝。")
                    }
                    "itemInsert","itemSet" -> {
                        val objectValue=runCatching {com.google.gson.JsonParser.parseString(inputs["value"].orEmpty()).asJsonObject}.getOrNull()
                        fun itemValue(index: String,text: String) {
                            update("value",JsonObject().apply {addProperty("index",index.toIntOrNull() ?: 0); addProperty("text",text)}.toString())
                        }
                        DesignerInput("索引 · 1起",objectValue?.get("index")?.asString.orEmpty(),true,Modifier.fillMaxWidth(),numeric=true) {itemValue(it,objectValue?.get("text")?.asString.orEmpty())}
                        DesignerInput("选项文字",objectValue?.get("text")?.asString.orEmpty(),true,Modifier.fillMaxWidth()) {itemValue(objectValue?.get("index")?.asString ?: "1",it)}
                    }
                    "itemsClear" -> Text("只清空运行期选项，不删除设计稿；必选控件不能清空。")
                    "itemAdd","itemRemove","selectedIndex","selectedIndices" -> {
                        val label=when(operation) {"itemAdd"->"选项文字"; "selectedIndices"->"多选索引JSON · 如 [1,3]"; "selectedIndex"->"单选索引 · 0表示取消（非必选）";else->"删除索引 · 1起"}
                        DesignerInput(label,inputs["value"].orEmpty(),true,Modifier.fillMaxWidth(),numeric=operation in setOf("itemRemove","selectedIndex")) {update("value",it)}
                    }
                    "page" -> DesignerChoice("页面", inputs["value"].orEmpty(), model?.pages?.map { it.id to it.title }.orEmpty(), true) { update("value", it) }
                    "groupVisible", "groupEnabled", "visible", "enabled", "fontBold", "textUnderline" -> DesignerChoice("值", inputs["value"].orEmpty(), listOf("true" to "是", "false" to "否"), true) { update("value", it) }
                    "value", "text" -> {
                        val field = model?.fields?.find { it.id == id }
                        when (field?.kind) {
                            "multiChoice" -> {
                                val selected = runCatching { com.autoscript.script.ui.uiSelections(inputs["value"].orEmpty(), field.options) }.getOrDefault(emptyList())
                                Text("多选 · 脚本读写为JSON数组文本")
                                field.options.forEach { option -> DesignerToggle(option, option in selected, option in selected || runCatching { com.autoscript.script.ui.uiSelectionsJson(selected + option) }.isSuccess) { checked ->
                                    update("value", com.autoscript.script.ui.uiSelectionsJson(field.options.filter { if (it == option) checked else it in selected }))
                                } }
                            }
                            "boolean" -> DesignerChoice("值", inputs["value"].orEmpty(), listOf("true" to "选中", "false" to "未选中"), true) { update("value", it) }
                            "choice" -> DesignerChoice("值", inputs["value"].orEmpty(), field.options.map { it to it }, true) { update("value", it) }
                            else -> OutlinedTextField(inputs["value"].orEmpty(), { update("value", it) }, label = { Text(if (field?.kind in setOf("integer", "number")) "${if (field?.kind == "number") "小数" else "整数"} ${field?.minimum ?: -1_000_000_000}～${field?.maximum ?: 1_000_000_000}" else "固定值") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    "textColor", "background", "borderColor" -> DesignerColor("颜色", inputs["value"].orEmpty(), true) { update("value", it) }
                    "alignment" -> DesignerChoice("对齐", inputs["value"].orEmpty(), listOf("left" to "左", "center" to "居中", "right" to "右"), true) { update("value", it) }
                    else -> OutlinedTextField(inputs["value"].orEmpty(), { update("value", it) }, label = { Text(if (operation == "items") "选项 · 每行一项" else "固定值") }, singleLine = operation != "items", modifier = Modifier.fillMaxWidth())
                }
                val range = when (operation) { "x" -> "0～4096"; "y" -> "0～8192"; "width" -> "20～4096"; "height" -> "20～8192"; "fontPx" -> "8～160"; "borderWidthPx" -> "0～32"; "cornerPx" -> "0～512"; else -> null }
                range?.let { Text("允许范围：$it 设计像素") }
            }
            if (onManageVariables != null) TextButton(onClick = onManageVariables) { Text("维护变量") }
            Text("尺寸和位置为设计像素；颜色为 #AARRGGBB。运行修改不会写回设计稿。")
            error?.let { Text(it) }
        }
    }
    if (discard) StudioConfirmDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存界面操作？") },
        text = { Text("未加入的参数修改将丢弃，不改变已保存界面或执行脚本。") },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
        confirmButton = { TextButton(onClick = { discard = false; onDismiss() }) { Text("放弃并关闭") } })
}

private fun uiOperationLabel(operation: String): String {
    val extensions=mapOf("backgroundImage" to "背景图片","groupVisible" to "整组可见","groupEnabled" to "整组启用","groupReset" to "整组恢复默认值","groupCount" to "组内控件数","windowPosition" to "窗口位置","bubblePosition" to "界面浮球位置")
    extensions[operation.removePrefix("get:")]?.let {return (if(operation.startsWith("get:")) "读取 · " else "设置 · ")+it}
    val inputLabels=mapOf("hint" to "占位提示","minimum" to "数值下限","maximum" to "数值上限","step" to "滑块步长","range" to "一次设置范围","config" to "导出页面参数","pages" to "列出设计页面","controls" to "列出页面控件","restoreConfig" to "恢复页面参数")
    inputLabels[operation.removePrefix("get:")]?.let {return (if(operation.startsWith("get:")) "读取 · " else "")+it}
    val listLabels=mapOf("itemAdd" to "追加选项","itemInsert" to "插入选项","itemSet" to "修改选项","itemRemove" to "删除选项","itemsClear" to "清空选项","itemCount" to "选项数量","selectedIndex" to "单选索引","selectedIndices" to "多选索引")
    listLabels[operation.removePrefix("get:")]?.let {return (if(operation.startsWith("get:")) "读取 · " else "")+it}
    val labels = mapOf("text" to "内容", "value" to "值", "visible" to "可见", "enabled" to "启用", "items" to "选择项", "progress" to "进度", "page" to "切页", "show" to "显示窗口", "hide" to "隐藏窗口", "minimize" to "最小化", "x" to "X位置", "y" to "Y位置", "width" to "宽度", "height" to "高度", "fontPx" to "字号", "textColor" to "文字颜色", "background" to "背景颜色", "alignment" to "文字对齐", "fontBold" to "粗体", "textUnderline" to "下划线", "borderColor" to "边框颜色", "borderWidthPx" to "边框宽度", "cornerPx" to "圆角")
    val property = operation.removePrefix("get:")
    return (if (operation.startsWith("get:")) "读取 · " else "设置 · ") + (labels[property] ?: property)
}
