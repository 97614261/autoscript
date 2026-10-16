package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VisualRuntimeToolsDialogTest {
    private val variables = listOf(
        CalculationVariable("recording", ProjectVariableType.STRING, "global"),
        CalculationVariable("volume", ProjectVariableType.INTEGER, "global"),
    )

    @Test
    fun recordingLimitsAndDeclaredVariableTypesAreCheckedBeforeAdding() {
        val inputs = mapOf("durationMs" to "1000", "eventBudget" to "128", "resultVariable" to "recording")
        assertNull(runtimeToolArgumentError("input.record", inputs, variables))
        assertNotNull(runtimeToolArgumentError("input.record", inputs + ("durationMs" to "999"), variables))
        assertNotNull(runtimeToolArgumentError("input.record", inputs + ("eventBudget" to "513"), variables))
        assertNotNull(runtimeToolArgumentError("input.record", inputs + ("resultVariable" to "volume"), variables))
        assertNull(runtimeToolArgumentError("input.playrecording", mapOf("recordingVariable" to "recording"), variables))
        assertNotNull(runtimeToolArgumentError("audio.getvolume", mapOf("resultVariable" to "recording"), variables))
    }

    @Test
    fun audioVolumeAndRequiredPathAreChecked() {
        assertNull(runtimeToolArgumentError("audio.setvolume", mapOf("volume" to "100"), variables))
        assertNotNull(runtimeToolArgumentError("audio.setvolume", mapOf("volume" to "101"), variables))
        assertNotNull(runtimeToolArgumentError("audio.play", mapOf("path" to ""), variables))
    }
}
