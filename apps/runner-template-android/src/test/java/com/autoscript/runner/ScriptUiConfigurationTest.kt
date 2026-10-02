package com.autoscript.runner

import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiField
import com.autoscript.script.ui.UiPresentation
import org.junit.Assert.*
import org.junit.Test

class ScriptUiConfigurationTest {
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
