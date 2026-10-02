package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualProjectStateTest {
    @Test
    fun jumpPanelCommandsMapToTypedFlowNodesAndArguments() {
        assertEquals("跳出循环", legacyDockBlockQuery("break\n"))
        assertEquals("放置标记", legacyDockBlockQuery("::ready::\n"))
        assertEquals("跳转标记", legacyDockBlockQuery("goto ready\n"))
        assertEquals("返回上层", legacyDockBlockQuery("return\n"))
        val label = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("control.label")), "::ready::\n",
            JsonObject().apply { addProperty("name", "mark") },
        )
        assertEquals("ready", label.get("name").asString)
        val arguments = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("flow.argument.set")),
            "${FunctionCatalog.BLOCK_HINT_PREFIX}flow.argument.set\n2|variable:score",
            JsonObject().apply { addProperty("index", 1); addProperty("value", 0) },
        )
        assertEquals(2, arguments.get("index").asInt)
        assertEquals("score", arguments.get("valueVariable").asString)
        assertFalse(arguments.has("value"))
    }

    @Test
    fun legacyDockMigratesTypedCommandArgumentsWithoutDiscardingDefaults() {
        val tapDefaults = JsonObject().apply { addProperty("x", 0); addProperty("y", 0) }
        val tap = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("input.tap")),
            "Input.tap(120, 345)\n",
            tapDefaults,
        )
        assertEquals(120, tap.get("x").asInt)
        assertEquals(345, tap.get("y").asInt)

        val imageDefaults = JsonObject().apply {
            addProperty("imagePath", "assets/images/start.png")
            addProperty("similarityPermille", 900)
            addProperty("tolerance", 0)
        }
        val image = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("vision.findimage")),
            "Vision.findImage(\"start.png\", 0.95)\n",
            imageDefaults,
        )
        assertEquals("assets/images/start.png", image.get("imagePath").asString)
        assertEquals(950, image.get("similarityPermille").asInt)
        assertEquals(0, image.get("tolerance").asInt)
    }

    @Test
    fun debugOutputBecomesTypedLogBlockForVisualProjects() {
        val snippet = "Log.warn(\"network is slow\")\\n"
        assertEquals("输出日志", legacyDockBlockQuery(snippet))

        val log = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("task.log")),
            snippet,
            JsonObject().apply {
                addProperty("level", "info")
                addProperty("message", "识别完成")
            },
        )
        assertEquals("warn", log.get("level").asString)
        assertEquals("network is slow", log.get("message").asString)
    }

    @Test
    fun debugVariableOutputBecomesValueBoundLogBlockForVisualProjects() {
        val snippet = "Log.info(tostring(__vars[\"score\"]))\n"
        val log = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("task.log")),
            snippet,
            JsonObject().apply {
                addProperty("level", "info")
                addProperty("message", "识别完成")
            },
        )
        assertEquals("info", log.get("level").asString)
        assertEquals("score", log.get("message").asString)
        assertEquals("score", log.get("valueVariable").asString)
    }

    @Test
    fun legacyDockMigratesControlStructureAndKeepsExplicitBranching() {
        val conditionDefaults = JsonObject().apply {
            addProperty("variable", "value")
            addProperty("operator", "equals")
            addProperty("value", true)
        }
        val condition = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("control.if")),
            "if score >= 10 then\nend\n",
            conditionDefaults,
        )
        assertEquals("score", condition.get("variable").asString)
        assertEquals("greaterOrEqual", condition.get("operator").asString)
        assertEquals(10, condition.get("value").asInt)

        val repeatDefaults = JsonObject().apply { addProperty("times", 1) }
        val repeat = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("control.repeat")),
            "for index = 1, 7 do\nend\n",
            repeatDefaults,
        )
        assertEquals(7, repeat.get("times").asInt)
        assertEquals("index", repeat.get("indexVariable").asString)

        val variableRepeat = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("control.repeat")),
            "--@autoscript-loop:repeat:variable:attempts",
            repeatDefaults,
        )
        assertEquals(0, variableRepeat.get("times").asInt)
        assertEquals("attempts", variableRepeat.get("timesVariable").asString)

        val timed = legacyDockBlockArguments(
            requireNotNull(BlockCatalog.find("control.while")),
            "--@autoscript-loop:timed:fixed:2500",
            JsonObject().apply {
                addProperty("variable", "value")
                addProperty("operator", "equals")
                addProperty("value", true)
                addProperty("maxIterations", 10_000)
            },
        )
        assertTrue(timed.get("always").asBoolean)
        assertEquals(2500, timed.get("durationMs").asInt)
    }

    @Test
    fun loopMetricUsesNearestStructuralLoopRatherThanRenderedDepth() {
        val source = listOf(
            node(
                "loop", "block-root", "a0", "control.repeat", depth = 0,
                childBlocks = "\"childBlocks\":{\"body\":\"block-body\"},",
            ).replace("\"args\":{}", "\"args\":{\"times\":1}"),
            node("nested", "block-body", "a0", "task.noop", parent = "loop", depth = 1),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root")
        editor.selectedNodeId = "nested"

        assertEquals("loop", editor.nearestAncestorOfKind(kinds = setOf("control.repeat", "control.while")))
    }

    @Test
    fun projectionUsesExplicitBlocksAndOrderKeysInsteadOfPhysicalLinesOrDepth() {
        val source = listOf(
            node("child-b", "block-body", "b0", "task.noop", parent = "loop", depth = 99),
            node("root-b", "block-root", "b0", "task.noop", depth = 7),
            node(
                "loop",
                "block-root",
                "a0",
                "control.loop",
                depth = 4,
                childBlocks = "\"childBlocks\":{\"body\":\"block-body\"},",
            ),
            node("child-a", "block-body", "a0", "task.noop", parent = "loop", depth = 0),
        ).joinToString("\n", postfix = "\n")

        val rows = parseVisualNodes(source, "block-root")

        assertEquals(listOf("loop", "child-a", "child-b", "root-b"), rows.map { it.nodeId })
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
    }

    @Test
    fun corruptLineRemainsVisibleWithStableLocation() {
        val rows = parseVisualNodes("{broken\n", "block-root")

        assertEquals(1, rows.size)
        assertEquals("invalid-line-1", rows.single().nodeId)
        assertEquals(1, rows.single().sourceLine)
    }

    @Test
    fun editorInsertMoveDeleteAndUndoAreWholeTransactions() {
        val source = node("first", "block-root", "a0", "task.noop", depth = 0) + "\n"
        var id = 0
        val editor = VisualEditorState.create(source, "block-root") { "fixed-${++id}" }

        val second = editor.insertNoop("first")!!
        val third = editor.insertNoop(second)!!
        assertEquals(listOf("first", second, third), editor.rows.map { it.nodeId })
        assertEquals(listOf(0, 0, 0), editor.rows.map { it.depth })
        assertTrue(editor.moveSelected(-1))
        assertEquals(listOf("first", third, second), editor.rows.map { it.nodeId })
        assertTrue(editor.deleteSelected())
        assertEquals(listOf("first", second), editor.rows.map { it.nodeId })
        assertEquals("first", editor.selectedNodeId)
        assertTrue(editor.undo())
        assertEquals(listOf("first", third, second), editor.rows.map { it.nodeId })
        assertTrue(editor.redo())
        assertEquals(listOf("first", second), editor.rows.map { it.nodeId })
    }

    @Test
    fun editorAnnotationIsARealNonExecutingCommentNode() {
        val source = node("step", "block-root", "a0", "task.noop", depth = 0) + "\n"
        val editor = VisualEditorState.create(source, "block-root") { "comment-id" }
        editor.selectedNodeId = "step"
        val contract = requireNotNull(BlockCatalog.find("task.comment"))
        val args = requireNotNull(initialBlockArguments(contract, emptyList(), emptyList(), "main"))
        args.addProperty("message", "检查目标位置")

        val commentId = requireNotNull(editor.insertBlock(contract, args))
        assertEquals(listOf("step", commentId), editor.rows.map { it.nodeId })
        assertEquals("task.comment", editor.rows.last().kind)
        assertEquals("检查目标位置", editor.nodeArguments(commentId)?.get("message")?.asString)
        assertTrue(editor.canUndo)
        assertTrue(editor.undo())
        assertEquals(listOf("step"), editor.rows.map { it.nodeId })
    }

    @Test
    fun emptyEditorAnnotationIsRejectedBeforeSaving() {
        val contract = requireNotNull(BlockCatalog.find("task.comment"))
        val (arguments, message) = parseBlockArguments(
            contract, mapOf("message" to "   "), null, emptyMap(), emptyList(),
        )
        assertEquals(null, arguments)
        assertEquals("注释内容不能为空", message)
    }

    @Test
    fun editorDeletesOwnedSubtreeAndUsesStableUniqueIds() {
        val source = listOf(
            node(
                "owner",
                "block-root",
                "a0",
                "control.loop",
                depth = 0,
                childBlocks = "\"childBlocks\":{\"body\":\"block-body\"},",
            ),
            node("child", "block-body", "a0", "task.noop", parent = "owner", depth = 1),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root") { "new" }
        editor.selectedNodeId = "owner"

        assertTrue(editor.deleteSelected())
        assertTrue(editor.rows.isEmpty())
        assertEquals(null, editor.selectedNodeId)
        assertTrue(editor.undo())
        assertEquals(listOf("owner", "child"), editor.rows.map { it.nodeId })
        val inserted = editor.insertNoop("child")
        assertEquals("node-new", inserted)
        assertEquals(3, editor.rows.map { it.nodeId }.distinct().size)
    }

    @Test
    fun invalidSourceIsReadOnlyAndSaveSnapshotDoesNotLoseLaterEdit() {
        val invalid = VisualEditorState.create("{broken\n", "block-root")
        assertTrue(invalid.isReadOnly)
        assertEquals(null, invalid.insertNoop())

        val source = node("first", "block-root", "a0", "task.noop", depth = 0) + "\n"
        var id = 0
        val editor = VisualEditorState.create(source, "block-root") { "${++id}" }
        editor.insertNoop()
        val submitted = editor.currentSource
        editor.insertNoop()
        editor.markSaved(submitted)

        assertTrue(editor.isDirty)
        assertNotEquals(editor.savedSource, editor.currentSource)
        editor.markSaved(editor.currentSource)
        assertFalse(editor.isDirty)
    }

    @Test
    fun depthConflictIsVisibleButReadOnly() {
        val source = node("first", "block-root", "a0", "task.noop", depth = 9) + "\n"
        val editor = VisualEditorState.create(source, "block-root")

        assertTrue(editor.isReadOnly)
        assertEquals(listOf("first"), editor.rows.map { it.nodeId })
        assertEquals(null, editor.insertNoop())
    }

    @Test
    fun newerKnownNodeVersionIsReadOnly() {
        val source = node("future", "block-root", "a0", "task.noop", depth = 0)
            .replace("\"nodeVersion\":1", "\"nodeVersion\":2") + "\n"

        val editor = VisualEditorState.create(source, "block-root")

        assertTrue(editor.isReadOnly)
        assertEquals(listOf("future"), editor.rows.map { it.nodeId })
    }

    @Test
    fun editorCanInsertIntoNamedEmptyChildBlocks() {
        var id = 0
        val editor = VisualEditorState.create("", "block-root") { "${++id}" }
        val conditional = requireNotNull(BlockCatalog.find("control.if"))
        val owner = requireNotNull(
            editor.insertBlock(
                conditional,
                JsonObject().apply {
                    addProperty("variable", "ready")
                    addProperty("operator", "equals")
                    addProperty("value", true)
                },
            ),
        )
        val noop = requireNotNull(BlockCatalog.find("task.noop"))

        val thenNode = requireNotNull(
            editor.insertBlock(noop, JsonObject(), owner, intoChildBlockName = "then"),
        )
        editor.selectedNodeId = owner
        val elseNode = requireNotNull(
            editor.insertBlock(noop, JsonObject(), owner, intoChildBlockName = "else"),
        )

        assertEquals(listOf(owner, elseNode, thenNode), editor.rows.map { it.nodeId })
        assertEquals(listOf(0, 1, 1), editor.rows.map { it.depth })
        assertEquals(listOf(null, "else", "then"), editor.rows.map { it.childSlot })
        val sibling = requireNotNull(editor.insertBlock(noop, JsonObject(), owner))
        assertEquals(listOf(owner, elseNode, thenNode, sibling), editor.rows.map { it.nodeId })
        assertEquals(listOf(0, 1, 1, 0), editor.rows.map { it.depth })
        assertEquals(listOf(null, "else", "then", null), editor.rows.map { it.childSlot })
        val before = editor.currentSource
        editor.selectedNodeId = owner
        assertEquals(null, editor.insertBlock(noop, JsonObject(), owner, "missing"))
        assertEquals(before, editor.currentSource)
    }

    @Test
    fun relatedGestureBlocksInsertAndUndoAsOneTransaction() {
        var id = 0
        val editor = VisualEditorState.create("", "block-root") { "gesture-${++id}" }
        val down = requireNotNull(BlockCatalog.find("input.pointerdown"))
        val sleep = requireNotNull(BlockCatalog.find("task.sleep"))
        val up = requireNotNull(BlockCatalog.find("input.pointerup"))
        val blocks = listOf(
            down to JsonObject().apply { addProperty("x", 10); addProperty("y", 20) },
            sleep to JsonObject().apply { addProperty("milliseconds", 800) },
            up to JsonObject(),
        )

        assertTrue(editor.insertBlocks(blocks))
        assertEquals(listOf("input.pointerdown", "task.sleep", "input.pointerup"), editor.rows.map { it.kind })
        val insertedSource = editor.currentSource
        assertTrue(editor.undo())
        assertEquals("", editor.currentSource)
        assertTrue(editor.redo())
        assertEquals(insertedSource, editor.currentSource)
    }

    @Test
    fun imageRecognitionInsertsCaptureAndFindAsOneUndoableAction() {
        var id = 0
        val editor = VisualEditorState.create("", "block-root") { "image-${++id}" }
        val capture = requireNotNull(BlockCatalog.find("screen.capture"))
        val find = requireNotNull(BlockCatalog.find("vision.findimage"))
        val release = requireNotNull(BlockCatalog.find("screen.release"))
        val captureArgs = JsonObject().apply { addProperty("resultVariable", "frame") }
        val findArgs = JsonObject().apply {
            addProperty("frameVariable", "frame")
            addProperty("imagePath", "assets/images/template.png")
        }

        val releaseArgs = JsonObject().apply { addProperty("frameVariable", "frame") }
        assertTrue(editor.insertBlocks(listOf(capture to captureArgs, find to findArgs, release to releaseArgs)))
        assertEquals(listOf("screen.capture", "vision.findimage", "screen.release"), editor.rows.map { it.kind })
        assertTrue(editor.undo())
        assertEquals("", editor.currentSource)
    }

    @Test
    fun editorOwnsElseIfBranchStructureAsOneTransaction() {
        var id = 0
        val editor = VisualEditorState.create("", "block-root") { "elseif-${++id}" }
        val conditional = requireNotNull(BlockCatalog.find("control.if"))
        val owner = requireNotNull(editor.insertBlock(
            conditional,
            JsonObject().apply {
                addProperty("variable", "score")
                addProperty("operator", "equals")
                addProperty("value", 1)
            },
        ))
        editor.selectedNodeId = owner
        assertTrue(editor.addElseIfBranch())
        assertEquals(listOf("then", "else", "elseIf0"), editor.childBlockNames(owner))
        assertEquals(1, editor.elseIfBranchCount(owner))

        val noop = requireNotNull(BlockCatalog.find("task.noop"))
        val child = requireNotNull(editor.insertBlock(noop, JsonObject(), owner, "elseIf0"))
        assertTrue(editor.rows.any { it.nodeId == child && it.childSlot == "elseIf0" })

        editor.selectedNodeId = owner
        assertTrue(editor.removeLastElseIfBranch())
        assertEquals(listOf("then", "else"), editor.childBlockNames(owner))
        assertEquals(0, editor.elseIfBranchCount(owner))
        assertFalse(editor.rows.any { it.nodeId == child })
    }

    @Test
    fun indentAndOutdentMoveWholeSubtreeWithoutChangingIdentity() {
        val source = listOf(
            node(
                "owner", "block-root", "a0", "control.if", depth = 0,
                childBlocks = "\"childBlocks\":{\"then\":\"block-then\",\"else\":\"block-else\"},",
            ),
            node("moving", "block-root", "b0", "task.noop", depth = 0),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root")
        editor.selectedNodeId = "moving"

        assertTrue(editor.indentSelected("then"))
        assertEquals(listOf("owner", "moving"), editor.rows.map { it.nodeId })
        assertEquals(listOf(0, 1), editor.rows.map { it.depth })
        assertEquals("then", editor.rows.last().childSlot)
        assertTrue(editor.outdentSelected())
        assertEquals(listOf(0, 0), editor.rows.map { it.depth })
        assertEquals(listOf("owner", "moving"), editor.rows.map { it.nodeId })
    }

    @Test
    fun subtreePasteAlwaysRegeneratesNodeAndBlockIdsAndRewritesInternalReferences() {
        val source = listOf(
            node(
                "owner", "block-root", "a0", "control.if", depth = 0,
                childBlocks = "\"childBlocks\":{\"then\":\"owned-then\",\"else\":\"owned-else\"},",
            ).replace("\"args\":{}", "\"args\":{\"targetNodeId\":\"child\",\"label\":\"child\"}"),
            node("child", "owned-then", "a0", "task.noop", parent = "owner", depth = 1),
        ).joinToString("\n", postfix = "\n")
        var id = 0
        val editor = VisualEditorState.create(source, "block-root") { "copy-${++id}" }
        editor.selectedNodeId = "owner"
        val clipboard = requireNotNull(editor.copySelectedSubtree())

        val copiedRoot = requireNotNull(editor.pasteSubtree(clipboard, afterNodeId = "owner"))
        val copiedRows = editor.rows.filter { it.nodeId !in setOf("owner", "child") }
        assertEquals(2, copiedRows.size)
        assertNotEquals("owner", copiedRoot)
        assertEquals(4, editor.rows.map { it.nodeId }.distinct().size)
        assertNotEquals(
            editor.rows.first { it.nodeId == "child" }.blockId,
            copiedRows.first { it.depth == 1 }.blockId,
        )
        assertEquals(
            copiedRows.first { it.depth == 1 }.nodeId,
            editor.nodeArguments(copiedRoot)?.get("targetNodeId")?.asString,
        )
        assertEquals("child", editor.nodeArguments(copiedRoot)?.get("label")?.asString)
        assertTrue(editor.undo())
        assertEquals(listOf("owner", "child"), editor.rows.map { it.nodeId })
    }

    @Test
    fun multipleSelectionDeduplicatesDescendantsAndDeletesAsOneUndo() {
        val source = listOf(
            node("owner", "block-root", "a0", "control.repeat", depth = 0, childBlocks = "\"childBlocks\":{\"body\":\"body\"},"),
            node("child", "body", "a0", "task.noop", parent = "owner", depth = 1),
            node("tail", "block-root", "a1", "task.noop", depth = 0),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root")
        editor.toggleSelection("owner")
        editor.toggleSelection("child")
        editor.toggleSelection("tail")
        assertEquals(listOf("owner", "tail"), editor.selectedRoots())
        assertEquals(2, editor.copySelection().size)
        assertEquals(3, editor.selectedNodeIds.size)
        assertTrue(editor.deleteSelection())
        assertTrue(editor.rows.isEmpty())
        assertTrue(editor.undo())
        assertEquals(listOf("owner", "child", "tail"), editor.rows.map { it.nodeId })
        assertFalse(editor.canUndo)
    }

    @Test
    fun batchMovementPreservesOrderAndRollsBackWhenAnyRootHitsBoundary() {
        val source = (0..3).joinToString("\n", postfix = "\n") { node("n$it", "block-root", "a$it", "task.noop", depth = 0) }
        val editor = VisualEditorState.create(source, "block-root")
        editor.toggleSelection("n1")
        editor.toggleSelection("n2")
        assertTrue(editor.moveSelection(-1))
        assertEquals(listOf("n1", "n2", "n0", "n3"), editor.rows.map { it.nodeId })
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        editor.selectedNodeId = "n2"
        editor.toggleSelection("n3")
        assertFalse(editor.moveSelection(1))
        assertEquals(source, editor.currentSource)
        assertFalse(editor.canUndo)
        assertEquals(setOf("n2", "n3"), editor.selectedNodeIds)
    }

    @Test
    fun forestPasteAndIndentOutdentAreSingleTransactions() {
        val source = listOf(
            node("owner", "block-root", "a0", "control.repeat", depth = 0, childBlocks = "\"childBlocks\":{\"body\":\"body\"},"),
            node("a", "block-root", "a1", "task.noop", depth = 0),
            node("b", "block-root", "a2", "task.noop", depth = 0),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root")
        editor.toggleSelection("a")
        editor.toggleSelection("b")
        assertTrue(editor.indentSelection("body"))
        assertEquals(listOf(0, 1, 1), editor.rows.map { it.depth })
        assertTrue(editor.outdentSelection())
        assertEquals(listOf("owner", "a", "b"), editor.rows.map { it.nodeId })
        val copies = editor.copySelection()
        assertTrue(editor.pasteSelection(copies))
        assertEquals(5, editor.rows.size)
        assertEquals(2, editor.selectedNodeIds.size)
        assertEquals(5, editor.rows.map { it.nodeId }.distinct().size)
        assertTrue(editor.undo())
        assertEquals(3, editor.rows.size)
    }

    @Test
    fun forestPasteRewritesReferencesBetweenCopiedRootsAndPreservesOriginals() {
        val source = listOf(
            node("a", "block-root", "a0", "task.noop", depth = 0).replace("\"args\":{}", "\"args\":{\"targetNodeId\":\"b\"}"),
            node("b", "block-root", "a1", "task.noop", depth = 0),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root")
        editor.toggleSelection("a"); editor.toggleSelection("b")
        assertTrue(editor.pasteSelection(editor.copySelection()))
        val copies = editor.rows.filter { it.nodeId !in setOf("a", "b") }
        assertEquals(2, copies.size)
        assertEquals(copies[1].nodeId, editor.nodeArguments(copies[0].nodeId)?.get("targetNodeId")?.asString)
        assertEquals("b", editor.nodeArguments("a")?.get("targetNodeId")?.asString)
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
    }

    @Test
    fun undoAndRedoPruneRemovedSelectionsWithoutDroppingSurvivors() {
        val source = node("a", "block-root", "a0", "task.noop", depth = 0)
        val editor = VisualEditorState.create(source, "block-root")
        val inserted = requireNotNull(editor.insertNoop())
        editor.toggleSelection("a")
        assertEquals("a", editor.selectedNodeId)
        assertTrue(editor.undo())
        assertEquals(setOf("a"), editor.selectedNodeIds)
        assertTrue(editor.redo())
        editor.selectOnly(inserted)
        assertTrue(editor.deleteSelection())
        assertTrue(editor.undo())
        editor.toggleSelection("a")
        editor.toggleSelection(inserted)
        assertTrue(editor.redo())
        assertEquals(setOf("a"), editor.selectedNodeIds)
        assertEquals("a", editor.selectedNodeId)
    }

    @Test
    fun disabledContainerPreservesSubtreeAndIsUndoable() {
        val source = listOf(
            node("owner", "block-root", "a0", "control.repeat", depth = 0, childBlocks = "\"childBlocks\":{\"body\":\"body\"},"),
            node("child", "body", "a0", "task.noop", "owner", 1),
        ).joinToString("\n", postfix = "\n")
        val editor = VisualEditorState.create(source, "block-root")
        editor.selectOnly("owner")
        assertTrue(editor.toggleDisabledSelection())
        assertTrue(editor.isNodeDisabled("child"))
        assertFalse(editor.isNodeDisabled("child", inherited = false))
        assertEquals(2, editor.rows.size)
        assertEquals(2, editor.selectionSubtreeSize())
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        assertFalse(editor.isNodeDisabled("owner"))
        assertTrue(editor.redo())
        assertTrue(editor.toggleDisabledSelection())
        assertFalse(editor.isNodeDisabled("child"))
    }

    @Test
    fun reflectionChangesArgumentsWithoutReplacingContainerOrChildrenAndCanReuse() {
        val source = listOf(
            node("owner", "block-root", "a0", "control.repeat", depth = 0, childBlocks = "\"childBlocks\":{\"body\":\"body\"},"),
            node("child", "body", "a0", "task.noop", "owner", 1),
        ).joinToString("\n", postfix = "\n")
        var serial = 0
        val editor = VisualEditorState.create(source, "block-root") { "new-${++serial}" }
        val contract = requireNotNull(BlockCatalog.find("control.repeat"))
        val args = JsonObject().apply { addProperty("times", 7) }
        assertTrue(applyReflectedArguments(editor, "owner", contract, args))
        assertEquals(listOf("owner", "child"), editor.rows.map { it.nodeId })
        assertEquals(listOf("body"), editor.childBlockNames("owner"))
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        assertTrue(applyReflectedArguments(editor, "owner", contract, args, EditorInsertPosition.BELOW))
        assertEquals(3, editor.rows.size)
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
    }

    @Test
    fun actionPolicyLocksMutationsDuringExecutionButAllowsPausedStep() {
        val states = listOf(com.autoscript.core.model.RuntimeEngineState.RUNNING,
            com.autoscript.core.model.RuntimeEngineState.PAUSED, com.autoscript.core.model.RuntimeEngineState.STOPPING)
        states.forEach { assertFalse(editorCanMutate(false, false, it)) }
        assertTrue(editorCanMutate(false, false, com.autoscript.core.model.RuntimeEngineState.IDLE))
        assertTrue(editorCanStep(false, true, com.autoscript.core.model.RuntimeEngineState.PAUSED, false))
        assertFalse(editorCanStep(false, true, com.autoscript.core.model.RuntimeEngineState.RUNNING, true))
        assertFalse(editorCanStep(false, false, com.autoscript.core.model.RuntimeEngineState.IDLE, true))
    }

    @Test
    fun reflectionRejectsStaleArgumentsAndDuplicateLabelsWithoutMutation() {
        val source = node("label", "block-root", "a0", "control.label", depth = 0)
            .replace("\"args\":{}", "\"args\":{\"name\":\"start\"}") + "\n"
        val editor = VisualEditorState.create(source, "block-root")
        val contract = requireNotNull(BlockCatalog.find("control.label"))
        val original = requireNotNull(editor.nodeArguments("label"))
        assertFalse(applyReflectedArguments(editor, "label", contract, original, EditorInsertPosition.BELOW))
        assertEquals(source, editor.currentSource)
        val updated = JsonObject().apply { addProperty("name", "newName") }
        assertTrue(editor.updateArguments("label", updated))
        val changedSource = editor.currentSource
        assertFalse(applyReflectedArguments(editor, "label", contract, original, expectedArguments = original))
        assertEquals(changedSource, editor.currentSource)
    }

    @Test
    fun reflectionRequiresExplicitValidBranchForInsideInsertion() {
        val source = node("owner", "block-root", "a0", "control.if", depth = 0,
            childBlocks = "\"childBlocks\":{\"then\":\"then-block\",\"else\":\"else-block\"},") + "\n"
        val editor = VisualEditorState.create(source, "block-root")
        val contract = requireNotNull(BlockCatalog.find("control.if"))
        val args = JsonObject()
        assertFalse(applyReflectedArguments(editor, "owner", contract, args, EditorInsertPosition.INSIDE))
        assertEquals(source, editor.currentSource)
        assertTrue(applyReflectedArguments(editor, "owner", contract, args, EditorInsertPosition.INSIDE, "else"))
        assertEquals("else", editor.rows.last().childSlot)
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
    }

    @Test
    fun loopReflectionRestoresUnitsAndVariableModes() {
        val args = JsonObject().apply { addProperty("durationVariable", "timeout"); addProperty("durationUnit", "seconds"); addProperty("maxIterations", 80) }
        val draft = loopDraftFromArguments("control.while", args)
        assertEquals(LoopMode.TIMED, draft.mode)
        assertTrue(draft.variableMode)
        assertEquals("timeout", draft.timeVariable)
        assertEquals(LoopTimeUnit.SECONDS, draft.unit)
        assertEquals("80", draft.maxIterations)
    }

    @Test
    fun literalTextReplacementDoesNotRewriteNamesOrStructureAndHasOneUndo() {
        val source = node("hello-id", "block-root", "a0", "task.log", depth = 0)
            .replace("\"args\":{}", "\"args\":{\"message\":\"hello world\",\"valueVariable\":\"hello\",\"level\":\"info\"}") + "\n"
        val editor = VisualEditorState.create(source, "block-root")
        assertEquals(1, editor.replaceDisplayText("hello", "goodbye", false))
        assertEquals("hello-id", editor.rows.single().nodeId)
        assertEquals("goodbye world", editor.nodeArguments("hello-id")?.get("message")?.asString)
        assertEquals("hello", editor.nodeArguments("hello-id")?.get("valueVariable")?.asString)
        assertTrue(editor.undo())
        assertEquals(source, editor.currentSource)
        assertEquals(0, editor.replaceDisplayText("", "anything", false))
        assertEquals(0, editor.replaceDisplayText("hello", "界".repeat(2048), false))
        assertEquals(source, editor.currentSource)
    }

    private fun node(
        id: String,
        block: String,
        order: String,
        kind: String,
        parent: String? = null,
        depth: Int,
        childBlocks: String = "",
    ): String =
        """{"flowSchemaVersion":1,"nodeId":"$id","blockId":"$block","parentId":${parent?.let { "\"$it\"" } ?: "null"},"orderKey":"$order","kind":"$kind","nodeVersion":1,$childBlocks"depth":$depth,"args":{}}"""
}
