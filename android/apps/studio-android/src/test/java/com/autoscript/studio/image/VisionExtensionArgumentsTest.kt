package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import org.junit.Assert.*
import org.junit.Test

class VisionExtensionArgumentsTest {
    private val variables = listOf(CalculationVariable("frame", ProjectVariableType.IMAGE, "局部"),
        CalculationVariable("result", ProjectVariableType.IMAGE, "局部"))
    private val inputs = mapOf("frameVariable" to "frame", "resultVariable" to "result",
        "mode" to "threshold", "width" to "0", "height" to "0")

    @Test fun thresholdRequiresIntegerInRangeAndKeepsLegacyDefault() {
        for (value in listOf("0", "128", "255")) {
            assertNull(visionExtensionArgumentError("screen.transform", inputs + ("threshold" to value), variables))
        }
        assertNull(visionExtensionArgumentError("screen.transform", inputs, variables))
        for (value in listOf("", "-1", "256", "1.5", "text")) {
            assertNotNull(visionExtensionArgumentError("screen.transform", inputs + ("threshold" to value), variables))
        }
        assertNotNull(visionExtensionArgumentError("screen.transform", inputs + ("mode" to "unknown"), variables))
        assertNotNull(visionExtensionArgumentError("screen.transform", inputs + ("resultVariable" to "frame"), variables))
    }
}
