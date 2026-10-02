package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class VisualLoopDialogTest {
    private val variables = listOf(CalculationVariable("n", ProjectVariableType.INTEGER, "局部"),
        CalculationVariable("seconds", ProjectVariableType.NUMBER, "全局"), CalculationVariable("label", ProjectVariableType.STRING, "局部"))
    private fun build(draft: LoopDraft) = requireNotNull(buildLoopInsertion(draft, variables).insertion)

    @Test fun fixedLoopInputsAreStrictInsteadOfSilentlyClamped() {
        assertEquals(0, build(LoopDraft(count = "0")).arguments["times"].asInt)
        for (value in listOf("", "-1", "1.5", "NaN", "1000001", "99999999999999999999")) {
            assertNotNull(value, buildLoopInsertion(LoopDraft(count = value), variables).error)
        }
        for (value in listOf("", "0", "-1", "1e3", "NaN", "0.0001", "86401")) {
            assertNotNull(value, buildLoopInsertion(LoopDraft(mode = LoopMode.TIMED, time = value), variables).error)
        }
        assertEquals(500, build(LoopDraft(mode = LoopMode.TIMED, time = "0.5")).arguments["durationMs"].asInt)
        assertEquals(120000L, loopMilliseconds("2", LoopTimeUnit.MINUTES))
        assertEquals(86400000L, loopMilliseconds("86400", LoopTimeUnit.SECONDS))
    }

    @Test fun variablesRespectTypesAndTimeUnitIsExplicit() {
        assertNotNull(buildLoopInsertion(LoopDraft(variableMode = true, countVariable = "seconds"), variables).error)
        assertNotNull(buildLoopInsertion(LoopDraft(variableMode = true, countVariable = "missing"), variables).error)
        assertEquals("n", build(LoopDraft(variableMode = true, countVariable = "n")).arguments["timesVariable"].asString)
        val time = build(LoopDraft(mode = LoopMode.TIMED, variableMode = true, timeVariable = "seconds"))
        assertEquals("seconds", time.arguments["durationUnit"].asString)
        assertFalse(time.arguments.has("durationMs"))
    }

    @Test fun positionalMetricsAreIndependentNodesAndSecondsRequireFloatOutput() {
        val count = build(LoopDraft(mode = LoopMode.COUNT, countOutput = "n"))
        assertEquals("control.loopmetric", count.kind)
        assertEquals("count", count.arguments["metric"].asString)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "n"), variables).error)
        val elapsed = build(LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "seconds"))
        assertEquals("seconds", elapsed.arguments["unit"].asString)
        assertEquals("milliseconds", build(LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "n", elapsedUnit = LoopTimeUnit.MILLISECONDS)).arguments["unit"].asString)
    }

    @Test fun bothDockAdaptersReceiveExactSameStructuredArguments() {
        for (draft in listOf(LoopDraft(), LoopDraft(mode = LoopMode.FOREVER), LoopDraft(mode = LoopMode.TIMED),
            LoopDraft(mode = LoopMode.COUNT, countOutput = "n"), LoopDraft(mode = LoopMode.WAIT), LoopDraft(mode = LoopMode.CHECK_TIME))) {
            val insertion = build(draft)
            assertEquals(insertion, loopInsertionFromHint(insertion.hint()))
            assertEquals(insertion.kind, FunctionCatalog.blockKindOf(insertion.hint()))
            val contract = requireNotNull(BlockCatalog.find(insertion.kind))
            val defaults = JsonObject().apply { addProperty("unrelated", "must-not-leak") }
            assertEquals(insertion.arguments, legacyDockBlockArguments(contract, insertion.hint(), defaults))
        }
        assertNull(loopInsertionFromHint("$LOOP_CONFIG_HINT{\"kind\":\"shell\",\"args\":{}}"))
        assertEquals("control.loopmetric", FunctionCatalog.blockKindOf("--@autoscript-loop:metric:count:n"))
        assertEquals("milliseconds", loopInsertionFromHint("--@autoscript-loop:metric:elapsed:seconds")!!.arguments["unit"].asString)
    }

    @Test fun waitsAndChecksUseRealContractsWithBoundedValues() {
        assertEquals(0, build(LoopDraft(mode = LoopMode.WAIT, delay = "0")).arguments["milliseconds"].asInt)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.WAIT, delay = "1.5"), variables).error)
        assertEquals("n", build(LoopDraft(mode = LoopMode.WAIT, variableMode = true, delayVariable = "n")).arguments["millisecondsVariable"].asString)
        val check = build(LoopDraft(mode = LoopMode.CHECK_TIME, checkTime = "0.5"))
        assertEquals("control.loopcheck", check.kind)
        assertEquals(0.5, check.arguments["limit"].asDouble, 0.0)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.CHECK_COUNT, checkCount = "0"), variables).error)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.FOREVER, maxIterations = "0"), variables).error)
    }

    @Test fun metricsCannotBeInsertedBesideRootLoopOrReplaceIt() {
        val editor = VisualEditorState.create("", "root")
        assertNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.loopmetric")), JsonObject()))
        assertNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.repeat")), JsonObject().apply { addProperty("times", 3) }))
        val owner = editor.selectedNodeId
        assertNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.loopmetric")), JsonObject()))
        assertFalse(editor.replaceSelectedBlock(requireNotNull(BlockCatalog.find("control.loopcheck")), JsonObject()))
        assertNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.loopmetric")), JsonObject(), intoChildBlockName = "body"))
        assertEquals(owner, editor.nearestAncestorOfKind(kinds = setOf("control.repeat")))
        assertNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.loopcheck")), JsonObject()))
        assertEquals(1, editor.rows.count { it.kind == "control.repeat" })
    }
}
