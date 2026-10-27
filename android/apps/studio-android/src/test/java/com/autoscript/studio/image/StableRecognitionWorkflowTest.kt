package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType as Type
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class StableRecognitionWorkflowTest {
    private val args=JsonObject().apply {
        addProperty("frameVariable","frame");addProperty("foundVariable","found");addProperty("xVariable","x");addProperty("yVariable","y")
        add("region",JsonObject().apply {addProperty("left",0);addProperty("top",0);addProperty("right",100);addProperty("bottom",100)})
    }
    private val draft=RecognitionWaitDraft(mode=RecognitionWaitMode.STABLE,start="start",clock="clock",elapsed="elapsed",attempt="attempt",outcome="outcome",
        previousFrame="previous",difference="difference",fresh="fresh",streak="streak",distance="distance",previousX="px",previousY="py",confirmFrames=true)
    private val variables=recognitionWaitSlots("vision.findimage",draft).map {CalculationVariable(it.value,it.type,"局部")}+
        listOf(CalculationVariable("frame",Type.IMAGE,"局部"))+listOf("found","x","y").map {CalculationVariable(it,Type.INTEGER,"局部")}
    @Test fun freshFrameGateAndOwnershipCleanupAreVisibleAndNoActionsAreInserted() {
        for(mode in RecognitionWaitMode.entries.filter {it.usesFreshFrames()}) {
            val forest=recognitionWaitBlocks(requireNotNull(BlockCatalog.find("vision.findimage")),args,draft.copy(mode=mode),variables)
            val all=forest.flatMap {it.flattened()}
            assertEquals(2,all.count {it.contract.kind=="screen.capture"})
            assertEquals(2,all.count {it.contract.kind=="screen.release"})
            assertTrue(all.any {it.contract.kind=="variable.copy" && it.arguments["name"].asString=="previous"})
            assertTrue(all.any {it.contract.kind=="vision.readresult" && it.arguments["field"].asString=="fresh"})
            assertFalse(all.any {it.contract.kind.startsWith("input.")})
            if(mode==RecognitionWaitMode.STABLE) {
                val sample=all.single {it.contract.kind=="vision.findimage"}
                assertFalse(sample.arguments["autoCapture"].asBoolean)
                assertEquals("none",sample.arguments["successAction"].asString)
                assertTrue(all.any {it.contract.kind=="variable.calculate" && it.arguments["name"].asString=="distance"})
            } else assertFalse(all.any {it.contract.kind=="vision.findimage"})
        }
    }
    @Test fun invalidVariablesUnconfirmedOverwritesAndDynamicRoiAreRejected() {
        val contract=requireNotNull(BlockCatalog.find("vision.findimage"))
        for(bad in listOf(draft.copy(confirmFrames=false),draft.copy(consecutive="0"),draft.copy(previousFrame="frame"),draft.copy(streak="found"),draft.copy(driftPx="-1")))
            assertThrows(IllegalArgumentException::class.java) {recognitionWaitBlocks(contract,args,bad,variables)}
        assertThrows(IllegalArgumentException::class.java) {recognitionWaitBlocks(contract,args.deepCopy().apply {addProperty("leftVariable","x")},draft,variables)}
    }
    @Test fun dedicatedRegionWaitNeedsNoTemplateRecognitionOutputsOrUnusedPositionVariables() {
        val inputs=mapOf("frameVariable" to "frame","left" to "0","top" to "0","right" to "100","bottom" to "100","tolerance" to "8")
        val configured=regionWaitArguments(inputs,variables)
        assertFalse(configured.has("imagePath"));assertFalse(configured.has("foundVariable"))
        val regionDraft=draft.copy(mode=RecognitionWaitMode.REGION_STABLE,previousX="",previousY="")
        assertFalse(recognitionWaitSlots("screen.compareframes",regionDraft).any {it.key in setOf("previousX","previousY")})
        val contract=requireNotNull(BlockCatalog.find("screen.compareframes"))
        val forest=recognitionWaitBlocks(contract,configured,regionDraft,variables).flatMap {it.flattened()}
        assertEquals(1,forest.count {it.contract.kind=="screen.compareframes"})
        assertFalse(forest.any {it.contract.kind.startsWith("vision.find") || it.arguments["name"]?.asString==""})
        assertThrows(IllegalArgumentException::class.java) {recognitionWaitBlocks(contract,configured,draft,variables)}
        assertThrows(IllegalArgumentException::class.java) {regionWaitArguments(inputs+("right" to "0"),variables)}
        assertThrows(IllegalArgumentException::class.java) {regionWaitArguments(inputs+("frameVariable" to "found"),variables)}
    }
}
