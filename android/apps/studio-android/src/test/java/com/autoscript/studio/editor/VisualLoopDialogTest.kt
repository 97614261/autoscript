package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class VisualLoopDialogTest {
    @Test fun variableSelectionChangesSourceOnlyAfterChoosingAndKeepsLiteralDrafts() {
        val literal = LoopDraft(count = "17", time = "0.5", unit = LoopTimeUnit.MINUTES)
        for (key in listOf("count", "time", "delay", "checkCount", "checkTime")) {
            val selected = applyLoopPickerSelection(literal, key, "n")
            assertTrue(selected.variableMode)
            assertEquals("17", selected.count)
            assertEquals("0.5", selected.time)
            assertEquals(LoopTimeUnit.MINUTES, selected.unit)
            val variable = when (key) {
                "count" -> selected.countVariable
                "time" -> selected.timeVariable
                "delay" -> selected.delayVariable
                "checkCount" -> selected.checkCountVariable
                else -> selected.checkTimeVariable
            }
            assertEquals("n", variable)
        }
        assertFalse(applyLoopPickerSelection(literal, "countOutput", "n").variableMode)
        assertEquals("n", applyLoopPickerSelection(literal, "iterationOutput", "n").iterationOutput)
        val preset = applyLoopPickerSelection(literal, "timePreset", "10000")
        assertEquals("10000", preset.time)
        assertEquals(LoopTimeUnit.MILLISECONDS, preset.unit)
    }

    @Test fun legacyAlwaysLoopsCanChangeDurationWithoutChangingNodeKind() {
        assertTrue(loopModeCanEdit(LoopMode.TIMED, "control.while", cancellable = false))
        assertTrue(loopModeCanEdit(LoopMode.FOREVER, "control.while", cancellable = false))
        assertFalse(loopModeCanEdit(LoopMode.REPEAT, "control.while", cancellable = false))
        assertFalse(loopModeCanEdit(LoopMode.TIMED, "control.while", cancellable = true))
        assertTrue(loopModeCanEdit(LoopMode.TIMED, "control.cycle", cancellable = true))
    }

    @Test fun repeatAndCycleOutputVariablesSurviveTheDedicatedEditor() {
        for ((kind, outputKey) in listOf("control.repeat" to "indexVariable", "control.cycle" to "iterationVariable", "control.while" to "iterationVariable")) {
            val args = JsonObject().apply {
                if (kind == "control.repeat") addProperty("times", 3)
                else if (kind == "control.cycle") addProperty("yieldMs", 1)
                else { addProperty("always", true); addProperty("maxIterations", 25) }
                addProperty(outputKey, "n"); addProperty("elapsedVariable", "seconds")
            }
            val result = build(loopDraftFromArguments(kind, args))
            assertEquals(kind, result.kind)
            assertEquals("n", result.arguments[outputKey].asString)
            assertEquals("seconds", result.arguments["elapsedVariable"].asString)
            assertFalse(result.arguments.has(if (outputKey == "indexVariable") "iterationVariable" else "indexVariable"))
        }
    }

    @Test fun outputPickersDoNotOfferParametersAndIntegerInputsHideFloats() {
        val choices = listOf(CalculationVariable("arg", ProjectVariableType.INTEGER, "参数"),
            CalculationVariable("count", ProjectVariableType.INTEGER, "全局"),
            CalculationVariable("time", ProjectVariableType.NUMBER, "局部"))
        assertEquals(listOf("arg", "count"), loopPickerChoices("count", LoopDraft(), choices).map { it.name })
        assertEquals(listOf("count"), loopPickerChoices("iterationOutput", LoopDraft(), choices).map { it.name })
        assertEquals(listOf("count", "time"), loopPickerChoices("loopElapsedOutput", LoopDraft(), choices).map { it.name })
        assertEquals("全局", preferredCalculationScope(loopPickerChoices("iterationOutput", LoopDraft(), choices)))
        assertEquals("参数", preferredCalculationScope(listOf(choices.first())))
    }
    private val variables = listOf(CalculationVariable("n", ProjectVariableType.INTEGER, "局部"),
        CalculationVariable("seconds", ProjectVariableType.NUMBER, "全局"), CalculationVariable("label", ProjectVariableType.STRING, "局部"))
    private fun build(draft: LoopDraft) = requireNotNull(buildLoopInsertion(draft, variables).insertion)

    @Test fun fixedLoopInputsAreStrictInsteadOfSilentlyClamped() {
        assertEquals(0, build(LoopDraft(count = "0")).arguments["times"].asInt)
        for (value in listOf("", "-1", "1.5", "NaN", "1000001", "99999999999999999999")) {
            assertNotNull(value, buildLoopInsertion(LoopDraft(count = value), variables).error)
        }
        for (value in listOf("", "0", "-1", "1e3", "NaN", "0.0001", "86400001")) {
            assertNotNull(value, buildLoopInsertion(LoopDraft(mode = LoopMode.TIMED, time = value), variables).error)
        }
        assertEquals(500, build(LoopDraft(mode = LoopMode.TIMED, time = "500")).arguments["durationMs"].asInt)
        assertEquals(500, build(LoopDraft(mode = LoopMode.TIMED, time = "0.5", unit = LoopTimeUnit.SECONDS)).arguments["durationMs"].asInt)
        assertEquals(120000L, loopMilliseconds("2", LoopTimeUnit.MINUTES))
        assertEquals(86400000L, loopMilliseconds("86400", LoopTimeUnit.SECONDS))
    }

    @Test fun variablesRespectTypesAndTimeUnitIsExplicit() {
        assertNotNull(buildLoopInsertion(LoopDraft(variableMode = true, countVariable = "seconds"), variables).error)
        assertNotNull(buildLoopInsertion(LoopDraft(variableMode = true, countVariable = "missing"), variables).error)
        assertEquals("n", build(LoopDraft(variableMode = true, countVariable = "n")).arguments["timesVariable"].asString)
        val time = build(LoopDraft(mode = LoopMode.TIMED, variableMode = true, timeVariable = "seconds"))
        assertEquals("milliseconds", time.arguments["durationUnit"].asString)
        assertFalse(time.arguments.has("durationMs"))
    }

    @Test fun positionalMetricsAreIndependentNodesAndSecondsRequireFloatOutput() {
        val count = build(LoopDraft(mode = LoopMode.COUNT, countOutput = "n"))
        assertEquals("control.loopmetric", count.kind)
        assertEquals("count", count.arguments["metric"].asString)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "n", elapsedUnit = LoopTimeUnit.SECONDS), variables).error)
        val elapsed = build(LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "seconds"))
        assertEquals("milliseconds", elapsed.arguments["unit"].asString)
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
        val check = build(LoopDraft(mode = LoopMode.CHECK_TIME, checkTime = "500"))
        assertEquals("control.loopcheck", check.kind)
        assertEquals(500, check.arguments["limit"].asInt)
        assertEquals("milliseconds", check.arguments["unit"].asString)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.CHECK_COUNT, checkCount = "0"), variables).error)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.FOREVER, cancellable = false, maxIterations = "0"), variables).error)
        assertNotNull(buildLoopInsertion(LoopDraft(mode = LoopMode.FOREVER, yieldMs = "0"), variables).error)
        assertEquals("control.cycle", build(LoopDraft(mode = LoopMode.FOREVER)).kind)
        val legacy = loopDraftFromArguments("control.while", JsonObject().apply { addProperty("maxIterations", 37) })
        assertFalse(legacy.cancellable)
        assertEquals(37, build(legacy).arguments["maxIterations"].asInt)
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

    @Test fun rowSourcesRemainIndependentAndCanReturnToTheirLiteralValues() {
        val selected = applyLoopPickerSelection(LoopDraft(count = "17"), "count", "n")
        assertEquals("n", build(selected).arguments["timesVariable"].asString)
        val timed = loopDraftForMode(selected, LoopMode.TIMED)
        assertFalse(timed.variableMode)
        assertEquals(3000, build(timed).arguments["durationMs"].asInt)
        val repeated = loopDraftForMode(timed, LoopMode.REPEAT)
        assertTrue(repeated.variableMode)
        val literal = clearLoopInputVariable(repeated, LoopMode.REPEAT)
        assertFalse(literal.variableMode)
        assertEquals("", literal.countVariable)
        assertEquals(17, build(literal).arguments["times"].asInt)
        val preset = applyLoopPickerSelection(applyLoopPickerSelection(timed, "time", "n"), "timePreset", "500")
        assertFalse(preset.variableMode)
        assertEquals("", preset.timeVariable)
        assertEquals(500, build(preset).arguments["durationMs"].asInt)
    }

    @Test fun millisecondsDialogConvertsOldLiteralsWithoutChangingDuration() {
        val old = loopDraftFromArguments("control.loopcheck", JsonObject().apply {
            addProperty("metric", "elapsed"); addProperty("limit", 0.5); addProperty("unit", "seconds")
        })
        val normalized = loopDraftForDialog(old)
        assertEquals(LoopMode.CHECK_TIME, normalized.mode)
        assertEquals("500", normalized.checkTime)
        assertEquals(LoopTimeUnit.MILLISECONDS, normalized.checkUnit)
        assertEquals(500, build(normalized).arguments["limit"].asInt)
        val timed = loopDraftForDialog(LoopDraft(mode = LoopMode.TIMED, time = "0.5", unit = LoopTimeUnit.MINUTES))
        assertEquals("30000", timed.time)
        assertEquals(30000, build(timed).arguments["durationMs"].asInt)
        assertNotNull(buildLoopInsertion(loopDraftForDialog(old.copy(checkTime = "-1")), variables).error)
    }

    @Test fun legacyVariableAndOutputUnitsSurviveOpeningTheMillisecondsDialog() {
        val old = LoopDraft(mode = LoopMode.TIMED, variableMode = true, time = "2", timeVariable = "seconds", unit = LoopTimeUnit.SECONDS)
        val opened = loopDraftForDialog(old)
        assertEquals("seconds", build(opened).arguments["durationUnit"].asString)
        val fixed = clearLoopInputVariable(opened, LoopMode.TIMED)
        assertEquals("2000", fixed.time)
        assertEquals(2000, build(fixed).arguments["durationMs"].asInt)
        val output = LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "seconds", elapsedUnit = LoopTimeUnit.MINUTES)
        assertEquals("minutes", build(loopDraftForDialog(output)).arguments["unit"].asString)
        assertEquals("milliseconds", build(LoopDraft(mode = LoopMode.ELAPSED, elapsedOutput = "n")).arguments["unit"].asString)
    }
}
