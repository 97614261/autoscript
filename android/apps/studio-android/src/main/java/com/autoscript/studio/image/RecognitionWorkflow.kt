package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

/** A visible forest of ordinary blocks, not an extra persisted workflow format. */
internal data class VisualBlockTree(val contract: BlockContract, val arguments: JsonObject,
    val children: Map<String, List<VisualBlockTree>> = emptyMap()) {
    fun flattened(): List<VisualBlockTree> = listOf(this) + children.values.flatten().flatMap { it.flattened() }
    fun frozen(): VisualBlockTree = copy(arguments = arguments.deepCopy(), children = children.mapValues { it.value.map(VisualBlockTree::frozen) })
}

internal enum class RecognitionWaitMode(val label: String) {
    APPEAR("等待出现"), DISAPPEAR("等待消失"), RETRY("限次重试"), ABSENT("连续未命中计时")
}
internal val recognitionWaitKinds = setOf("vision.findimage","vision.findgray","vision.findcolor","vision.findmulticolor","ocr.glyph","ocr.alphanumeric")

internal data class RecognitionWaitDraft(val mode: RecognitionWaitMode = RecognitionWaitMode.APPEAR,
    val timeout: String = "5000", val interval: String = "200", val attempts: String = "10", val absentMs: String = "1000",
    val start: String = "", val clock: String = "", val elapsed: String = "", val attempt: String = "", val outcome: String = "",
    val absentStart: String = "", val absentElapsed: String = "", val match: String = "", val sampleResult: String = "",
    val targetText: String = "", val exactText: Boolean = false,
    val glyphDetails: String = "", val glyphMatches: String = "")

internal fun recognitionWaitUsesGlyphDetails(kind: String, draft: RecognitionWaitDraft) =
    kind == "ocr.glyph" && draft.targetText.isNotEmpty() && !draft.exactText

/** Clock values and all work/output variables are explicit declarations; no hidden variable is created. */
internal fun recognitionWaitBlocks(contract: BlockContract, arguments: JsonObject, draft: RecognitionWaitDraft,
    variables: List<CalculationVariable>): List<VisualBlockTree> {
    require(contract.kind in recognitionWaitKinds) { "当前识别不支持等待组合" }
    val color = contract.kind in setOf("vision.findcolor","vision.findmulticolor")
    val ocr = contract.kind.startsWith("ocr.")
    val glyphContains = recognitionWaitUsesGlyphDetails(contract.kind, draft)
    val timeout = requireNotNull(draft.timeout.toIntOrNull()) { "总超时须为整数毫秒" }
    val interval = requireNotNull(draft.interval.toIntOrNull()) { "间隔须为整数毫秒" }
    val attempts = if (draft.mode == RecognitionWaitMode.RETRY) requireNotNull(draft.attempts.toIntOrNull()) { "重试次数须为整数" } else 1
    val absence = if (draft.mode == RecognitionWaitMode.ABSENT) requireNotNull(draft.absentMs.toIntOrNull()) { "未命中时长须为整数毫秒" } else 1
    require(timeout in 1..600_000 && interval in 1..60_000 && attempts in 1..10_000) { "总超时1–600000ms，间隔1–60000ms，次数1–10000" }
    if (draft.mode == RecognitionWaitMode.ABSENT) require(absence in 1..timeout) { "连续未命中时长须在1至总超时之间" }
    val work = listOf(draft.start, draft.clock, draft.elapsed, draft.attempt, draft.outcome) +
        if (draft.mode == RecognitionWaitMode.ABSENT) listOf(draft.absentStart, draft.absentElapsed) else emptyList()
    require(work.distinct().size == work.size && work.all { name -> variables.any { it.name == name && it.type == ProjectVariableType.INTEGER } }) {
        "工作变量需选择互不重名的已声明整数变量"
    }
    val found = if (color || ocr) draft.match else arguments["foundVariable"]?.asString.orEmpty()
    require(variables.any { it.name == found && it.type == ProjectVariableType.INTEGER }) { "是否找到需选择已声明整数变量（1/0）" }
    val references = recognitionWaitReferences(arguments)
    require(work.none { it in references }) { "工作变量不能覆盖识别结果、帧或动态范围变量" }
    require(found !in work) { "条件状态不能与工作变量重名" }
    if (color || ocr) require(found !in references) { "条件状态不能覆盖原识别输出、分数、覆盖率或来源变量" }
    if (color) require(variables.any { it.name == draft.sampleResult && it.type == ProjectVariableType.STRING } && draft.sampleResult !in references) { "请选择独立字符变量接收临时颜色集合" }
    if (ocr) {
        require(variables.any { it.name == arguments["textVariable"]?.asString && it.type == ProjectVariableType.STRING }) { "识别文字需选择已声明字符变量" }
        require(draft.targetText.toByteArray(Charsets.UTF_8).size <= 256) { "目标文字最多256字节" }
        if (contract.kind == "ocr.glyph") require(variables.any {
            it.name == arguments["coverageVariable"]?.asString && it.type == ProjectVariableType.INTEGER
        }) { "字库覆盖率需选择已声明整数变量，用于排除未识别占位符" }
        if(contract.kind=="ocr.glyph") require(arguments["coverageVariable"]!=arguments["scoreVariable"]) { "字库覆盖率与得分必须使用不同变量，请返回识别配置修改" }
    }
    if (glyphContains) {
        require(draft.targetText.none(Char::isWhitespace)) { "字库包含目标须为同一行连续文字，不包含空格或换行；完整文本可用完全一致" }
        val strings = listOf(draft.glyphDetails, draft.glyphMatches)
        require(strings.distinct().size == 2 && strings.none { it in references } && strings.all { name ->
            variables.any { it.name == name && it.type == ProjectVariableType.STRING }
        }) { "逐字详情与目标匹配须选择两个独立的已声明字符变量，不能覆盖原文字或其他识别引用" }
    }
    if (color || contract.kind == "ocr.glyph") require(variables.any { it.name == arguments["frameVariable"]?.asString && it.type == ProjectVariableType.IMAGE }) { "截图暂存需选择已声明专用图像变量" }
    fun json(vararg values: Pair<String, Any>): JsonObject = JsonObject().apply { values.forEach { (key, value) ->
        when (value) { is Number -> addProperty(key, value); is Boolean -> addProperty(key, value); else -> addProperty(key, value.toString()) }
    } }
    fun block(kind: String, args: JsonObject = JsonObject(), children: Map<String, List<VisualBlockTree>> = emptyMap()) =
        VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), args, children)
    fun set(name: String, value: String) = block("variable.calculate", json("name" to name, "expression" to value))
    fun now() = block("system.elapsed", json("resultVariable" to draft.clock))
    fun elapsed() = set(draft.elapsed, "${draft.clock} - ${draft.start}")
    fun branch(name: String, operator: String, value: Int, then: List<VisualBlockTree>, otherwise: List<VisualBlockTree> = emptyList()) =
        block("control.if", json("variable" to name, "operator" to operator, "value" to value), mapOf("then" to then, "else" to otherwise))
    val stop = block("control.break")
    val deadline = branch(draft.elapsed, "greaterOrEqual", timeout, listOf(stop))
    val recognition = arguments.deepCopy().apply {
        if (recognitionUsesOwnCapture(contract.kind)) addProperty("autoCapture", true) else remove("autoCapture")
        if (contract.kind == "vision.findimage") { addProperty("frequency", 1); addProperty("successAction", "none") }
    }
    val samples: List<VisualBlockTree> = if (color) {
        val region = arguments["region"].asJsonObject
        val colors = if (contract.kind == "vision.findcolor") arguments.deepCopy().apply {
            remove("foundVariable"); remove("xVariable"); remove("yVariable"); remove("autoCapture"); addProperty("limit",1); addProperty("resultVariable",draft.sampleResult)
        } else JsonObject().apply {
            addProperty("frameVariable",arguments["frameVariable"].asString); addProperty("resultVariable",draft.sampleResult); addProperty("limit",1)
            listOf("left","top","right","bottom").forEach { add(it,region[it]) }
            addProperty("samples",com.google.gson.JsonArray().apply {
                add(json("dx" to 0,"dy" to 0,"rgb" to arguments["anchorRgb"].asInt,"tolerance" to arguments["anchorTolerance"].asInt))
                arguments["samples"].asJsonArray.forEach { sample -> val s=sample.asJsonObject; add(json("dx" to s["x"].asInt,"dy" to s["y"].asInt,"rgb" to s["rgb"].asInt,"tolerance" to s["tolerance"].asInt)) }
            }.toString())
        }
        if(contract.kind=="vision.findmulticolor") visionSampleError(colors["samples"].asString)?.let { throw IllegalArgumentException(it) }
        val sampleContract = requireNotNull(BlockCatalog.find(if (contract.kind == "vision.findcolor") "vision.findallcolor" else "vision.findallmulticolor"))
        recognitionImageEntryBlocks(sampleContract,colors,true).map { VisualBlockTree(it.first,it.second) } +
            block("vision.readresult",json("sourceVariable" to draft.sampleResult,"shape" to "COLORS","index" to 0,"field" to "count","resultVariable" to found)) +
            branch(found,"greaterThan",0,listOf(set(found,"1")),listOf(set(found,"0")))
    } else if (glyphContains) {
        val details = json("frameVariable" to arguments["frameVariable"].asString,
            "resultVariable" to draft.glyphDetails)
        listOf("dictionaryPath", "foregroundRgb", "tolerance", "similarityPermille", "spaceGapColumns").forEach { key ->
            details.add(key, requireNotNull(arguments[key]) { "字库参数缺少$key" }.deepCopy())
        }
        val region = arguments["region"].asJsonObject
        listOf("left", "top", "right", "bottom").forEach { details.add(it, region[it].deepCopy()) }
        // One captured frame produces both the ordinary outputs and the safe target predicate.
        recognitionImageEntryBlocks(requireNotNull(BlockCatalog.find("ocr.glyphdetails")), details, true)
            .map { VisualBlockTree(it.first, it.second) } +
            listOf("text" to "textVariable", "coveragePermille" to "coverageVariable", "averageScorePermille" to "scoreVariable").map { (field, output) ->
                block("vision.readresult", json("sourceVariable" to draft.glyphDetails, "shape" to "GLYPH_SUMMARY",
                    "index" to 0, "field" to field, "resultVariable" to arguments[output].asString))
            } + listOf(
                block("ocr.findtext", json("detailsVariable" to draft.glyphDetails, "text" to draft.targetText, "resultVariable" to draft.glyphMatches)),
                block("vision.readresult", json("sourceVariable" to draft.glyphMatches, "shape" to "TEXT_ITEM", "index" to 0,
                    "field" to "count", "resultVariable" to found)),
                branch(found, "greaterThan", 0, listOf(set(found, "1")), listOf(set(found, "0"))))
    } else if (ocr) {
        val text = arguments["textVariable"].asString
        val predicate =
            if (draft.targetText.isEmpty()) listOf(block("text.length",json("text" to "","textVariable" to text,"resultVariable" to found)),
                branch(found,"greaterThan",0,listOf(set(found,"1")),listOf(set(found,"0"))))
            else if (draft.exactText) listOf(block("control.if",json("variable" to text,"operator" to "equals","value" to draft.targetText),
                mapOf("then" to listOf(set(found,"1")),"else" to listOf(set(found,"0")))))
            else listOf(block("text.contains",json("text" to "","textVariable" to text,"needle" to draft.targetText,"resultVariable" to found)))
        recognitionImageEntryBlocks(contract,recognition,true).map { VisualBlockTree(it.first,it.second) } +
            if (contract.kind == "ocr.glyph") listOf(branch(arguments["coverageVariable"].asString,
                if (draft.targetText.isEmpty()) "greaterThan" else "equals", if (draft.targetText.isEmpty()) 0 else 1000,
                predicate, listOf(set(found,"0")))) else predicate
    } else listOf(VisualBlockTree(contract,recognition))
    val success = listOf(set(draft.outcome, "1"), stop)
    val inspect = if (draft.mode == RecognitionWaitMode.ABSENT) branch(found, "equals", 1,
        listOf(set(draft.absentStart, "-1"), set(draft.absentElapsed, "0")),
        listOf(branch(draft.absentStart, "lessThan", 0, listOf(set(draft.absentStart, draft.clock))),
            set(draft.absentElapsed, "${draft.clock} - ${draft.absentStart}"), branch(draft.absentElapsed, "greaterOrEqual", absence, success)))
    else branch(found, "equals", if (draft.mode == RecognitionWaitMode.DISAPPEAR) 0 else 1, success)
    val body = listOf(now(), elapsed(), deadline, set(draft.attempt, "${draft.attempt} + 1"),
        ) + samples + listOf(now(), elapsed(), deadline, inspect) +
        (if (draft.mode == RecognitionWaitMode.RETRY) listOf(branch(draft.attempt, "greaterOrEqual", attempts, listOf(stop))) else emptyList()) +
        listOf(set(draft.clock, "$timeout - ${draft.elapsed}"),
            branch(draft.clock, "greaterThan", interval, listOf(set(draft.clock, interval.toString()))),
            block("task.sleep", json("milliseconds" to 0, "millisecondsVariable" to draft.clock, "exactTiming" to true)))
    return listOf(set(draft.outcome, "0"), set(draft.attempt, "0"), set(draft.elapsed, "0"),
        block("system.elapsed", json("resultVariable" to draft.start))) +
        (if (draft.mode == RecognitionWaitMode.ABSENT) listOf(set(draft.absentStart, "-1"), set(draft.absentElapsed, "0")) else emptyList()) +
        listOf(block("control.cycle", json("yieldMs" to 1, "durationMs" to timeout), mapOf("body" to body)), now(), elapsed(),
            branch(draft.outcome, "equals", 1, emptyList()))
}

/** Reserve recognition input/output references before presenting choices as well as on submit. */
internal fun recognitionWaitReferences(arguments: JsonObject): Set<String> = arguments.entrySet()
    .filter { it.key.endsWith("Variable") && it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
    .map { it.value.asString }.filter(String::isNotBlank).toSet()

internal fun recognitionWaitWorkNames(draft: RecognitionWaitDraft): List<String> =
    listOf(draft.start,draft.clock,draft.elapsed,draft.attempt,draft.outcome) +
        if(draft.mode==RecognitionWaitMode.ABSENT) listOf(draft.absentStart,draft.absentElapsed) else emptyList()

internal fun recognitionWorkVariableSuggestion(draft: RecognitionWaitDraft, variables: List<CalculationVariable>, references: Set<String>): RecognitionWaitDraft? {
    return recognitionVariableSuggestion("vision.findimage", draft, variables, references + draft.match)
}

internal data class RecognitionVariableSlot(val key: String, val label: String, val value: String,
    val type: ProjectVariableType = ProjectVariableType.INTEGER)

internal fun recognitionWaitSlots(kind: String, draft: RecognitionWaitDraft): List<RecognitionVariableSlot> = buildList {
    add(RecognitionVariableSlot("start", "开始时刻", draft.start))
    add(RecognitionVariableSlot("clock", "时钟/等待临时值", draft.clock))
    add(RecognitionVariableSlot("elapsed", "总耗时 ms", draft.elapsed))
    add(RecognitionVariableSlot("attempt", "尝试次数", draft.attempt))
    add(RecognitionVariableSlot("outcome", "完成状态 1/0", draft.outcome))
    if (draft.mode == RecognitionWaitMode.ABSENT) {
        add(RecognitionVariableSlot("absentStart", "未命中起点", draft.absentStart))
        add(RecognitionVariableSlot("absentElapsed", "连续未命中 ms", draft.absentElapsed))
    }
    if (kind.startsWith("ocr.") || kind in setOf("vision.findcolor", "vision.findmulticolor")) add(RecognitionVariableSlot("match", "条件状态 1/0", draft.match))
    if (kind in setOf("vision.findcolor", "vision.findmulticolor")) add(RecognitionVariableSlot("sampleResult", "颜色集合", draft.sampleResult, ProjectVariableType.STRING))
    if (recognitionWaitUsesGlyphDetails(kind, draft)) {
        add(RecognitionVariableSlot("glyphDetails", "逐字详情", draft.glyphDetails, ProjectVariableType.STRING))
        add(RecognitionVariableSlot("glyphMatches", "目标匹配集合", draft.glyphMatches, ProjectVariableType.STRING))
    }
}

internal fun recognitionWaitAssign(draft: RecognitionWaitDraft, key: String, value: String): RecognitionWaitDraft = when (key) {
    "start" -> draft.copy(start=value); "clock" -> draft.copy(clock=value); "elapsed" -> draft.copy(elapsed=value)
    "attempt" -> draft.copy(attempt=value); "outcome" -> draft.copy(outcome=value)
    "absentStart" -> draft.copy(absentStart=value); "absentElapsed" -> draft.copy(absentElapsed=value)
    "match" -> draft.copy(match=value); "sampleResult" -> draft.copy(sampleResult=value)
    "glyphDetails" -> draft.copy(glyphDetails=value); "glyphMatches" -> draft.copy(glyphMatches=value)
    else -> error("未知工作变量")
}

/** Keep valid user choices; a suggested mapping is applied only after explicit confirmation. */
internal fun recognitionVariableSuggestion(kind: String, draft: RecognitionWaitDraft,
    variables: List<CalculationVariable>, references: Set<String>): RecognitionWaitDraft? {
    val slots = recognitionWaitSlots(kind, draft)
    val used = references.toMutableSet()
    val chosen = mutableMapOf<String,String>()
    slots.forEach { slot -> if (slot.value !in used && variables.any { it.name==slot.value && it.type==slot.type }) {
        chosen[slot.key]=slot.value; used.add(slot.value)
    } }
    slots.filter { it.key !in chosen }.forEach { slot ->
        val name=variables.firstOrNull { it.type==slot.type && it.name !in used }?.name ?: return null
        chosen[slot.key]=name; used.add(name)
    }
    return slots.fold(draft) { result, slot -> recognitionWaitAssign(result,slot.key,chosen.getValue(slot.key)) }
}
