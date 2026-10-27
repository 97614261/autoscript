package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType
import com.autoscript.project.store.ProjectImageFilter
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VisionExtensionArgumentsTest {
    @Test fun filterRulesValidateAndSurviveVisualAndRestrictedSourceRoundTrips() {
        val filter=ProjectImageFilter(space="relative",output="keep",invert=true).encode()
        val texts=inputs + mapOf("mode" to "filter","filter" to filter,"threshold" to "128")
        assertNull(visionExtensionArgumentError("screen.transform",texts,variables))
        assertNotNull(visionExtensionArgumentError("screen.transform",texts-"filter",variables))
        assertNotNull(visionExtensionArgumentError("screen.transform",texts+("filter" to "{}"),variables))
        assertNotNull(visionExtensionArgumentError("screen.transform",texts+("mode" to "gray"),variables))
        assertNotNull(visionExtensionArgumentError("screen.transform",texts+("resultVariable" to "frame"),variables))
        val contract=BlockCatalog.find("screen.transform")!!
        val result=parseBlockArguments(contract,texts,null,emptyMap(),emptyList())
        assertNull(result.second)
        val parsed=requireNotNull(result.first)
        assertEquals(filter,parsed["filter"].asString)
        assertEquals(filter,blockPropertyTexts(contract,parsed)["filter"])
        val editor=VisualEditorState.create("","root")
        assertNotNull(editor.insertBlock(contract,parsed))
        val baseline=editor.currentSource
        val exported=pluginSourceToolchain.export(baseline,"root").text
        val restored=pluginSourceToolchain.parse(exported,baseline,"root",emptySet()).text
        assertEquals(parsed,restored.lineSequence().filter(String::isNotBlank).map { JsonParser.parseString(it).asJsonObject }.first { it["kind"].asString=="screen.transform" }["args"])
    }

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
