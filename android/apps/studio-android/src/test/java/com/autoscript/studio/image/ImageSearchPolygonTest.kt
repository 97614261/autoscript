package com.autoscript.studio

import com.autoscript.project.store.ProjectMaskPoint
import com.autoscript.studio.generated.BlockContract
import org.junit.Assert.*
import org.junit.Test

class ImageSearchPolygonTest {
    private val points = listOf(ProjectMaskPoint(0,0),ProjectMaskPoint(4,0),ProjectMaskPoint(0,4))
    private val polygon = "[[0,0],[4,0],[0,4]]"
    @Test fun grayScaledAndFeatureFormsExposeAndValidateTheSameSearchPolygon() {
        val resources=listOf(com.google.gson.JsonObject().apply {addProperty("kind","image");addProperty("path","assets/images/a.png")})
        val (gray,base)=inputs("vision.findgray",resources)
        assertTrue("vision.findgray" in ImageSearchPolygon.kinds)
        assertNull(parseRecognitionArguments(gray,base+mapOf("polygon" to polygon,"region" to "0,0,4,4"),resources).second)
        val variables=listOf(CalculationVariable("frame",com.autoscript.project.store.ProjectVariableType.IMAGE,"global"),
            CalculationVariable("result",com.autoscript.project.store.ProjectVariableType.STRING,"global"))
        for(kind in listOf("vision.findscaled","vision.findfeatures")) {
            assertTrue(kind in ImageSearchPolygon.kinds)
            val (contract,initial)=inputs(kind,resources)
            val values=initial+mapOf("frameVariable" to "frame","resultVariable" to "result","imagePath" to "assets/images/a.png",
                "left" to "0","top" to "0","right" to "4","bottom" to "4","polygon" to polygon)
            assertNull(visionExtensionArgumentError(kind,values,variables))
            assertNotNull(visionExtensionArgumentError(kind,values+("right" to "3"),variables))
            assertEquals(polygon,recognitionPropertyTexts(contract,requireNotNull(parseBlockArguments(contract,values,null,emptyMap(),resources).first))["polygon"])
        }
    }
    private fun inputs(kind: String, resources:List<com.google.gson.JsonObject> = emptyList()): Pair<BlockContract, Map<String,String>> {
        val contract = requireNotNull(BlockCatalog.find(kind))
        val args = requireNotNull(initialBlockArguments(contract, emptyList(), resources, "main"))
        return contract to (recognitionPropertyTexts(contract,args) + mapOf("frameVariable" to "frame",
            "resultVariable" to "result", "foundVariable" to "found", "xVariable" to "x", "yVariable" to "y"))
    }

    @Test fun polygonRejectsCrossingTouchingDegenerateAndOverflowCoordinates() {
        assertEquals(points,ImageSearchPolygon.decode(polygon))
        assertEquals(polygon,ImageSearchPolygon.encode(points))
        assertEquals(points.reversed(),ImageSearchPolygon.decode(ImageSearchPolygon.encode(points.reversed())))
        for (text in listOf("[[0,0],[4,4],[0,4],[4,0]]", "[[0,0],[4,0],[2,0],[0,4]]", "[]",
            "[[0,0],[4294967296,0],[0,4]]", "[[0,0],[9999999999999999999999999999,0],[0,4]]",
            "[[0,0],[4.0,0],[0,4]]", "[[0,0],[4,0],[0,0],[0,4]]")) {
            assertTrue(text,runCatching {ImageSearchPolygon.decode(text)}.isFailure)
        }
        assertEquals(ImageToolCodeGen.Roi(0,0,4,4),ImageSearchPolygon.bounds(points))
    }

    @Test fun selectionKeepsPolygonAndRawCoordinatesAcrossColorModesOnly() {
        val state=ImageToolSelection(box=ImageSearchPolygon.bounds(points),polygon=points)
        assertEquals(points,state.clearedFor(ImageToolMode.COLOR).polygon)
        assertEquals(points,state.clearedFor(ImageToolMode.MULTI_COLOR).polygon)
        assertEquals(points,state.clearedFor(ImageToolMode.REGION).polygon)
        assertTrue(state.clearedFor(ImageToolMode.CROP).polygon.isEmpty())
        assertTrue(state.clearedFor(ImageToolMode.TAP).polygon.isEmpty())
        val selection=ImageToolCodeGen.VisualSelection(mode=ImageToolMode.COLOR,roi=state.box,polygon=points,
            points=listOf(ImageToolCodeGen.PickedPoint(1,1,0xffffff)),tolerance=0,frameWidth=4,frameHeight=4)
        val node=requireNotNull(visualImageToolDraft(selection))
        assertEquals("vision.findcolor",node.first)
        assertEquals(polygon,node.second["polygon"].asString)
        assertEquals(4,node.second.getAsJsonObject("region")["right"].asInt)
    }

    @Test fun polygonRoundTripsThroughFormAndRectangleCaptureClearsIt() {
        val (contract,base)=inputs("vision.countcolor")
        val selected=recognitionSelectionInputs(base + ("leftVariable" to "old"),ImageToolCodeGen.VisualSelection(
            ImageToolMode.REGION,ImageSearchPolygon.bounds(points),emptyList(),0,4,4,polygon=points))
        assertEquals(polygon,selected["polygon"])
        assertEquals("",selected["leftVariable"])
        val parsed=parseRecognitionArguments(contract,selected,emptyList())
        assertNull(parsed.second)
        assertEquals(polygon,parsed.first?.get("polygon")?.asString)
        val roundTrip=recognitionPropertyTexts(contract,requireNotNull(parsed.first))
        assertEquals(polygon,roundTrip["polygon"])
        assertNotNull(parseRecognitionArguments(contract,selected+("region" to "0,0,3,4"),emptyList()).second)
        assertNotNull(parseRecognitionArguments(contract,selected+("leftVariable" to "left"),emptyList()).second)
        val rectangular=recognitionSelectionInputs(selected,ImageToolCodeGen.VisualSelection(
            ImageToolMode.REGION,ImageToolCodeGen.Roi(0,0,2,2),emptyList(),0,4,4))
        assertEquals("",rectangular["polygon"])
    }

    @Test fun polygonLuaSnippetIncludesOptionalArgumentAndKeepsScreenshotPixels() {
        val mapping=ImageToolCodeGen.DesignMapping(8,8,4,4)
        val region=ImageToolCodeGen.region(ImageSearchPolygon.bounds(points),mapping,points)
        assertNull(region.rejection)
        assertTrue(region.code.contains("local polygon = \"[[0,0],[4,0],[0,4]]\""))
        val multi=ImageToolCodeGen.findMultiColor(ImageToolCodeGen.PickedPoint(0,0,0xffffff),
            listOf(ImageToolCodeGen.PickedPoint(1,0,0xffffff)),0,ImageSearchPolygon.bounds(points),mapping,points)
        assertNull(multi.rejection)
        assertTrue(multi.code.contains("0, 0, 4, 4, \"[[0,0],[4,0],[0,4]]\")"))
    }
}
