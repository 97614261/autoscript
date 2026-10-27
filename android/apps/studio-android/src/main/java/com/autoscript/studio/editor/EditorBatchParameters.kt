package com.autoscript.studio

import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.autoscript.runtime.client.VisualCompileResult

internal data class EditorBatchField(val path: String, val label: String, val values: List<String>,
    val editor: BlockPropertyEditor = BlockPropertyEditor.INTEGER, val choices: List<String> = emptyList())
internal data class EditorBatchParameters(val context: EditorPasteRequest, val root: String, val kind: String,
    val title: String, val rows: List<Int>, val fields: List<EditorBatchField>)
internal data class EditorBatchCandidate(val source: String, val changed: Int)

/** Only explicit literal fields and conservative options; never identities or output variables. */
private val batchNumericFields = setOf("milliseconds", "durationMs", "similarityPermille", "tolerance",
    "timeoutMs", "times", "maxIterations", "periodMs")
private val batchBooleanFields = setOf("autoCapture", "exactTiming", "allowPartial", "blockOutside")
private val batchEnumFields = setOf("direction", "level")

private fun hasNoBatchBinding(args: com.google.gson.JsonObject, path: String): Boolean {
    val binding = args.get("${path}Variable") ?: return true
    return binding.isJsonNull || (binding.isJsonPrimitive && binding.asJsonPrimitive.isString && binding.asString.isBlank())
}

internal fun editorBatchParameters(projectId: String, flowId: String, root: String, editor: VisualEditorState): EditorBatchParameters? {
    if (editor.isReadOnly || editor.selectedNodeIds.size !in 2..256) return null
    val nodes = variableCheckNodes(editor.variableCheckInput()).filter { it.row.nodeId in editor.selectedNodeIds }
    if (nodes.size != editor.selectedNodeIds.size || nodes.map { it.row.kind }.distinct().size != 1) return null
    val kind = nodes.first().row.kind
    val contract = BlockCatalog.find(kind) ?: return null
    val fields = contract.properties.filter { field -> field.dependsOn == null && when (field.editor) {
        BlockPropertyEditor.INTEGER -> field.path in batchNumericFields
        BlockPropertyEditor.BOOLEAN -> field.path in batchBooleanFields || (field.path == "enabled" && kind in setOf("task.logenabled", "task.promptenabled"))
        BlockPropertyEditor.ENUM, BlockPropertyEditor.INTEGER_ENUM -> field.path in batchEnumFields && field.choices.isNotEmpty()
        else -> false
    } }
        .filter { field -> nodes.all { node ->
            val args = node.args
            val value = args?.get(field.path)
            args != null && hasNoBatchBinding(args, field.path) && if (value == null)
                field.defaultValue != null || (field.path == "exactTiming" && field.editor == BlockPropertyEditor.BOOLEAN)
            else value.isJsonPrimitive && when (field.editor) {
                BlockPropertyEditor.INTEGER -> value.asJsonPrimitive.isNumber
                BlockPropertyEditor.BOOLEAN -> value.asJsonPrimitive.isBoolean
                BlockPropertyEditor.INTEGER_ENUM -> value.asJsonPrimitive.isNumber && value.asString in field.choices
                BlockPropertyEditor.ENUM -> value.asJsonPrimitive.isString && value.asString in field.choices
                else -> false
            }
        } }.map { field -> EditorBatchField(field.path, field.label,
            nodes.map { it.args?.get(field.path)?.asString ?: field.defaultValue ?: "未设置" }, field.editor,
            if (field.editor == BlockPropertyEditor.BOOLEAN) listOf("true", "false") else field.choices) }
    if (fields.isEmpty()) return null
    return EditorBatchParameters(editorInsertionContext(projectId, flowId, editor), root, kind,
        contract.title, nodes.map { it.row.sourceLine }, fields)
}

internal fun editorBatchValue(path: String, input: String): BigDecimal {
    require(input.length in 1..32) { "请输入完整的整数" }
    val value = input.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("请输入整数，不填单位或变量名")
    require(value.stripTrailingZeros().scale() <= 0 && value >= BigDecimal.ZERO && value <= BigDecimal(Int.MAX_VALUE)) { "请输入0到2147483647之间的整数" }
    if (path == "similarityPermille") require(value <= BigDecimal(1000)) { "相似度范围是0到1000（900代表90%）" }
    if (path == "tolerance") require(value <= BigDecimal(255)) { "颜色容差范围是0到255" }
    return value
}

internal fun editorBatchCandidate(request: EditorBatchParameters, path: String, input: String): EditorBatchCandidate {
    return editorBatchCandidate(request, mapOf(path to input))
}

internal fun editorBatchCandidate(request: EditorBatchParameters, inputs: Map<String, String>, checkCancelled: () -> Unit = {}): EditorBatchCandidate {
    require(inputs.isNotEmpty()) { "请勾选至少一个要修改的参数" }
    val values = inputs.mapValues { (path, input) ->
        val field = request.fields.singleOrNull { it.path == path } ?: throw IllegalArgumentException("这个参数不能批量修改")
        when (field.editor) {
            BlockPropertyEditor.INTEGER -> JsonPrimitive(editorBatchValue(path, input).toBigIntegerExact())
            BlockPropertyEditor.BOOLEAN -> JsonPrimitive(input.toBooleanStrictOrNull() ?: throw IllegalArgumentException("请选择开启或关闭"))
            BlockPropertyEditor.INTEGER_ENUM -> {
                require(input in field.choices) { "请选择已有选项" }; JsonPrimitive(input.toInt())
            }
            BlockPropertyEditor.ENUM -> { require(input in field.choices) { "请选择已有选项" }; JsonPrimitive(input) }
            else -> throw IllegalArgumentException("这个参数不支持批改")
        }
    }
    var count = 0
    val visited = mutableSetOf<String>()
    val source = request.context.source.lineSequence().map { line ->
        checkCancelled()
        if (line.isBlank()) line else {
            val node = JsonParser.parseString(line).asJsonObject
            val id = node["nodeId"].asString
            if (id !in request.context.selection) line else {
                require(visited.add(id) && node["kind"].asString == request.kind) { "选中行的类型或身份已变化" }
                val args = node.getAsJsonObject("args")
                var changed = false
                values.forEach { (path, value) ->
                    require(hasNoBatchBinding(args, path)) { "参数绑定了变量或绑定格式无效，不能覆盖" }
                    if (args[path] != value) { args.add(path, value); changed = true }
                }
                if (!changed) line else { count++; node.toString() }
            }
        }
    }.joinToString("\n")
    require(visited == request.context.selection && count > 0) { "没有需要改变的选中行" }
    require(source.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "修改后插件超过1MiB，未应用" }
    return EditorBatchCandidate(source, count)
}

internal suspend fun applyEditorBatch(request: EditorBatchParameters, candidate: EditorBatchCandidate,
    editor: VisualEditorState, canApply: () -> Boolean, validate: suspend () -> VisualCompileResult): String? {
    fun current() = request.context.matches(request.context.projectId, request.context.flowId, editor) && canApply()
    currentCoroutineContext().ensureActive()
    if (!current()) return "项目、草稿或选择已变化，请重新打开批量参数"
    when (val result = validate()) {
        is VisualCompileResult.Invalid -> return "整项目校验未通过，未修改任何行：${result.diagnostic.message}"
        is VisualCompileResult.Unavailable -> return "校验不可用，未修改任何行：${result.message}"
        is VisualCompileResult.Success -> Unit
    }
    currentCoroutineContext().ensureActive()
    if (!current()) return "校验期间项目、选择或运行状态已变化，未修改任何行"
    return editor.replaceRawSource(request.context.source, candidate.source)
}
