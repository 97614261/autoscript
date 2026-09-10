package com.autoscript.studio

import com.autoscript.studio.generated.BlockCategory
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockMigrationContract
import com.autoscript.studio.generated.BlockResourceKind
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockCatalogTest {
    @Test
    fun generatedCatalogIsUniqueSearchableAndBoundToNodeSchemas() {
        assertEquals(21, BlockCatalog.all.size)
        assertEquals(BlockCatalog.all.size, BlockCatalog.all.map { it.kind }.distinct().size)
        assertTrue(BlockCatalog.all.all { it.nodeSchemaId.startsWith("https://autoscript.local/schema/node/") })
        assertEquals("flow.call", BlockCatalog.search("调用 flow", emptySet()).single().contract.kind)
        assertEquals(
            listOf("task.noop"),
            BlockCatalog.search("占位", emptySet(), BlockCategory.TASK).map { it.contract.kind },
        )
        assertEquals(
            setOf("input.keyevent", "input.swipe", "input.tap"),
            BlockCatalog.search("", emptySet(), BlockCategory.TASK)
                .filter { it.missingCapabilities == setOf("input.basic") }
                .map { it.contract.kind }
                .toSet(),
        )
        assertEquals(
            "1000",
            BlockCatalog.find("task.sleep")?.properties?.single()?.defaultValue,
        )
        assertEquals(
            setOf("control.if", "control.repeat", "control.while"),
            BlockCatalog.search("", emptySet(), BlockCategory.CONTROL)
                .map { it.contract.kind }
                .toSet(),
        )
        assertEquals(
            setOf("screen.capture", "screen.release"),
            BlockCatalog.search("", emptySet(), BlockCategory.SCREEN)
                .map { it.contract.kind }
                .toSet(),
        )
        assertEquals(
            setOf(
                "vision.getcolor",
                "vision.findcolor",
                "vision.comparecolor",
                "vision.findmulticolor",
                "vision.countcolor",
                "vision.findallcolor",
                "vision.findimage",
            ),
            BlockCatalog.search("", emptySet(), BlockCategory.VISION)
                .map { it.contract.kind }
                .toSet(),
        )
        assertEquals(
            setOf("ocr.glyph"),
            BlockCatalog.search("", emptySet(), BlockCategory.OCR)
                .map { it.contract.kind }
                .toSet(),
        )
        assertEquals(
            BlockResourceKind.IMAGE,
            BlockCatalog.find("vision.findimage")?.properties
                ?.single { it.path == "imagePath" }
                ?.resourceKind,
        )
        assertEquals(
            BlockResourceKind.GLYPH_DICTIONARY,
            BlockCatalog.find("ocr.glyph")?.properties
                ?.single { it.path == "dictionaryPath" }
                ?.resourceKind,
        )
    }

    @Test
    fun migrationRenamesArgumentsAndRejectsConflictingData() {
        val contract = BlockContract(
            kind = "task.legacy",
            nodeVersion = 2,
            nodeSchemaId = "https://autoscript.local/schema/node/task.legacy/2/schema.json",
            title = "旧积木",
            category = BlockCategory.TASK,
            summary = "test",
            searchTerms = emptyList(),
            requiredCapabilities = emptySet(),
            childBlocks = emptyList(),
            properties = emptyList(),
            migrations = listOf(BlockMigrationContract(1, 2, mapOf("old" to "value"))),
        )
        val old = JsonParser.parseString(
            """{"kind":"task.legacy","nodeVersion":1,"args":{"old":7}}""",
        ).asJsonObject

        val migrated = BlockMigrationEngine.migrateNode(old, listOf(contract))

        assertTrue(migrated is NodeMigrationResult.Current)
        migrated as NodeMigrationResult.Current
        assertTrue(migrated.changed)
        assertEquals(2, migrated.node.get("nodeVersion").asInt)
        assertEquals(7, migrated.node.getAsJsonObject("args").get("value").asInt)
        assertFalse(migrated.node.getAsJsonObject("args").has("old"))

        old.getAsJsonObject("args").addProperty("value", 8)
        assertTrue(
            BlockMigrationEngine.migrateNode(old, listOf(contract)) is NodeMigrationResult.Unsupported,
        )
    }

    @Test
    fun currentFlowMigrationPreservesExactBytes() {
        val source = "{\"nodeId\":\"n\",\"blockId\":\"b\",\"parentId\":null," +
            "\"orderKey\":\"a0\",\"kind\":\"task.noop\",\"nodeVersion\":1," +
            "\"depth\":0,\"args\":{}}\r\n"

        val result = BlockMigrationEngine.migrateFlowSource(source)

        assertEquals(FlowMigrationResult.Success(source, false), result)
    }

    @Test
    fun visualPropertyEditorsParseStrictValuesAndFilterTypedResources() {
        assertEquals(12, parsePoint("12,-3")?.get("x")?.asInt)
        assertEquals(-3, parsePoint("12,-3")?.get("y")?.asInt)
        assertEquals(null, parsePoint("12"))
        assertEquals(30, parseRect("1,2,30,40")?.get("right")?.asInt)
        assertEquals(null, parseRect("1,2,1,40"))
        assertEquals(0xA0B1C2, parseColor("#A0B1C2"))
        assertEquals(0xA0B1C2, parseColor("0xa0b1c2"))
        assertEquals("#A0B1C2", formatColor(0xA0B1C2))
        assertEquals(null, parseColor("#12345"))
        val samples = parseMultiColorSamples("1,-2,#A0B1C2,3; -4,5,0x010203,255")
        assertEquals(2, samples?.size())
        assertEquals(-2, samples?.get(0)?.asJsonObject?.get("y")?.asInt)
        assertEquals(0x010203, samples?.get(1)?.asJsonObject?.get("rgb")?.asInt)
        assertEquals(null, parseMultiColorSamples("1,2,#FFFFFF,256"))
        assertEquals(null, parseMultiColorSamples("1,2,#FFFFFF,0;"))
        val resources = listOf(
            JsonParser.parseString(
                """{"kind":"image","path":"assets/images/button.png"}""",
            ).asJsonObject,
            JsonParser.parseString(
                """{"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}""",
            ).asJsonObject,
        )
        assertEquals(
            listOf("assets/images/button.png"),
            resourcePaths(resources, BlockResourceKind.IMAGE),
        )
        assertEquals(
            listOf("dictionaries/main.asglyph"),
            resourcePaths(resources, BlockResourceKind.GLYPH_DICTIONARY),
        )
    }
}
