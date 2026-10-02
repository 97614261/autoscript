package com.autoscript.studio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class VisualVariableCalculationTest {
    @Test fun calculationHintRoundTripsWithoutLosingQuotesOrNewlines() {
        val args = JsonObject().apply { addProperty("name", "text"); addProperty("expression", "\"引号\\\"\\n\" .. text(count)") }
        val hint = variableCalculationHint(args)
        assertEquals("variable.calculate", FunctionCatalog.blockKindOf(hint))
        assertEquals(args, variableCalculationArguments(hint))
        val contract = requireNotNull(BlockCatalog.find("variable.calculate"))
        assertEquals(args, legacyDockBlockArguments(contract, hint, JsonObject()))
        assertNull(variableCalculationArguments("$VARIABLE_CALCULATION_HINT{\"name\":\"x\",\"expression\":\"0\",\"shell\":true}"))
    }

    @Test fun chooserHonorsLocalScopeAndParameterShadowing() {
        val variables = listOf(
            ProjectVariable("same", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING),
            ProjectVariable("same", ProjectVariableScope.FLOW, "main", ProjectVariableType.INTEGER),
            ProjectVariable("otherOnly", ProjectVariableScope.FLOW, "other", ProjectVariableType.INTEGER),
            ProjectVariable("frame", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE),
        )
        val choices = calculationVariables(variables, "main", emptyList())
        assertEquals(ProjectVariableType.INTEGER, choices.single { it.name == "same" }.type)
        assertEquals("局部", choices.single { it.name == "same" }.scope)
        assertFalse(choices.any { it.name == "otherOnly" })
        val param = JsonObject().apply { addProperty("name", "same"); addProperty("type", "number") }
        val flow = ProjectFlow("main", "visual/flows/main.jsonl", "root", listOf(param))
        assertEquals("参数", calculationVariables(variables, "main", listOf(flow)).single { it.name == "same" }.scope)
        param.addProperty("type", "boolean")
        assertFalse(calculationVariables(variables, "main", listOf(flow)).any { it.name == "same" })
    }

    @Test fun expressionToolsInsertAtCursorReplaceSelectionAndRespectUtf8Limit() {
        assertEquals("x + 1", insertCalculationToken(TextFieldValue(" + 1", TextRange(0)), "x").text)
        val replaced = insertCalculationToken(TextFieldValue("count + 1", TextRange(0, 5)), "total")
        assertEquals("total + 1", replaced.text)
        assertEquals(TextRange(5), replaced.selection)
        val full = TextFieldValue("中".repeat(341), TextRange(1023.coerceAtMost(341)))
        assertEquals(full, insertCalculationToken(full, "aa"))
    }

    @Test fun catalogCalculationContractHasRealArguments() {
        val contract = requireNotNull(BlockCatalog.find("variable.calculate"))
        assertEquals(setOf("name", "expression"), contract.properties.map { it.path }.toSet())
        assertTrue(contract.requiredCapabilities.isEmpty())
        assertTrue(contract.childBlocks.isEmpty())
    }

    @Test fun maintenanceReferencesIgnoreTextAndConversions() {
        assertEquals(setOf("count", "total"), calculationReferencedNames("\"count fake\" .. text(count + total)"))
        assertEquals(setOf("e12"), calculationReferencedNames("1e+12 + e12"))
    }

    @Test fun conversionWrapsExpressionOrSelectedOperandInsteadOfAppendingInvalidCode() {
        assertEquals("int(12.7)", wrapCalculationConversion(TextFieldValue("12.7"), "int", "count").text)
        assertEquals("text(count) .. label", wrapCalculationConversion(TextFieldValue("count .. label", TextRange(0, 5)), "text", "").text)
        assertEquals(TextRange(4), wrapCalculationConversion(TextFieldValue(), "int", "").selection)
    }
}
