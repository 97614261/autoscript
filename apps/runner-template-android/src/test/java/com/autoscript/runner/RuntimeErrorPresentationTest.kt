package com.autoscript.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeErrorPresentationTest {
    @Test
    fun visualRuntimeLineMapsToFlowAndNodeAfterConfigPrefix() {
        val presentation = runtimeErrorPresentation(
            visualRelease(
                RunnerSourceMapEntry(
                    flowId = "main",
                    nodeId = "node-sleep",
                    luaStartLine = 3,
                    luaEndLine = 3,
                ),
            ),
            "[string \"android-runner\"]:4: synthetic failure",
        )

        assertEquals("Flow main · 节点 node-sleep · Lua第3行", presentation.location)
        assertEquals("[string \"android-runner\"]:4: synthetic failure", presentation.message)
    }

    @Test
    fun luaLineWithoutMatchingSourceEntryStillReportsAdjustedLine() {
        val presentation = runtimeErrorPresentation(
            visualRelease(),
            "main.lua:8: synthetic failure",
        )

        assertEquals("Lua第7行", presentation.location)
    }

    @Test
    fun unrelatedDiagnosticDoesNotInventLocation() {
        val presentation = runtimeErrorPresentation(visualRelease(), "CAPABILITY_DENIED")

        assertNull(presentation.location)
    }

    private fun visualRelease(vararg entries: RunnerSourceMapEntry) = EmbeddedRelease(
        releaseId = "a".repeat(64),
        projectId = "visual-release-smoke",
        displayName = "Visual Release Smoke",
        versionName = "1.0.0",
        runtimeApi = "1.5",
        sourceMode = "visual",
        capabilities = listOf("core.task"),
        luaSource = byteArrayOf(),
        resources = emptyList(),
        designWidth = 720,
        designHeight = 1280,
        scaleMode = 0,
        runnerUi = null,
        sourceMap = RunnerSourceMap(
            generationId = "b".repeat(32),
            entries = entries.toList(),
        ),
    )
}
