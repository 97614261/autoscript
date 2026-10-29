package com.autoscript.studio

import com.autoscript.project.store.*
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ColorCountEditingTest {
    private fun arguments(kind: String): JsonObject = requireNotNull(initialBlockArguments(
        requireNotNull(BlockCatalog.find(kind)), emptyList(), emptyList(), "main"))

    @Test fun countAndListsUseTheirOwnLimitsWithoutChangingSavedValues() {
        for ((kind, valid, invalid) in listOf(
            Triple("vision.countcolor", listOf(0, 257, 1_000_000, 64_000_000), listOf(-1, 64_000_001)),
            Triple("vision.findallcolor", listOf(1, 257, 2048), listOf(0, 2049)),
            Triple("vision.findallmulticolor", listOf(1, 256), listOf(0, 257)),
            Triple("vision.components", listOf(1, 256), listOf(0, 257)),
        )) {
            val contract = requireNotNull(BlockCatalog.find(kind))
            val defaults = arguments(kind)
            val inputs = recognitionPropertyTexts(contract, defaults) + mapOf(
                "frameVariable" to "frame", "resultVariable" to "result")
            for (limit in valid) {
                val parsed = parseRecognitionArguments(contract, inputs + ("limit" to "$limit"), emptyList())
                assertNull("$kind $limit: ${parsed.second}", parsed.second)
                assertEquals(limit, parsed.first?.get("limit")?.asInt)
            }
            for (limit in invalid) assertNull("$kind $limit", parseRecognitionArguments(contract,
                inputs + ("limit" to "$limit"), emptyList()).first)
            assertEquals(defaults["limit"].asInt, requireNotNull(parseRecognitionArguments(contract, inputs, emptyList()).first)["limit"].asInt)
            assertTrue(recognitionValuePresets("limit", kind).all { it.toInt() in recognitionLimitRange(kind) })
        }
        assertEquals(0, arguments("vision.countcolor")["limit"].asInt)
    }

    @Test fun freshColorOperationsNeedNoDeclaredScratchButBorrowedImagesDo() {
        val snapshot = ProjectSnapshot(File("."), ProjectManifestDocument(projectId = "test", name = "test",
            sourceMode = ProjectSourceMode.VISUAL, capabilities = listOf("vision.pixel", "screen.capture")),
            luaSource = null, flowSources = emptyMap())
        for (kind in listOf("vision.getcolor", "vision.findcolor", "vision.comparecolor", "vision.findmulticolor", "vision.countcolor", "vision.findallcolor")) {
            val contract = requireNotNull(BlockCatalog.find(kind))
            val args = arguments(kind)
            val fresh = recognitionImageEntryBlocks(contract, args, true)
            assertEquals(listOf(kind), fresh.map { it.first.kind })
            assertTrue(fresh.single().second["autoCapture"].asBoolean)
            assertNull(recognitionFrameVariableError(contract, fresh.single().second, snapshot, "main", true))
            assertNotNull(recognitionProjectError(contract, fresh.single().second,
                snapshot.copy(manifest = snapshot.manifest.copy(capabilities = listOf("vision.pixel"))), "main"))
            assertTrue(requireNotNull(recognitionFrameVariableError(contract, args, snapshot, "main", false)).contains("共用图像"))
            val declared = snapshot.copy(manifest = snapshot.manifest.copy(variables = listOf(
                ProjectVariable("frame", ProjectVariableScope.GLOBAL, type = ProjectVariableType.IMAGE))))
            assertNull(recognitionFrameVariableError(contract, args, declared, "main", false))
            val wrongType = declared.copy(manifest = declared.manifest.copy(variables = listOf(
                ProjectVariable("frame", ProjectVariableScope.FLOW, "main", ProjectVariableType.INTEGER))))
            assertNotNull(recognitionFrameVariableError(contract, args, wrongType, "main", false))
            assertFalse(args["autoCapture"]?.asBoolean ?: false)
        }
    }

    @Test fun fullColorListCanBeReadAtItsLastIndexWithoutExpandingOtherShapes() {
        val vars = listOf(CalculationVariable("points", ProjectVariableType.STRING, "global"),
            CalculationVariable("value", ProjectVariableType.INTEGER, "global"))
        assertEquals(2048, recognitionResultBlocks(RecognitionResultShape.COLORS, "x", 2048,
            "points", "", "value", vars).single().second["index"].asInt)
        assertTrue(runCatching { recognitionResultBlocks(RecognitionResultShape.COMPONENTS, "area", 257,
            "points", "", "value", vars) }.isFailure)
    }
}
