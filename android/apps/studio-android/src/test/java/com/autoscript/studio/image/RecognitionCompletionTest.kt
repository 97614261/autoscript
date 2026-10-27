package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType as Type
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class RecognitionCompletionTest {
    private fun variable(name: String, type: Type = Type.INTEGER) = CalculationVariable(name, type, "局部")
    private val work = listOf("start", "clock", "elapsed", "attempt", "outcome", "absentStart", "absentElapsed", "found").map { variable(it) }
    private val draft = RecognitionWaitDraft(start = "start", clock = "clock", elapsed = "elapsed", attempt = "attempt",
        outcome = "outcome", absentStart = "absentStart", absentElapsed = "absentElapsed")
    private fun arguments() = JsonObject().apply {
        addProperty("foundVariable", "found"); addProperty("frameVariable", "frame")
        addProperty("frequency", 20); addProperty("successAction", "tap")
    }
    private val find = requireNotNull(BlockCatalog.find("vision.findimage"))

    @Test fun waitingModesHaveExplicitDeadlineAndOneSampleWithoutImplicitActions() {
        for (mode in RecognitionWaitMode.entries.filterNot {it.usesFreshFrames()}) {
            val forest = recognitionWaitBlocks(find, arguments(), draft.copy(mode = mode), work)
            val all = forest.flatMap { it.flattened() }
            val sample = all.single { it.contract.kind == "vision.findimage" }.arguments
            assertEquals(1, sample["frequency"].asInt)
            assertEquals("none", sample["successAction"].asString)
            assertTrue(sample["autoCapture"].asBoolean)
            assertEquals(2, all.count { it.contract.kind == "control.if" && it.arguments["variable"].asString == "elapsed" })
            assertEquals("control.if", forest.last().contract.kind)
            assertEquals(setOf("then", "else"), forest.last().children.keys)
            assertEquals("outcome", forest.last().arguments["variable"].asString)
            assertTrue(all.all { it.contract == BlockCatalog.find(it.contract.kind) })
            assertFalse(all.any { it.contract.kind.startsWith("input.") })
            assertTrue(all.single { it.contract.kind == "task.sleep" }.arguments["exactTiming"].asBoolean)
        }
    }

    @Test fun workVariablesAreDeclaredTypedDistinctAndCannotOverwriteRecognitionReferences() {
        assertTrue(recognitionWaitBlocks(find, arguments(), draft.copy(attempts = "", absentMs = ""), work).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { recognitionWaitBlocks(find, arguments(), draft.copy(clock = "start"), work) }
        assertThrows(IllegalArgumentException::class.java) { recognitionWaitBlocks(find, arguments(), draft.copy(clock = "found"), work) }
        assertThrows(IllegalArgumentException::class.java) { recognitionWaitBlocks(find, arguments(), draft, work.filter { it.name != "found" }) }
        assertThrows(IllegalArgumentException::class.java) { recognitionWaitBlocks(find, arguments(), draft.copy(interval = "0"), work) }
        assertThrows(IllegalArgumentException::class.java) { recognitionWaitBlocks(find, arguments(), draft.copy(mode = RecognitionWaitMode.ABSENT, absentMs = "6000"), work) }
    }

    @Test fun absenceStartsWithFirstNegativeSampleNotBeforeCaptureAndResetsOnHit() {
        val all = recognitionWaitBlocks(find, arguments(), draft.copy(mode = RecognitionWaitMode.ABSENT), work).flatMap { it.flattened() }
        assertEquals(2, all.count { it.contract.kind == "variable.calculate" && it.arguments["name"].asString == "absentStart" && it.arguments["expression"].asString == "-1" })
        assertTrue(all.any { it.contract.kind == "control.if" && it.arguments["variable"].asString == "absentStart" && it.arguments["operator"].asString == "lessThan" })
    }

    @Test fun treeStagingKeepsChildBranchesAndDoesNotMutateUntilOneUndoableCommit() {
        for (position in listOf(EditorInsertPosition.ABOVE, EditorInsertPosition.BELOW, EditorInsertPosition.INSIDE, EditorInsertPosition.LIST_BOTTOM)) {
            val editor = VisualEditorState.create("", "root")
            val anchor = requireNotNull(editor.insertBlock(requireNotNull(BlockCatalog.find("control.if")), JsonObject()))
            val original = editor.currentSource
            val forest = recognitionWaitBlocks(find, arguments(), draft, work)
            val pending = requireNotNull(ImageBlockInsertionRequest.createTrees("p", "main", editor, forest, ImageInsertionOrigin.CONFIGURATION))
            val caps = pending.blocks.flatMap { it.first.requiredCapabilities }.toSet()
            val staged = requireNotNull(pending.stage(editor, "p", "main", caps, position, if (position == EditorInsertPosition.INSIDE) "else" else null))
            assertEquals(original, editor.currentSource)
            val candidate = VisualEditorState.create(staged, "root")
            assertFalse(candidate.isReadOnly)
            assertTrue(candidate.rows.any { it.kind == "vision.findimage" && it.depth >= 1 })
            assertNull(editor.replaceRawSource(original, staged))
            assertTrue(editor.undo()); assertEquals(original, editor.currentSource)
            assertNotNull(anchor)
        }
    }

    @Test fun unsafeTreeShapesRejectedBeforeFlatteningAndManualSourcesNeverRelease() {
        val noop = VisualBlockTree(requireNotNull(BlockCatalog.find("task.noop")), JsonObject())
        val editor = VisualEditorState.create("", "root")
        assertNull(ImageBlockInsertionRequest.createTrees("p", "main", editor, List(257) { noop }, ImageInsertionOrigin.CONFIGURATION))
        assertNull(ImageBlockInsertionRequest.createTrees("p", "main", editor, listOf(noop.copy(children = mapOf("body" to listOf(noop)))), ImageInsertionOrigin.CONFIGURATION))
        for (kind in listOf("vision.findcolor", "ocr.glyph", "vision.findgray", "ocr.alphanumeric", "vision.components")) {
            val result = recognitionImageEntryBlocks(requireNotNull(BlockCatalog.find(kind)), arguments(), capture = false)
            assertEquals(listOf(kind), result.map { it.first.kind })
            assertFalse(result.single().second["autoCapture"]?.asBoolean == true)
        }
        for (kind in listOf("ocr.findtext", "screen.crop", "screen.transform", "vision.result"))
            assertEquals(listOf(kind), recognitionImageEntryBlocks(requireNotNull(BlockCatalog.find(kind)), JsonObject()).map { it.first.kind })
    }

    @Test fun resultReadersKeepSourceTypedAndDecodeTextInsteadOfCopyingJsonQuotes() {
        val vars = listOf(variable("source", Type.STRING), variable("scratch", Type.STRING), variable("text", Type.STRING), variable("number"))
        val text = recognitionResultBlocks(RecognitionResultShape.GLYPH_ITEM, "text", 2, "source", "scratch", "text", vars)
        assertEquals(listOf("vision.readresult"), text.map { it.first.kind })
        assertEquals("GLYPH_ITEM", text.first().second["shape"].asString)
        assertEquals(2, text.single().second["index"].asInt)
        val count = recognitionResultBlocks(RecognitionResultShape.GLYPH_SUMMARY, "count", 0, "source", "scratch", "number", vars)
        assertEquals(listOf("vision.readresult"), count.map { it.first.kind })
        val x = recognitionResultBlocks(RecognitionResultShape.COLORS, "x", 1, "source", "scratch", "number", vars)
        assertEquals("x", x.single().second["field"].asString)
        assertThrows(IllegalArgumentException::class.java) { recognitionResultBlocks(RecognitionResultShape.COLORS, "x", 0, "source", "scratch", "number", vars) }
        assertEquals(1,recognitionResultBlocks(RecognitionResultShape.COLORS, "x", 1, "source", "", "number", vars).size)
        assertThrows(IllegalArgumentException::class.java) { recognitionResultBlocks(RecognitionResultShape.COLORS, "x", 1, "source", "scratch", "text", vars) }
    }

    @Test fun componentStatusDoesNotConfuseInstalledWithRecognitionSuccess() {
        assertNull(recognitionAvailabilityProblem("INSTALLED,source=provider,loaded=false"))
        assertNotNull(recognitionAvailabilityProblem("CHECKING,source=provider,loaded=false"))
        assertNotNull(recognitionAvailabilityProblem("LOAD_FAILED,source=provider,loaded=true"))
        assertNotNull(recognitionAvailabilityProblem("unknown/private/path"))
        assertNull(recognitionComponent("ocr.glyph"))
    }
}
