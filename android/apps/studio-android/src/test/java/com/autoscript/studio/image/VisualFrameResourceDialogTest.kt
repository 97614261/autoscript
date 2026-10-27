package com.autoscript.studio
import com.autoscript.project.store.ProjectVariableType
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
class VisualFrameResourceDialogTest {
    private val variables=listOf(CalculationVariable("frame",ProjectVariableType.IMAGE,"global"),CalculationVariable("cropped",ProjectVariableType.IMAGE,"global"),CalculationVariable("series",ProjectVariableType.STRING,"global"))
    @Test fun allResourceBlocksHaveTypedSelectorsAndRealCatalogEntries() {
        frameResourceKinds.forEach {kind ->
            val contract=requireNotNull(BlockCatalog.find(kind))
            val inputs=blockPropertyTexts(contract,JsonObject()).let {if(kind=="screen.frameinfo") it+mapOf("frameVariable" to "frame","resultVariable" to "series") else it}
            if (kind == "screen.loadimage") return@forEach
            assertNull(kind,frameResourceArgumentError(kind,inputs,variables))
            assertNotNull(parseBlockArguments(contract,inputs,null,emptyMap(),emptyList()).first)
            assertTrue(FunctionCatalog.visualGroups().flatMap {it.entries}.any {it.blockKind==kind})
        }
    }
    @Test fun imageLoadingUsesDeclaredResourceAndImageOutputWithoutHiddenCapture() {
        val contract = requireNotNull(BlockCatalog.find("screen.loadimage"))
        val inputs = mapOf("imagePath" to "assets/images/source.png", "resultVariable" to "frame")
        val resources = listOf(JsonObject().apply { addProperty("kind", "image"); addProperty("path", inputs.getValue("imagePath")) })
        assertNull(frameResourceArgumentError(contract.kind, inputs, variables))
        assertNotNull(parseBlockArguments(contract, inputs, null, emptyMap(), resources).first)
        assertNull(parseBlockArguments(contract, inputs, null, emptyMap(), emptyList()).first)
        assertNotNull(frameResourceArgumentError(contract.kind, inputs + ("resultVariable" to "series"), variables))
        assertEquals(setOf("vision.template"), contract.requiredCapabilities)
    }
    @Test fun wrongSourceTypeSameCropOutputAndInvalidBoundsAreBlocked() {
        val crop=blockPropertyTexts(requireNotNull(BlockCatalog.find("screen.crop")),JsonObject())
        for(change in listOf("frameVariable" to "series","resultVariable" to "frame","left" to "-1","right" to "0")) assertNotNull(frameResourceArgumentError("screen.crop",crop+change,variables))
        val series=blockPropertyTexts(requireNotNull(BlockCatalog.find("screen.series")),JsonObject())
        assertNotNull(frameResourceArgumentError("screen.series",series+("maxFrames" to "121"),variables))
        assertNotNull(frameResourceArgumentError("screen.series",series+("allowPartial" to "1"),variables))
    }
    @Test fun explicitSharedFrameCaptureAndReleaseRequireDeclaredImageVariables() {
        for(kind in listOf("screen.capture","screen.release")) {
            val key=if(kind=="screen.capture") "resultVariable" else "frameVariable"
            assertNull(frameResourceArgumentError(kind,mapOf(key to "frame"),variables))
            assertNotNull(frameResourceArgumentError(kind,mapOf(key to "series"),variables))
            assertNotNull(frameResourceArgumentError(kind,mapOf(key to "missing"),variables))
        }
    }
    @Test fun regionCaptureNeedsOnlyExplicitBoundsAndDeclaredImage() {
        val contract=requireNotNull(BlockCatalog.find("screen.captureregion"))
        val inputs=blockPropertyTexts(contract,JsonObject())
        assertNull(frameResourceArgumentError(contract.kind,inputs,variables))
        for(change in listOf("resultVariable" to "series","left" to "-1","right" to "0","bottom" to "16385"))
            assertNotNull(frameResourceArgumentError(contract.kind,inputs+change,variables))
        assertEquals(setOf("screen.capture"),contract.requiredCapabilities)
        assertTrue(contract.properties.none {it.path=="frameVariable" || it.path=="sourceVariable"})
    }
}
