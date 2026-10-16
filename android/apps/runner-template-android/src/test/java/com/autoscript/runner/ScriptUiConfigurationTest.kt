package com.autoscript.runner

import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.UiPresentation
import org.junit.Assert.*
import org.junit.Test

class ScriptUiConfigurationTest {
    @Test fun decimalReleaseDigestDoesNotDependOnNumericPrinting() {
        assertEquals("3ff4000000000000", uiNumberDigest("1.25"))
        assertEquals(uiNumberDigest("0.0000001"), uiNumberDigest("1E-7"))
        assertEquals("0000000000000000", uiNumberDigest("-0.0"))
    }
    @Test fun extendedControlsUseTypedSharedStartupAndRejectMalformedValues() {
        val model = ScriptUiDefinition(version = 2, fields = listOf(
            UiField("number", "小数", "number", true, "1.25", -2, 2, ui = UiPresentation(control = "DECIMAL", binding = "global:number")),
            UiField("multi", "多选", "multiChoice", true, "[\"a,b\"]", options = listOf("a,b", "c"), ui = UiPresentation(control = "MULTISELECT", binding = "global:multi"))))
        val extended = release().copy(runnerUi = RunnerUiDefinition(null, emptyList(), script = model))
        val lua = buildRuntimeLua(extended, mapOf("number" to RunnerConfigDraftValue.Text("-0.5"), "multi" to RunnerConfigDraftValue.Text("[\"a,b\",\"c\"]"))).toString(Charsets.UTF_8)
        assertTrue(lua.contains("[\"number\"]=-0.5"))
        assertTrue(lua.contains("a,b"))
        assertTrue(lua.endsWith("return function() end\n"))
        assertThrows(IllegalArgumentException::class.java) { buildRuntimeLua(extended, mapOf("number" to RunnerConfigDraftValue.Text("NaN"))) }
        assertThrows(IllegalArgumentException::class.java) { buildRuntimeLua(extended, mapOf("multi" to RunnerConfigDraftValue.Text("[]"))) }
    }
    private fun release() = EmbeddedRelease(
        releaseId = "release", projectId = "project", displayName = "测试", versionName = "1.0",
        runtimeApi = "1.7", sourceMode = "lua", capabilities = listOf("ui.control"),
        luaSource = "return function() end\n".toByteArray(), resources = emptyList(),
        designWidth = 720, designHeight = 1280, scaleMode = 0, sourceMap = null,
        runnerUi = RunnerUiDefinition(null, emptyList(), script = ScriptUiDefinition(version = 2, fields = listOf(
            UiField("count", "次数", "integer", true, "3", 1, 100, ui = UiPresentation(control = "NUMBER", binding = "global:count")),
            UiField("text", "文本", "text", true, "初始", ui = UiPresentation()),
        ))),
    )
    @Test fun runnerUsesSharedTypedConfigurationWithoutLosingFrozenLua() {
        val lua = buildRuntimeLua(release(), mapOf("count" to RunnerConfigDraftValue.Text("12"), "text" to RunnerConfigDraftValue.Text("\";bad()"))).toString(Charsets.UTF_8)
        assertTrue(lua.contains("[\"count\"]=12"))
        assertTrue(lua.contains("__ui_initial_globals"))
        assertTrue(lua.contains("\\\";bad()"))
        assertTrue(lua.endsWith("return function() end\n"))
    }
    @Test fun invalidConfigurationCannotReachScriptStart() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRuntimeLua(release(), mapOf("count" to RunnerConfigDraftValue.Text("0")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildRuntimeLua(release(), mapOf("text" to RunnerConfigDraftValue.Text("")))
        }
    }
}
