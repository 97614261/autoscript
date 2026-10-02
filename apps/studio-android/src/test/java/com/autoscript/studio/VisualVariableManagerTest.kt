package com.autoscript.studio

import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectVariable
import com.autoscript.project.store.ProjectVariableScope
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class VisualVariableManagerTest {
    private fun local(name: String, type: ProjectVariableType = ProjectVariableType.INTEGER, flow: String = "main") =
        ProjectVariable(name, ProjectVariableScope.FLOW, flow, type)

    @Test fun panelGrowsWithContentAndStopsAtSixRows() {
        assertEquals(223f, variableManagerPanelHeightDp(1, 800f), 0f)
        assertEquals(264f, variableManagerPanelHeightDp(2, 800f), 0f)
        assertEquals(428f, variableManagerPanelHeightDp(6, 800f), 0f)
        assertEquals(428f, variableManagerPanelHeightDp(1000, 800f), 0f)
        assertEquals(278f, variableManagerPanelHeightDp(0, 800f), 0f)
        assertEquals(300f, variableManagerPanelHeightDp(10, 300f), 0f)
        assertEquals(263f, variableManagerPanelHeightDp(1, 800f, hasNotice = true), 0f)
    }

    @Test fun scopeAndNameSearchDoNotLeakAnotherPlugin() {
        val vars = listOf(local("total"), local("Count"), local("ratio", ProjectVariableType.NUMBER),
            local("count", flow = "other"), ProjectVariable("count", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING))
        assertEquals(listOf("Count", "ratio", "total"), variableManagerEntries(vars, ProjectVariableScope.FLOW, "main").map { it.name })
        assertEquals(listOf("Count"), variableManagerEntries(vars, ProjectVariableScope.FLOW, "main", "  coUN  ").map { it.name })
        assertTrue(variableManagerEntries(vars, ProjectVariableScope.FLOW, "main", "missing").isEmpty())
        assertEquals(ProjectVariableType.STRING, variableManagerEntries(vars, ProjectVariableScope.GLOBAL, "main").single().type)
        assertTrue(variableManagerEntries(vars, ProjectVariableScope.FLOW, "missing").isEmpty())
    }

    @Test fun nameSearchKeepsEveryMatchingTypeVisibleWithoutAHiddenTypeFilter() {
        val vars = listOf(local("itemInt"), local("itemFloat", ProjectVariableType.NUMBER),
            local("itemText", ProjectVariableType.STRING), local("itemImage", ProjectVariableType.IMAGE))
        val entries = variableManagerEntries(vars, ProjectVariableScope.FLOW, "main", "item")
        assertEquals(4, entries.size)
        assertEquals(ProjectVariableType.entries.toSet(), entries.map { it.type }.toSet())
        assertEquals(listOf("itemFloat", "itemImage", "itemInt", "itemText"), entries.map { it.name })
    }

    @Test fun editingSelfAndSameNameInAnotherScopeStayCompatible() {
        val old = local("count")
        val vars = listOf(old, local("count", flow = "other"), ProjectVariable("count", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING))
        assertNull(variableDeclarationError(old.copy(type = ProjectVariableType.NUMBER), vars, old))
        assertNotNull(variableDeclarationError(old, vars, null))
        assertNull(variableDeclarationError(local("newCount"), vars, null))
        assertNotNull(variableDeclarationError(local("invalid name"), vars, null))
        assertNotNull(variableDeclarationError(local("x", flow = ""), vars, null))
        assertNotNull(variableDeclarationError(local("x".repeat(65)), vars, null))
        assertNull(variableDeclarationError(local("x".repeat(64)), vars, null))
    }

    @Test fun calculationKeepsDeclarationVisibleButRejectsInaccessibleShadowedGlobal() {
        val global = ProjectVariable("count", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER)
        val vars = listOf(global, local("count"))
        assertEquals(listOf(global), variableManagerEntries(vars, ProjectVariableScope.GLOBAL, "main"))
        assertNotNull(variableCalculationUnavailableReason(global, vars, "main", emptyList()))
        assertNull(variableCalculationUnavailableReason(vars[1], vars, "main", emptyList()))
        assertNull(variableCalculationUnavailableReason(global, vars, "other", emptyList()))
    }

    @Test fun imageAndParameterReferencesAreNotPresentedAsScalarDeclarationAssignments() {
        val variable = local("count")
        val param = JsonObject().apply { addProperty("name", "count"); addProperty("type", "integer") }
        val flow = ProjectFlow("main", "visual/flows/main.jsonl", "root", listOf(param))
        assertNotNull(variableCalculationUnavailableReason(variable, listOf(variable), "main", listOf(flow)))
        assertNotNull(variableCalculationUnavailableReason(local("frame", ProjectVariableType.IMAGE), emptyList(), "main", emptyList()))
        assertNull(variableCalculationUnavailableReason(local("text", ProjectVariableType.STRING), emptyList(), "main", emptyList()))
    }
}
