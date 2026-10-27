package com.autoscript.studio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DebugOutputExpressionTest {
    private fun field(text: String, start: Int = text.length, end: Int = start) =
        TextFieldValue(text, TextRange(start, end))

    private fun variable(field: TextFieldValue, name: String, labeled: Boolean = false,
        type: ProjectVariableType = ProjectVariableType.INTEGER) =
        DebugOutputExpression.variable(field, CalculationVariable(name, type, "全局"), labeled)

    @Test fun variableValuesAndChineseLabelsCanBePrintedRepeatedly() {
        val first = requireNotNull(variable(field(""), "次数", labeled = true))
        assertEquals("\"次数=\" .. text(次数)", first.text)
        val second = requireNotNull(variable(first, "金币", labeled = true))
        assertEquals("\"次数=\" .. text(次数) .. \"  \" .. \"金币=\" .. text(金币)", second.text)
        assertEquals(second.text.length, second.selection.start)
        assertEquals("text(count)", variable(field(""), "count")!!.text)
        assertNull(variable(field(""), "x);shell"))
    }

    @Test fun insertsAtStartMiddleEndAndReplacesForwardOrReverseSelection() {
        assertEquals("text(count) .. \"完成\"", variable(field("\"完成\"", 0), "count")!!.text)
        val text = "\"进度\" .. text(old) .. \"完成\""
        val start = text.indexOf("text(old)")
        val end = start + "text(old)".length
        for (selection in listOf(TextRange(start, end), TextRange(end, start))) {
            assertEquals("\"进度\" .. text(count) .. \"完成\"",
                variable(TextFieldValue(text, selection), "count")!!.text)
        }
        val result = requireNotNull(variable(field("\"前\"\"后\"", 3), "count"))
        assertEquals("\"前\" .. text(count) .. \"后\"", result.text)
        assertEquals("\"前\" .. text(count)".length, result.selection.start)
        assertEquals("\"进度\" .. text(count)", variable(field("\"进度\" .. "), "count")!!.text)
    }

    @Test fun insertsVariableInsideTextWithoutRequiringManualQuoteEditing() {
        val result = requireNotNull(variable(field("\"当前剩余\"", 3), "次数"))
        assertEquals("\"当前\" .. text(次数) .. \"剩余\"", result.text)
        assertEquals("\"当前\" .. text(次数)".length, result.selection.start)
        val selected = requireNotNull(variable(field("\"当前剩余\"", 1, 3), "次数"))
        assertEquals("\"\" .. text(次数) .. \"剩余\"", selected.text)
    }

    @Test fun newTextPlacesCursorBetweenQuotesAndAllowsContinuedTyping() {
        val result = requireNotNull(DebugOutputExpression.literal(field("text(count)"), "", editInside = true))
        assertEquals("text(count) .. \"\"", result.text)
        assertEquals(result.text.length - 1, result.selection.start)
        val inserted = requireNotNull(DebugOutputExpression.literal(result, "进度："))
        assertEquals("text(count) .. \"进度：\"", inserted.text)
    }

    @Test fun newlineSpaceQuotesBackslashesAndControlsUseJsonLiterals() {
        assertEquals("text(count) .. \"\\n\"", DebugOutputExpression.literal(field("text(count)"), "\n")!!.text)
        assertEquals("text(count) .. \" \"", DebugOutputExpression.literal(field("text(count)"), " ")!!.text)
        val content = "中文\n\t\r\"\\\u0001"
        val literal = requireNotNull(DebugOutputExpression.literal(field(""), content)).text
        assertEquals(content, JsonParser.parseString(literal).asString)
        val within = requireNotNull(DebugOutputExpression.literal(field("\"ab\"", 2), "\n\"\\"))
        assertEquals("a\n\"\\b", JsonParser.parseString(within.text).asString)
    }

    @Test fun arithmeticConversionWrapsSelectionOrWholeExpressionWithoutAssigningVariables() {
        assertEquals("text(count + 1)", DebugOutputExpression.asText(field("count + 1"))!!.text)
        val expression = "\"次数：\" .. count + 1"
        val start = expression.indexOf("count")
        assertEquals("\"次数：\" .. text(count + 1)", DebugOutputExpression.asText(field(expression, start, expression.length))!!.text)
        val empty = requireNotNull(DebugOutputExpression.asText(field("")))
        assertEquals("text()", empty.text)
        assertEquals(5, empty.selection.start)
        assertEquals("count + ", DebugOutputExpression.token(field("count"), " + ")!!.text)
        assertNull(DebugOutputExpression.token(field("count"), ";os.execute()"))
    }

    @Test fun shortcutsCannotSplitNamesEscapesOrSurrogatesOrCrossLiteralBoundaries() {
        for (input in listOf(field("count", 2), field("次数", 1), field("12.25", 3), field("a .. b", 3),
            field("\"a\\n\"", 3), field("\"a\\u0001b\"", 5), field("\"😀\"", 2), field("\"ab\" .. \"cd\"", 2, 10))) {
            assertNull(input.toString(), variable(input, "count"))
            assertNull(DebugOutputExpression.literal(input, "\n"))
        }
        assertNull(DebugOutputExpression.token(field("\"abc\"", 2), " + "))
        assertNull(DebugOutputExpression.asText(field("\"abc\"", 2, 3)))
    }

    @Test fun utf8BudgetFailureLeavesOriginalTextSelectionAndCompositionUntouched() {
        val input = field("\"" + "中".repeat(340) + "\"")
        assertEquals(1022, input.text.toByteArray(Charsets.UTF_8).size)
        assertNull(variable(input, "次数"))
        assertNull(DebugOutputExpression.literal(input, "\n"))
        assertNull(DebugOutputExpression.asText(input))
        assertNull(DebugOutputExpression.token(input, " + "))
        val exact = requireNotNull(DebugOutputExpression.literal(field(""), "x".repeat(1022)))
        assertEquals(1024, exact.text.toByteArray(Charsets.UTF_8).size)
        assertEquals(input.text.length, input.selection.start)
    }

    @Test fun outputDraftAndAllChannelsKeepShortcutTextThroughBlockRoundTrip() {
        val expression = requireNotNull(variable(field(""), "次数", labeled = true))
        val withNewline = requireNotNull(DebugOutputExpression.literal(expression, "\n"))
        val output = requireNotNull(variable(withNewline, "状态", type = ProjectVariableType.STRING))
        for (mode in DebugOutputCode.RUN_PROMPT..DebugOutputCode.LOG) {
            val snippet = requireNotNull(DebugOutputCode.expressionSnippet(mode, output.text, true))
            val args = legacyDockBlockArguments(requireNotNull(BlockCatalog.find("task.message")), snippet, com.google.gson.JsonObject())
            val restored = DebugOutputDraft.from("task.message", args)
            assertEquals(output.text, restored.text)
            assertEquals(mode, restored.mode)
            assertTrue(restored.expression)
            assertFalse(restored.needsDiscard(restored, editingExisting = true))
        }
    }

    @Test fun stringShortcutsKeepRawValuesWhileNumericShortcutsConvertOnlyForDisplay() {
        val first = requireNotNull(variable(field(""), "识别文本", labeled = true, type = ProjectVariableType.STRING))
        assertEquals("\"识别文本=\" .. 识别文本", first.text)
        assertEquals("识别文本", variable(field(""), "识别文本", type = ProjectVariableType.STRING)!!.text)
        val number = requireNotNull(variable(first, "数值", labeled = true, type = ProjectVariableType.NUMBER))
        assertEquals("\"识别文本=\" .. 识别文本 .. \"  \" .. \"数值=\" .. text(数值)", number.text)
        val inside = requireNotNull(variable(field("\"前后\"", 2), "识别文本", type = ProjectVariableType.STRING))
        assertEquals("\"前\" .. 识别文本 .. \"后\"", inside.text)
        val previous = "text(旧值)"
        val selected = variable(field(previous, 0, previous.length), "识别文本", type = ProjectVariableType.STRING)
        assertEquals("识别文本", requireNotNull(selected).text)
    }

    @Test fun visibleLocalStringShadowsGlobalNumberAndImagesRemainUnsupported() {
        val declarations = listOf(
            ProjectVariable("结果", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
            ProjectVariable("结果", ProjectVariableScope.FLOW, flowId = "main", type = ProjectVariableType.STRING),
        )
        val visible = calculationVariables(declarations, "main", emptyList()).single()
        assertEquals("\"结果=\" .. 结果", DebugOutputExpression.variable(field(""), visible, labeled = true)!!.text)
        assertNull(variable(field(""), "图片", type = ProjectVariableType.IMAGE))
    }
}
