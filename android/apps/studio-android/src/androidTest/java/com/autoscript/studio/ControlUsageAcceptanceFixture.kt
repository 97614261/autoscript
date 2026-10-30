package com.autoscript.studio

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.*

internal object ControlUsageAcceptanceFixture {
    fun run(stage: String, test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("count" to ProjectVariableType.INTEGER, "value" to ProjectVariableType.INTEGER,
            "index" to ProjectVariableType.INTEGER, "elapsed" to ProjectVariableType.INTEGER, "text" to ProjectVariableType.STRING)
        val ui = UsageTestUi(test)
        var panel by mutableStateOf("")
        var inputs by mutableStateOf(JsonObject())
        var loop by mutableStateOf(LoopDraft())
        var accepted by mutableStateOf<JsonObject?>(null)
        val conditionContract = requireNotNull(BlockCatalog.find("control.if"))
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            when (panel) {
                "condition" -> VisualVariableConditionDialog(conditionContract, conditionPropertyTexts(conditionContract, inputs),
                    listOf("count", "value", "text"), rt.snapshot.manifest.variables, rt.main.flowId, rt.snapshot.manifest.flows,
                    onDismiss = { panel = "" }, onConfirm = { values ->
                        val parsed = parseBlockArguments(conditionContract, values, null, emptyMap(), emptyList())
                        if (parsed.first == null) parsed.second else { accepted = parsed.first; panel = ""; null }
                    }, confirmLabel = "QA加入")
                "loop" -> VisualLoopDialog(rt.snapshot.manifest.variables, rt.main.flowId, rt.snapshot.manifest.flows,
                    { panel = "" }, { accepted = requireNotNull(loopInsertionFromHint(it)).arguments; panel = "" },
                    initialDraft = loop, confirmLabel = "QA加入")
            }
        } } }
        fun branches(args: JsonObject) = VisualBlockTree(conditionContract, args,
            mapOf("then" to listOf(rt.set("value", "1")), "else" to listOf(rt.set("value", "-1"))))
        fun condition(args: JsonObject, expected: String) {
            rt.run(listOf(rt.set("count", "5"), rt.set("text", "\"智构\""), branches(args))) { check(it["value"] == expected) { "QA_CONDITION_WRONG_BRANCH:$it" } }
        }
        try {
            if (stage == "conditions") {
                for ((operator, expected) in listOf("equals" to "1", "notEquals" to "-1", "lessThan" to "-1",
                    "lessOrEqual" to "1", "greaterThan" to "-1", "greaterOrEqual" to "1")) record("condition-real-form-and-runtime-$operator") {
                    test.runOnMainSync { inputs = rt.block("control.if", "variable" to "count", "operator" to operator, "value" to 5).arguments; accepted = null; panel = "condition" }
                    ui.click("QA加入"); ui.await("condition submitted") { accepted != null && panel.isEmpty() }
                    condition(requireNotNull(accepted), expected)
                }
                record("condition-chinese-string-and-right-variable-priority") {
                    condition(rt.block("control.if", "variable" to "text", "operator" to "equals", "value" to "智构").arguments, "1")
                    condition(rt.block("control.if", "variable" to "count", "operator" to "equals", "value" to 0, "valueVariable" to "count").arguments, "1")
                }
                for ((type, expected) in listOf("all" to "-1", "any" to "1", "not" to "-1")) record("condition-compound-$type-real-runtime") {
                    val yes = conditionSeed("count", "equals", "5")
                    val no = conditionSeed("count", "equals", "6")
                    val tree = JsonObject().apply { addProperty("type", type); if (type == "not") add("condition", yes)
                        else add("conditions", JsonArray().apply { add(yes); add(no) }) }
                    condition(rt.block("control.if", "variable" to "count", "operator" to "equals", "value" to 5, "condition" to tree).arguments, expected)
                }
            } else {
                fun insertion(draft: LoopDraft): Pair<String, JsonObject> {
                    val built = buildLoopInsertion(draft, calculationVariables(rt.snapshot.manifest.variables, rt.main.flowId, rt.snapshot.manifest.flows))
                    return requireNotNull(built.insertion) { "QA_LOOP_BUILD:${built.error}" }.let { it.kind to it.arguments }
                }
                for (mode in LoopMode.entries) record("loop-real-form-${mode.name.lowercase()}") {
                    val draft = LoopDraft(mode = mode, count = "3", time = "30", delay = "0", checkCount = "2", checkTime = "20",
                        countOutput = "index", elapsedOutput = "elapsed")
                    test.runOnMainSync { loop = draft; accepted = null; panel = "loop" }
                    ui.click("QA加入"); ui.await("loop submitted") { accepted != null && panel.isEmpty() }
                    check(accepted == insertion(loopDraftForDialog(draft)).second) { "QA_LOOP_FORM_CHANGED_ARGUMENTS" }
                }
                fun loopNode(draft: LoopDraft, body: List<VisualBlockTree>): VisualBlockTree {
                    val (kind, args) = insertion(draft)
                    return VisualBlockTree(requireNotNull(BlockCatalog.find(kind)), args, mapOf("body" to body))
                }
                record("loop-repeat-variable-index-and-runtime-results") {
                    rt.run(listOf(rt.set("value", "3"), loopNode(LoopDraft(countVariable = "value", variableMode = true, iterationOutput = "index", loopElapsedOutput = "elapsed"),
                        listOf(rt.set("count", "count + 1"))))) { check(it["count"] == "3" && it["index"] == "3" && requireNotNull(it["elapsed"]).toLong() >= 0) }
                }
                record("loop-continue-and-break-nearest-loop") {
                    val skip = rt.block("control.if", "variable" to "count", "operator" to "equals", "value" to 2).copy(children = mapOf("then" to listOf(rt.block("control.continue")), "else" to emptyList()))
                    val end = rt.block("control.if", "variable" to "count", "operator" to "equals", "value" to 4).copy(children = mapOf("then" to listOf(rt.block("control.break")), "else" to emptyList()))
                    rt.run(listOf(loopNode(LoopDraft(count = "10"), listOf(rt.set("count", "count + 1"), skip, end, rt.set("value", "value + 10"))))) {
                        check(it["count"] == "4" && it["value"] == "20")
                    }
                }
                record("loop-timed-and-empty-wait-use-milliseconds") {
                    rt.run(listOf(loopNode(LoopDraft(mode = LoopMode.TIMED, time = "30", loopElapsedOutput = "elapsed"), listOf(rt.set("count", "count + 1"))),
                        rt.block("task.sleep", "milliseconds" to 20))) { check(requireNotNull(it["count"]).toLong() > 0 && requireNotNull(it["elapsed"]).toLong() >= 25) }
                }
                record("loop-infinite-with-count-threshold-exits") {
                    rt.run(listOf(loopNode(LoopDraft(mode = LoopMode.FOREVER), listOf(rt.set("count", "count + 1"),
                        rt.block("control.loopcheck", "metric" to "count", "limit" to 3, "unit" to "milliseconds"))))) { check(it["count"] == "3") }
                }
                record("loop-nested-repeat-keeps-inner-and-outer-counts") {
                    rt.run(listOf(loopNode(LoopDraft(count = "2"), listOf(loopNode(LoopDraft(count = "3"), listOf(rt.set("count", "count + 1"))))))) { check(it["count"] == "6") }
                }
                record("loop-while-uses-real-condition-and-exits-with-checked-value") {
                    val body = rt.block("control.while", "variable" to "count", "operator" to "lessThan", "value" to 3,
                        "maxIterations" to 10).copy(children = mapOf("body" to listOf(rt.block("task.noop"), rt.set("count", "count + 1"))))
                    rt.run(listOf(body)) { check(it["count"] == "3") }
                }
                record("loop-metric-reports-current-iteration-to-real-variable") {
                    rt.run(listOf(loopNode(LoopDraft(count = "3"), listOf(rt.block("control.loopmetric", "metric" to "count", "name" to "index"))))) {
                        check(it["index"] == "3")
                    }
                }
            }
            return store.openProject(rt.id)
        } finally { test.runOnMainSync { panel = "" }; rt.stop() }
    }
}
