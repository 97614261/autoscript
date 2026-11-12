package com.autoscript.studio

import com.autoscript.project.store.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DesignerPropertiesAndBindingsTest {
    private fun field(kind: RunnerUiControlKind) = RunnerUiDesignerDraft().add(kind).fields.single()
    private fun flow(id: String, parameters: String = "[]") = ProjectFlow(id, "flows/$id.jsonl", "root-$id",
        JsonParser.parseString(parameters).asJsonArray.map { it.asJsonObject })
    private fun manifest() = ProjectManifestDocument(projectId = "qa", name = "qa", sourceMode = ProjectSourceMode.VISUAL,
        entryFlowId = "main", flows = listOf(flow("main", """[{"name":"文字","type":"string","required":false},{"name":"数值","type":"number","required":false},{"name":"开关","type":"boolean","required":false}]"""), flow("worker")),
        variables = listOf(ProjectVariable("名称", ProjectVariableScope.GLOBAL, type = ProjectVariableType.STRING),
            ProjectVariable("名称", ProjectVariableScope.FLOW, "worker", ProjectVariableType.STRING),
            ProjectVariable("次数", ProjectVariableScope.GLOBAL, type = ProjectVariableType.INTEGER),
            ProjectVariable("数值", ProjectVariableScope.GLOBAL, type = ProjectVariableType.NUMBER),
            ProjectVariable("图片", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE)))

    @Test fun buttonsDoNotOfferUnreachableChangeOrSelectionAndInputsKeepTheirSupportedClicks() {
        assertEquals(listOf("click", "longClick"), RunnerUiControlKind.BUTTON.designerEvents().map { it.first })
        assertEquals(listOf("change", "click", "longClick"), RunnerUiControlKind.TEXT.designerEvents().map { it.first })
        for (kind in listOf(RunnerUiControlKind.RADIO, RunnerUiControlKind.LIST, RunnerUiControlKind.CHOICE, RunnerUiControlKind.MULTISELECT)) {
            assertTrue(kind.designerEvents().any { it.first == "selection" })
            assertFalse(kind.designerEvents().any { it.first == "change" })
        }
        assertEquals("拖动结束", RunnerUiControlKind.SLIDER.designerEvents().first { it.first == "click" }.second)
        assertFalse(RunnerUiControlKind.PROGRESS.hasTextStyle())
        assertFalse(RunnerUiControlKind.BUTTON.hasRequiredChoice())
        assertTrue(RunnerUiControlKind.MULTISELECT.hasRequiredChoice())
    }

    @Test fun bindingsPreserveScopeIdentityAndFilterTypesIncludingBooleanNumericContract() {
        val draft = RunnerUiDesignerDraft()
        val text = draft.bindingOptions(field(RunnerUiControlKind.TEXT), manifest())
        assertEquals(setOf("global:名称", "flow:worker:名称", "param:文字"), text.map { it.key }.toSet())
        assertEquals(2, text.filter { it.name == "名称" }.map { it.scope }.distinct().size)
        assertTrue(text.first { it.key == "flow:worker:名称" }.summary.contains("worker"))
        val decimal = draft.bindingOptions(field(RunnerUiControlKind.DECIMAL), manifest())
        assertEquals(setOf("global:数值", "param:数值"), decimal.map { it.key }.toSet())
        val toggle = draft.bindingOptions(field(RunnerUiControlKind.SWITCH), manifest())
        assertEquals(setOf("global:次数", "global:数值", "param:开关"), toggle.map { it.key }.toSet())
        assertEquals("布尔", toggle.first { it.key == "param:开关" }.typeLabel)
        assertTrue(toggle.first { it.key == "global:次数" }.typeLabel.endsWith("0/1"))
    }

    @Test fun occupiedBindingsDisappearAndBecomeAvailableWhenReleasedWithoutRewritingMissingBinding() {
        val current = field(RunnerUiControlKind.TEXT).let { it.copy(ui = it.ui.copy(binding = "global:名称")) }
        val other = current.copy(id = "other", ui = current.ui.copy(binding = "param:文字"))
        val draft = RunnerUiDesignerDraft(fields = listOf(current, other))
        val offered = draft.bindingOptions(current, manifest()).map { it.key }
        assertTrue("global:名称" in offered)
        assertFalse("param:文字" in offered)
        assertTrue("param:文字" in draft.remove(1).bindingOptions(current, manifest()).map { it.key })
        assertFalse("global:名称" in draft.bindingOptions(current, manifest().copy(variables = emptyList())).map { it.key })
        assertEquals("global:名称", draft.fields.first().ui.binding)
    }

    @Test fun pluginChoicesAcceptOnlyParametersTheExistingEventContractCanSupply() {
        assertTrue(designerPluginCompatible(RunnerUiControlKind.TEXT, flow("plain")))
        assertTrue(designerPluginCompatible(RunnerUiControlKind.TEXT, flow("text", """[{"name":"value","type":"string","required":true},{"name":"event","type":"string","required":true}]""")))
        assertFalse(designerPluginCompatible(RunnerUiControlKind.TEXT, flow("wrong", """[{"name":"value","type":"integer","required":false}]""")))
        assertFalse(designerPluginCompatible(RunnerUiControlKind.TEXT, flow("missing", """[{"name":"extra","type":"string","required":true}]""")))
        assertTrue(designerPluginCompatible(RunnerUiControlKind.SWITCH, flow("switch", """[{"name":"value","type":"boolean","required":true}]""")))
    }

    @Test fun unsupportedSavedEventsRemainInRoundTripUntilExplicitlyCleared() {
        val source = field(RunnerUiControlKind.BUTTON).let { it.copy(ui = it.ui.copy(events = mapOf("change" to "run", "click" to "close"))) }
        val draft = RunnerUiDesignerDraft(fields = listOf(source))
        assertEquals(draft, runnerUiDesignerDraft(draft.toRunnerUiJson()))
        assertFalse(source.kind.designerEvents().any { it.first == "change" })
        assertEquals("run", draft.fields.single().ui.events["change"])
    }
}
