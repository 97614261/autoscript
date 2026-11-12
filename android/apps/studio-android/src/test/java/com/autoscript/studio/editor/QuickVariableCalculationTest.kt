package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import org.junit.Assert.*
import org.junit.Test

class QuickVariableCalculationTest {
    private val variables = listOf(CalculationVariable("次数", ProjectVariableType.INTEGER, "局部"),
        CalculationVariable("结果列表", ProjectVariableType.STRING, "全局"), CalculationVariable("小数", ProjectVariableType.NUMBER, "局部"))

    @Test fun everyShortcutSurvivesExpressionOnlySaveAndReopen() {
        quickCalculationOperations.forEach { operation ->
            var state = newQuickCalculationState(operation.id)
            if (operation.id in setOf("find", "replace")) state = state.copy(operands = state.operands.toMutableList().apply { set(1, CalculationOperand("查找")) })
            val result = quickCalculationResult(state, variables)
            assertNull("${operation.id}: ${result.error}", result.error)
            val reopened = decodeQuickCalculation(requireNotNull(result.expression), variables)
            assertNotNull("${operation.id}: ${result.expression}", reopened)
            assertEquals(result, quickCalculationResult(requireNotNull(reopened), variables))
        }
    }

    @Test fun conversionAndCollectionShortcutsKeepTypesDefaultsAndChineseNames() {
        val converted = QuickCalculationState("int", listOf(CalculationOperand(" +190 ")), fallback = CalculationOperand("-1", type = ProjectVariableType.INTEGER))
        assertEquals("intOr(\" +190 \",-1)", quickCalculationResult(converted, variables).expression)
        val item = QuickCalculationState("itemInt", listOf(CalculationOperand(variable = "结果列表"), CalculationOperand(variable = "次数")),
            fallback = CalculationOperand("0", type = ProjectVariableType.INTEGER))
        val result = quickCalculationResult(item, variables)
        assertEquals("itemInt(结果列表,次数,0)", result.expression)
        assertEquals(ProjectVariableType.INTEGER, result.type)
        assertEquals(result, quickCalculationResult(requireNotNull(decodeQuickCalculation(result.expression!!, variables)), variables))
        val field = item.copy(operands = listOf(CalculationOperand(variable = "结果列表"), CalculationOperand("a.b[1]")))
        assertEquals("itemInt(结果列表,\"a.b[1]\",0)", quickCalculationResult(field, variables).expression)
    }

    @Test fun splitPreservesLiteralSeparatorsAndOptionalCleaning() {
        val state = QuickCalculationState("splitAny", listOf(CalculationOperand("100, 200；300\n400"), CalculationOperand(",；\n")), trim = true, skipEmpty = true)
        val expression = requireNotNull(quickCalculationResult(state, variables).expression)
        assertEquals("splitAny(\"100, 200；300\\n400\",\",；\\n\",3)", expression)
        assertEquals(state, decodeQuickCalculation(expression, variables))
        assertNull(decodeQuickCalculation("splitAny(\"a\",\",\",9)", variables))
    }

    @Test fun quotesBackslashesCommasAndOperatorsRemainData() {
        listOf("a,b(中) + x", "\"; os.execute(\"x\")", "\\\n\t\u0000", "<>&").forEach { text ->
            val state = QuickCalculationState(operands = listOf(CalculationOperand(text)))
            val expression = quickCalculationResult(state, variables).expression!!
            assertEquals(state, decodeQuickCalculation(expression, variables))
        }
        assertNull(decodeQuickCalculation("次数 + (2 * 3)", variables))
        assertNull(decodeQuickCalculation("unknown(次数)", variables))
    }

    @Test fun rejectsZeroDivisorsOverflowBadIndexesAndInaccessibleVariables() {
        listOf("/", "//", "%", "idiv", "irem").forEach { operation ->
            val state = newQuickCalculationState(operation).copy(operands = listOf(CalculationOperand("1", type = ProjectVariableType.INTEGER), CalculationOperand("0", type = ProjectVariableType.INTEGER)))
            assertTrue(quickCalculationResult(state, variables).error!!.contains("除数"))
        }
        val index = QuickCalculationState("itemText", listOf(CalculationOperand("[]"), CalculationOperand("0", type = ProjectVariableType.INTEGER)))
        assertTrue(quickCalculationResult(index, variables).error!!.contains("从1"))
        assertNotNull(quickCalculationResult(index.copy(operands = listOf(CalculationOperand("[]"), CalculationOperand("1.5", type = ProjectVariableType.NUMBER))), variables).error)
        val huge = QuickCalculationState(operands = listOf(CalculationOperand("9223372036854775808", type = ProjectVariableType.INTEGER)))
        assertNotNull(quickCalculationResult(huge, variables).error)
        assertNotNull(quickCalculationResult(huge.copy(operands = listOf(CalculationOperand(variable = "其他插件变量"))), variables).error)
        assertNotNull(quickCalculationResult(QuickCalculationState(operands = listOf(CalculationOperand("中".repeat(342)))), variables).error)
    }

    @Test fun switchingOperationsPreservesCompatibleSourcesAndResetsIncompatibleInputs() {
        val state = QuickCalculationState("int", listOf(CalculationOperand(variable = "结果列表")))
        assertEquals("结果列表", newQuickCalculationState("split", state, variables).operands[0].variable)
        assertEquals("", newQuickCalculationState("+", state, variables).operands[0].variable)
        val floating = quickCalculationResult(QuickCalculationState("+", listOf(CalculationOperand(variable = "小数"), CalculationOperand(variable = "次数"))), variables)
        assertEquals(ProjectVariableType.NUMBER, floating.type)
        assertFalse(calculationInputAccepts(CalculationInputKind.NUMERIC, ProjectVariableType.STRING))
        assertFalse(calculationInputAccepts(CalculationInputKind.SCALAR, ProjectVariableType.IMAGE))
    }
}
