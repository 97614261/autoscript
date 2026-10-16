package com.autoscript.studio

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CompiledVisionRequirementsTest {
    private fun parse(body: String) = compiledVisionRequirements(JsonParser.parseString(body).asJsonObject)

    @Test fun unknownAndKnownEmptyAreDistinct() {
        assertNull(parse("{}"))
        assertEquals(emptySet<String>(), parse("""{"visionRequirements":{"version":1,"components":[]}}"""))
    }

    @Test fun acceptsOnlyFixedCanonicalComponents() {
        assertEquals(setOf("opencv", "onnx"), parse("""{"visionRequirements":{"version":1,"components":["opencv","onnx"]}}"""))
        assertEquals(setOf("onnx"), parse("""{"visionRequirements":{"version":1,"components":["onnx"]}}"""))
    }

    @Test fun rejectsMalformedUnknownDuplicateOrNoncanonicalMetadata() {
        for (body in listOf("null", "[]", "{}",
            """{"version":2,"components":[]}""",
            """{"version":"1","components":[]}""",
            """{"version":1.0,"components":[]}""",
            """{"version":1,"components":["external"]}""",
            """{"version":1,"components":["onnx","opencv"]}""",
            """{"version":1,"components":["opencv","opencv"]}""",
            """{"version":1,"components":[1]}""",
            """{"version":1,"components":[],"path":"untrusted"}""")) {
            assertThrows(IllegalArgumentException::class.java) { parse("{\"visionRequirements\":$body}") }
        }
    }
}
