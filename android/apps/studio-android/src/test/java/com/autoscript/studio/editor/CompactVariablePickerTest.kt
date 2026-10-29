package com.autoscript.studio

import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CompactVariablePickerTest {
    @Test fun annotationsKeepCallerEligibilityAndOrder() {
        val variables = listOf(CalculationVariable("次数", ProjectVariableType.INTEGER, "局部"),
            CalculationVariable("图像", ProjectVariableType.IMAGE, "全局"))
        val options = variablePickerOptions(listOf("未声明", "次数", "次数"), variables)
        assertEquals(listOf("未声明", "次数"), options.map { it.value })
        assertNull(options[0].type)
        assertNull(options[0].scope)
        assertEquals(ProjectVariableType.INTEGER, options[1].type)
        assertEquals("局部", options[1].scope)
        assertTrue(options.all { it.enabled })
    }

    @Test fun resolvedMetadataKeepsParameterShadowingAndOtherFlowPrivate() {
        val variables = listOf(ProjectVariable("相同", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING),
            ProjectVariable("相同", ProjectVariableScope.FLOW, "main", ProjectVariableType.INTEGER),
            ProjectVariable("其他插件", ProjectVariableScope.FLOW, "other", ProjectVariableType.INTEGER))
        val parameter = JsonObject().apply { addProperty("name", "相同"); addProperty("type", "number") }
        val metadata = calculationVariables(variables, "main", listOf(ProjectFlow("main", "visual/flows/main.jsonl", "root", listOf(parameter))))
        val options = variablePickerOptions(metadata.map { it.name }, metadata)
        assertEquals(1, options.size)
        assertEquals("参数", options.single().scope)
        assertEquals(ProjectVariableType.NUMBER, options.single().type)
    }

    @Test fun searchAndScopePreserveFixedChoiceAndDisabledState() {
        val fixed = VariablePickerOption("", "固定值")
        val local = VariablePickerOption("local", "Local次数", "局部", ProjectVariableType.INTEGER)
        val global = VariablePickerOption("global", "Global次数", "全局", ProjectVariableType.INTEGER, enabled = false)
        val options = listOf(fixed, local, global)
        assertEquals(listOf(fixed, local), filteredVariablePickerOptions(options, "局部", ""))
        assertEquals(listOf(global), filteredVariablePickerOptions(options, "全局", "  gLoBaL  "))
        assertEquals(listOf(fixed), filteredVariablePickerOptions(options, "参数", "固定"))
        assertEquals(options, filteredVariablePickerOptions(options, "", ""))
        assertTrue(filteredVariablePickerOptions(options, "局部", "global").isEmpty())
        assertFalse(filteredVariablePickerOptions(options, "全局", "次数").single().enabled)
    }
}
