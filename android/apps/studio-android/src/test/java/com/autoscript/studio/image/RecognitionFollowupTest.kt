package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType as Type
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RecognitionFollowupTest {
    private fun v(name:String,type:Type=Type.INTEGER)=CalculationVariable(name,type,"局部")
    private val work=listOf("start","clock","elapsed","attempt","outcome","absentStart","absentElapsed","match").map { v(it) } +
        listOf(v("frame",Type.IMAGE),v("points",Type.STRING),v("text",Type.STRING),v("details",Type.STRING),v("targetMatches",Type.STRING),v("x"),v("y"),v("coverage"),v("score"))
    private val draft=RecognitionWaitDraft(start="start",clock="clock",elapsed="elapsed",attempt="attempt",outcome="outcome",
        absentStart="absentStart",absentElapsed="absentElapsed",match="match",sampleResult="points",targetText="AB",
        glyphDetails="details",glyphMatches="targetMatches")
    @Test fun colorWaitUsesExplicitCollectionInsteadOfChangingLegacyBooleanOutputs() {
        val args=JsonParser.parseString("""{"frameVariable":"frame","rgb":16777215,"tolerance":0,"region":{"left":0,"top":0,"right":20,"bottom":20},"foundVariable":"oldFound","xVariable":"x","yVariable":"y"}""").asJsonObject
        val all=recognitionWaitBlocks(requireNotNull(BlockCatalog.find("vision.findcolor")),args,draft,work).flatMap { it.flattened() }
        assertFalse(all.any { it.contract.kind=="vision.findcolor" })
        assertEquals(listOf("screen.capture","vision.findallcolor","screen.release"),all.filter { it.contract.kind in setOf("screen.capture","vision.findallcolor","screen.release") }.map { it.contract.kind })
        assertTrue(all.any { it.contract.kind=="vision.readresult" && it.arguments["resultVariable"].asString=="match" })
        assertTrue(all.none { it.arguments["resultVariable"]?.asString=="oldFound" })
    }
    @Test fun ocrWaitUsesRealTextAndExactOrContainsPredicate() {
        val args=JsonParser.parseString("""{"frameVariable":"frame","textVariable":"text","scoreVariable":"score","minimumConfidencePermille":500,"region":{"left":0,"top":0,"right":20,"bottom":20},"autoCapture":true}""").asJsonObject
        val contract=requireNotNull(BlockCatalog.find("ocr.alphanumeric"))
        val contains=recognitionWaitBlocks(contract,args,draft,work).flatMap { it.flattened() }
        assertEquals("AB",contains.single { it.contract.kind=="text.contains" }.arguments["needle"].asString)
        val exact=recognitionWaitBlocks(contract,args,draft.copy(exactText=true),work).flatMap { it.flattened() }
        assertTrue(exact.any { it.contract.kind=="control.if" && it.arguments["variable"].asString=="text" && it.arguments["value"].asString=="AB" })
        assertTrue(recognitionWaitBlocks(contract,args,draft.copy(targetText=""),work).flatMap { it.flattened() }.any { it.contract.kind=="text.length" })
    }
    @Test fun automaticWorkSelectionExcludesRecognitionOutputsAndRequiresEnoughDeclarations() {
        val suggested=requireNotNull(recognitionVariableSuggestion("vision.findimage",draft,work,setOf("x","y","score","coverage",draft.match)))
        val names=listOf(suggested.start,suggested.clock,suggested.elapsed,suggested.attempt,suggested.outcome)
        assertEquals(names.size,names.distinct().size)
        assertFalse(names.any { it in setOf("match","x","y","score","coverage") })
        assertNull(recognitionVariableSuggestion("vision.findimage",draft,listOf(v("one")),setOf(draft.match)))
    }
    @Test fun pointTablePreservesInvalidNumbersAndSupportsHexColorsWithoutTruncation() {
        val raw="""[{"dx":0,"dy":0,"rgb":0,"tolerance":0}]"""
        assertEquals(16711680,JsonParser.parseString(updateVisionSample(raw,0,"rgb","#FF0000")).asJsonArray[0].asJsonObject["rgb"].asInt)
        val invalid=JsonParser.parseString(updateVisionSample(raw,0,"dx","-")).asJsonArray[0].asJsonObject["dx"]
        assertTrue(invalid.asJsonPrimitive.isString);assertEquals("-",invalid.asString)
    }
    @Test fun glyphPreviewRequiresRealBoundedNumbersAndDoesNotInventCharacterLocations() {
        val raw="""{"text":"A","coveragePermille":1000,"averageScorePermille":900,"matches":[{"text":"A","left":1,"top":2,"right":3,"bottom":4,"score":900,"recognized":true}]}"""
        assertEquals(1,requireNotNull(decodeOcrPreviewDetails(raw,10,10)).glyphs.single().left)
        assertNull(decodeOcrPreviewDetails(raw.replace("\"left\":1","\"left\":4294967297"),10,10))
        assertNull(decodeOcrPreviewDetails(raw,2,2))
        assertNull(decodeOcrPreviewDetails(raw.replace("\"recognized\":true","\"recognized\":1"),10,10))
        assertTrue(recognitionReplyOperationMatches(com.autoscript.runtime.api.RecognitionPreviewReply.GLYPH,com.autoscript.runtime.api.RecognitionPreviewReply.GLYPH_DETAILS))
    }

    private fun glyphArgs()=JsonParser.parseString("""{"frameVariable":"frame","textVariable":"text","scoreVariable":"score","coverageVariable":"coverage","autoCapture":true,"dictionaryPath":"dictionaries/main.asglyph","foregroundRgb":16777215,"tolerance":0,"similarityPermille":900,"spaceGapColumns":2,"region":{"left":0,"top":0,"right":20,"bottom":20}}""").asJsonObject
    @Test fun glyphWaitGatesUnknownPlaceholdersWithoutRewritingOriginalTextOrScore() {
        val contract=requireNotNull(BlockCatalog.find("ocr.glyph"))
        for(target in listOf("","AB","?")) {
            val all=recognitionWaitBlocks(contract,glyphArgs(),draft.copy(targetText=target,exactText=true),work).flatMap { it.flattened() }
            val gate=all.single { it.contract.kind=="control.if" && it.arguments["variable"].asString=="coverage" }
            assertEquals(if(target.isEmpty()) "greaterThan" else "equals",gate.arguments["operator"].asString)
            assertEquals(if(target.isEmpty()) 0 else 1000,gate.arguments["value"].asInt)
            assertEquals("0",gate.children.getValue("else").single().arguments["expression"].asString)
            assertFalse(all.single { it.contract.kind=="ocr.glyph" }.arguments.has("autoCapture"))
            assertFalse(all.filter { it.contract.kind=="variable.calculate" }.any { it.arguments["name"].asString in setOf("text","score","coverage") })
        }
        for(conflict in listOf("score","coverage","start")) assertThrows(IllegalArgumentException::class.java) {
            recognitionWaitBlocks(contract,glyphArgs(),draft.copy(match=conflict),work)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recognitionWaitBlocks(contract,glyphArgs().apply { addProperty("coverageVariable","score") },draft,work)
        }
    }
    @Test fun glyphContainsUsesOneFrameOfRealDetailsAndPreservesAllOriginalOutputs() {
        val all=recognitionWaitBlocks(requireNotNull(BlockCatalog.find("ocr.glyph")),glyphArgs(),draft,work).flatMap { it.flattened() }
        assertEquals(listOf("screen.capture","ocr.glyphdetails","screen.release"),
            all.filter { it.contract.kind in setOf("screen.capture","ocr.glyphdetails","screen.release") }.map { it.contract.kind })
        val details=all.single { it.contract.kind=="ocr.glyphdetails" }.arguments
        assertEquals("frame",details["frameVariable"].asString)
        assertEquals("details",details["resultVariable"].asString)
        assertEquals(20,details["right"].asInt)
        val reads=all.filter { it.contract.kind=="vision.readresult" && it.arguments["shape"].asString=="GLYPH_SUMMARY" }
        assertEquals(mapOf("text" to "text","coveragePermille" to "coverage","averageScorePermille" to "score"),
            reads.associate { it.arguments["field"].asString to it.arguments["resultVariable"].asString })
        assertTrue(reads.all { it.arguments["sourceVariable"].asString=="details" })
        val target=all.single { it.contract.kind=="ocr.findtext" }.arguments
        assertEquals("details",target["detailsVariable"].asString)
        assertEquals("AB",target["text"].asString)
        assertEquals("targetMatches",target["resultVariable"].asString)
        assertTrue(all.any { it.contract.kind=="vision.readresult" && it.arguments["sourceVariable"].asString=="targetMatches" &&
            it.arguments["field"].asString=="count" && it.arguments["resultVariable"].asString=="match" })
        assertFalse(all.any { it.contract.kind in setOf("ocr.glyph","text.contains") })
        assertFalse(all.any { it.contract.kind=="control.if" && it.arguments["variable"].asString=="coverage" })
        assertFalse(all.filter { it.contract.kind=="variable.calculate" }.any { it.arguments["name"].asString in setOf("text","coverage","score") })
    }
    @Test fun glyphContainsRejectsUndeclaredConflictingAndWhitespaceTargets() {
        val contract=requireNotNull(BlockCatalog.find("ocr.glyph"))
        for(invalid in listOf(draft.copy(glyphDetails="missing"),draft.copy(glyphMatches="details"),draft.copy(glyphDetails="text"),
            draft.copy(glyphDetails="score"),draft.copy(targetText="A B"),draft.copy(targetText="A\nB"),draft.copy(targetText="A".repeat(257)))) {
            assertThrows(IllegalArgumentException::class.java) { recognitionWaitBlocks(contract,glyphArgs(),invalid,work) }
        }
    }
    @Test fun quickAllocationKeepsValidMappingsAndExplicitlyReservesOriginalOutputs() {
        val references=recognitionWaitReferences(glyphArgs())
        val partial=draft.copy(start="",match="",glyphDetails="",glyphMatches="")
        val suggested=requireNotNull(recognitionVariableSuggestion("ocr.glyph",partial,work,references))
        assertEquals(draft.clock,suggested.clock)
        assertEquals(draft.elapsed,suggested.elapsed)
        assertEquals(draft.attempt,suggested.attempt)
        assertEquals(draft.outcome,suggested.outcome)
        val slots=recognitionWaitSlots("ocr.glyph",suggested)
        assertEquals(slots.size,slots.map { it.value }.distinct().size)
        assertTrue(slots.all { slot -> slot.value !in references && work.any { it.name==slot.value && it.type==slot.type } })
        assertNull(recognitionVariableSuggestion("ocr.glyph",partial,work.filter { it.name!="details" && it.name!="points" },references))
        assertEquals(8,recognitionWaitSlots("ocr.glyph",suggested).size)
        assertEquals(6,recognitionWaitSlots("ocr.glyph",suggested.copy(exactText=true)).size)
    }
    @Test fun resultGuidanceAndEveryActionParameterInvalidatePriorScreenConfirmation() {
        RecognitionResultShape.entries.forEach { assertTrue(recognitionResultProducerHint(it).isNotBlank()) }
        assertTrue("count" in RecognitionResultShape.GLYPH_ITEM.fields)
        for(key in listOf("sourceVariable","shape","offsetX","offsetY","anchor","selection","action","limit","durationMs")) {
            assertTrue(key,recognitionActionClearsConfirmation(key))
        }
        assertFalse(recognitionActionClearsConfirmation("screenConfirmed"))
        assertFalse(recognitionActionClearsConfirmation("resultVariable"))
    }
    @Test fun multicolorWaitEmitsTheActualStringContractAndKeepsAnchorOffset() {
        val args=JsonParser.parseString("""{"frameVariable":"frame","anchorRgb":16777215,"anchorTolerance":2,"samples":[{"x":3,"y":-4,"rgb":0,"tolerance":5}],"region":{"left":0,"top":0,"right":20,"bottom":20},"foundVariable":"oldFound","xVariable":"x","yVariable":"y","autoCapture":true}""").asJsonObject
        val all=recognitionWaitBlocks(requireNotNull(BlockCatalog.find("vision.findmulticolor")),args,draft,work).flatMap { it.flattened() }
        val sample=all.single { it.contract.kind=="vision.findallmulticolor" }.arguments
        assertTrue(sample["samples"].asJsonPrimitive.isString)
        assertNull(visionSampleError(sample["samples"].asString))
        val points=JsonParser.parseString(sample["samples"].asString).asJsonArray
        assertEquals(0,points[0].asJsonObject["dx"].asInt);assertEquals(-4,points[1].asJsonObject["dy"].asInt)
        assertFalse(sample.has("autoCapture"))
    }
    @Test fun glyphHitTestingUsesActualFitCoordinatesAndHalfOpenBounds() {
        val details=OcrPreviewDetails("A",1000,900,listOf(OcrPreviewGlyph("A",1,2,3,4,900,true)))
        assertEquals(0,ocrGlyphAt(details,10,10,200f,100f,65f,25f))
        assertEquals(-1,ocrGlyphAt(details,10,10,200f,100f,40f,25f))
        assertEquals(-1,ocrGlyphAt(details,10,10,200f,100f,80f,25f))
        assertEquals(-1,ocrGlyphAt(details,10,10,0f,100f,65f,25f))
        val args=ocrPreviewReaderArguments(2)
        assertEquals("GLYPH_ITEM",args["shape"].asString);assertEquals(2,args["index"].asInt)
        assertEquals("",args["sourceVariable"].asString)
        assertThrows(IllegalArgumentException::class.java) { ocrPreviewReaderArguments(0) }
    }
    @Test fun pointErrorsLocateRowsAndNeverCoerceStringsBooleansOrOverflow() {
        val good="""[{"dx":0,"dy":-1,"rgb":16777215,"tolerance":0}]"""
        assertNull(visionSampleError(good))
        for(invalid in listOf("\"-\"","true","4294967297","1.5")) {
            assertTrue(requireNotNull(visionSampleError(good.replace("\"dx\":0","\"dx\":$invalid"))).contains("第1个色点"))
        }
        assertNotNull(visionSampleError("[]"));assertNotNull(visionSampleError("[{}]"))
    }
    @Test fun readerActionAndGuardedWaitKeepArgumentsAndChildrenThroughReversibleSource() {
        val editor=VisualEditorState.create("","root")
        val reader=ocrPreviewReaderArguments(2).apply { addProperty("sourceVariable","points");addProperty("resultVariable","text") }
        assertNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("vision.readresult")),reader))
        val action=JsonParser.parseString("""{"sourceVariable":"points","shape":"GLYPH_ITEM","selection":"all","anchor":"center","action":"tap","limit":2,"durationMs":100,"offsetX":0,"offsetY":0,"screenConfirmed":true}""").asJsonObject
        assertNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("vision.resultaction")),action))
        val forest=recognitionWaitBlocks(requireNotNull(BlockCatalog.find("ocr.glyph")),glyphArgs(),draft,work)
        val staged=requireNotNull(editor.stageTreeInsertion(forest,EditorInsertPosition.LIST_BOTTOM,null,null))
        assertNull(editor.replaceRawSource(editor.currentSource,staged))
        val source=editor.currentSource
        val exported=pluginSourceToolchain.export(source,"root").text
        val restored=pluginSourceToolchain.parse(exported,source,"root",emptySet()).text
        // Physical JSONL order is not execution order; the reversible source emits structural preorder.
        // Compare every complete envelope by its stable identity, including order keys and children.
        fun nodes(text:String)=text.lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
            .associateBy { it["nodeId"].asString }
        assertEquals(nodes(source),nodes(restored))
    }
}
