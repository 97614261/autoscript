package com.autoscript.studio

import com.autoscript.studio.generated.BlockContract
import com.autoscript.script.ui.CompactId
import com.google.gson.JsonNull
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal class VisualEditorState private constructor(
    initialSource: String,
    migratedSource: String,
    private val rootBlockId: String,
    private val idFactory: () -> String,
    forceReadOnly: Boolean,
) {
    private var projectIds: VisualProjectIds? = null
    var savedSource: String = initialSource
        private set
    var currentSource: String = migratedSource
        private set
    private var selectionMutationDepth = 0
    var selectedNodeId: String? = null
        set(value) {
            field = value
            if (selectionMutationDepth == 0) selectedNodeIds = setOfNotNull(value)
        }
    var selectedNodeIds: Set<String> = emptySet()
        private set

    fun toggleSelection(nodeId: String) {
        if (rows.none { it.nodeId == nodeId }) return
        if (nodeId !in selectedNodeIds && selectedNodeIds.size >= 256) return
        val next = if (nodeId in selectedNodeIds) selectedNodeIds - nodeId else selectedNodeIds + nodeId
        selectedNodeId = next.lastOrNull()
        selectedNodeIds = next
    }

    fun selectOnly(nodeId: String?) {
        selectedNodeId = nodeId
        selectedNodeIds = setOfNotNull(nodeId)
    }

    /** View-only bulk selection: parse once, never record history or partially apply a large request. */
    fun selectNodes(nodeIds: Collection<String>): Boolean {
        val requested = nodeIds.toSet()
        if (requested.size > 256) return false
        val ordered = rows.map { it.nodeId }
        if (!ordered.toSet().containsAll(requested)) return false
        val next = ordered.filter { it in requested }.toSet()
        val primary = selectedNodeId?.takeIf { it in next } ?: next.firstOrNull()
        selectionMutationDepth++
        try {
            selectedNodeId = primary
            selectedNodeIds = next
        } finally { selectionMutationDepth-- }
        return true
    }

    /** Selected ancestors already include their children: never process a subtree twice. */
    fun selectedRoots(): List<String> {
        val document = editableDocument() ?: return emptyList()
        val requested = selectedNodeIds.ifEmpty { setOfNotNull(selectedNodeId) }
        val byId = document.nodes.associateBy { it.nodeId }
        return rows.map { it.nodeId }.filter { id ->
            if (id !in requested) return@filter false
            var parent = byId[id]?.parentId
            val seen = mutableSetOf<String>()
            while (parent != null && seen.add(parent)) {
                if (parent in requested) return@filter false
                parent = byId[parent]?.parentId
            }
            true
        }
    }

    private fun batchMutation(operation: () -> Boolean): Boolean {
        val source = currentSource
        val primary = selectedNodeId
        val selection = selectedNodeIds
        val undo = undoStack.toList()
        val redo = redoStack.toList()
        selectionMutationDepth++
        val result = try {
            operation()
        } catch (failure: Exception) {
            currentSource = source
            selectedNodeId = primary
            selectedNodeIds = selection
            throw failure
        } finally {
            selectionMutationDepth--
            undoStack.clear()
            undoStack.addAll(undo)
            redoStack.clear()
            redoStack.addAll(redo)
        }
        if (!result || source == currentSource) {
            currentSource = source
            selectedNodeId = primary
            selectedNodeIds = selection
            return false
        }
        undoStack += EditorHistoryEntry(source)
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
        pruneSelection()
        return true
    }

    private fun pruneSelection() {
        selectedNodeIds = selectedNodeIds.intersect(rows.map { it.nodeId }.toSet())
        if (selectedNodeId !in selectedNodeIds) {
            selectionMutationDepth++
            try { selectedNodeId = selectedNodeIds.lastOrNull() } finally { selectionMutationDepth-- }
        }
    }

    fun copySelection(): List<VisualSubtreeClipboard> {
        val primary = selectedNodeId
        selectionMutationDepth++
        return try {
            selectedRoots().mapNotNull { id ->
                selectedNodeId = id
                copySelectedSubtree()
            }
        } finally {
            selectedNodeId = primary
            selectionMutationDepth--
        }
    }

    fun deleteSelection(): Boolean {
        val roots = selectedRoots()
        if (roots.isEmpty()) return false
        return batchMutation { deleteRoots(roots.toSet()) }
    }

    fun isNodeDisabled(nodeId: String, inherited: Boolean = true): Boolean {
        val document = editableDocument() ?: return false
        val byId = document.nodes.associateBy { it.nodeId }
        var node = byId[nodeId]
        val seen = mutableSetOf<String>()
        while (node != null && seen.add(node.nodeId)) {
            if (node.json.get("disabled")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return true
            if (!inherited) return false
            node = byId[node.parentId]
        }
        return false
    }

    /** Capture strings/flags only on the owner thread; the worker never touches editor state. */
    fun variableCheckInput(): VisualVariableCheckInput = VisualVariableCheckInput(currentSource, rootBlockId, isReadOnly)

    fun toggleDisabledSelection(): Boolean {
        val roots = selectedRoots()
        if (roots.isEmpty()) return false
        val document = editableDocument() ?: return false
        val enable = roots.all { isNodeDisabled(it, inherited = false) }
        document.nodes.filter { it.nodeId in roots }.forEach { node ->
            if (enable) node.json.remove("disabled") else node.json.addProperty("disabled", true)
        }
        return commit(document)
    }

    fun nodeDisplay(nodeId: String): com.autoscript.studio.compiler.NodeDisplaySettings =
        com.autoscript.studio.compiler.NodeDisplaySettings.parse(editableDocument()?.nodes
            ?.firstOrNull { it.nodeId == nodeId }?.json?.get("display"))

    /** One document parse per list projection, not several full parses for every row. */
    fun programDisplayNodes(flowNames: Map<String, String>? = null): List<EditorProgramNode> {
        if (displayCacheSource == currentSource && displayCacheNames == flowNames) return displayCache
        val parsed = if (isReadOnly) null else parseDocument(currentSource, rootBlockId)
        val byId = parsed?.editable?.nodes.orEmpty().associateBy { it.nodeId }
        val disabledById = mutableMapOf<String, Boolean>()
        val projection = (parsed?.rows ?: rows).map { row ->
            val node = byId[row.nodeId]
            val ownDisabled = node?.json?.get("disabled")?.asBoolean == true
            // Projection is parent-before-child and validated acyclic.
            val inheritedDisabled = node?.parentId?.let { disabledById[it] } == true
            disabledById[row.nodeId] = ownDisabled || inheritedDisabled
            val settings = com.autoscript.studio.compiler.NodeDisplaySettings.parse(node?.json?.get("display"))
            val contract = BlockCatalog.find(row.kind)
            val args = node?.json?.getAsJsonObject("args")
            val randomization = randomizationRowSuffix(node?.json?.getAsJsonObject("randomization"), row.kind)
            val defaultLabel = compactProgramLabel(contract, args, row.kind, flowNames) + randomization
            val rowLabel = compactProgramLabel(contract, args, row.kind, flowNames, rowSummary = true) + randomization
            val branch = row.childSlot?.let(::childBlockLabel)
            val parent = node?.parentId?.let(byId::get)
            val branchHasHeading = row.childSlot in editorBranchSlots(parent?.json?.get("kind")?.asString.orEmpty(),
                parent?.childBlocks?.keys?.toList().orEmpty())
            EditorProgramNode(row.nodeId, (branch?.let { "[$it] " } ?: "") + applyProgramDisplayLabel(defaultLabel, settings),
                row.kind, row.depth, node?.childBlocks?.keys?.toList().orEmpty(), ownDisabled,
                !ownDisabled && inheritedDisabled,
                displayProgramRowText(rowLabel, settings, branch, ownDisabled, inheritedDisabled,
                    branchHasHeading = branchHasHeading),
                childSlot = row.childSlot,
                fullDisplayRow = displayProgramRowText(defaultLabel, settings, branch, ownDisabled, inheritedDisabled, branchHasHeading))
        }
        if (parsed != null && rowCacheSource != currentSource) {
            rowCache = java.util.Collections.unmodifiableList(parsed.rows)
            rowCacheSource = currentSource
        }
        displayCache = java.util.Collections.unmodifiableList(projection)
        displayCacheNames = flowNames?.toMap()
        displayCacheSource = currentSource
        return displayCache
    }

    fun updateNodeDisplay(nodeId: String, settings: com.autoscript.studio.compiler.NodeDisplaySettings): Boolean {
        if (!settings.isValid()) return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == nodeId } ?: return false
        if (settings.alias.isBlank()) node.json.remove("display") else node.json.add("display", settings.toJson())
        val candidate = document.serialize(rootBlockId) ?: return false
        if (candidate.toByteArray(Charsets.UTF_8).size > com.autoscript.studio.compiler.VisualLuaSource.MAX_BYTES) return false
        return commit(document)
    }

    fun updateNodeRandomization(nodeId: String, settings: JsonObject?): Boolean {
        if (runCatching { com.autoscript.studio.compiler.NodeRandomizationSettings.validate(settings) }.isFailure) return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == nodeId } ?: return false
        if (!com.autoscript.studio.compiler.NodeRandomizationSettings.supports(node.json["kind"].asString)) return false
        if (settings == null) node.json.remove("randomization") else node.json.add("randomization", settings.deepCopy())
        val candidate = document.serialize(rootBlockId) ?: return false
        if (candidate.toByteArray(Charsets.UTF_8).size > com.autoscript.studio.compiler.VisualLuaSource.MAX_BYTES) return false
        return commit(document)
    }

    /** Literal text only: identifiers, expressions, paths and JSON structure are never rewritten. */
    fun replaceDisplayText(query: String, replacement: String, selectionOnly: Boolean): Int {
        if (query.isEmpty() || query.length > 80 || replacement.toByteArray(Charsets.UTF_8).size > 2048 || query == replacement) return 0
        val document = editableDocument() ?: return 0
        val allowed = if (selectionOnly) copySelection().flatMap { it.nodes }.map { it.get("nodeId").asString }.toSet() else null
        var changed = 0
        document.nodes.forEach { node ->
            if (allowed != null && node.nodeId !in allowed) return@forEach
            val args = node.json.getAsJsonObject("args") ?: return@forEach
            var nodeChanged = false
            listOf("message", "value").forEach { key ->
                val value = args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@forEach
                val next = value.replace(query, replacement)
                if (next.toByteArray(Charsets.UTF_8).size > 2048) return 0
                if (next != value) { args.addProperty(key, next); nodeChanged = true }
            }
            if (nodeChanged) changed++
        }
        return if (changed > 0 && commit(document)) changed else 0
    }

    fun moveSelection(offset: Int): Boolean {
        val roots = selectedRoots().let { if (offset > 0) it.reversed() else it }
        if (roots.isEmpty()) return false
        return batchMutation { roots.all { id -> selectedNodeId = id; moveSelected(offset) } }
    }

    /** Only a contiguous sibling selection can move into its preceding container. */
    fun indentSlotsForSelection(): List<String> {
        val roots = selectedRoots()
        val selected = rows.filter { it.nodeId in roots }
        if (roots.isEmpty() || selected.map { it.blockId }.distinct().size != 1) return emptyList()
        val siblings = rows.filter { it.blockId == selected.first().blockId }
        val indices = siblings.indices.filter { siblings[it].nodeId in roots }
        if (indices.last() - indices.first() + 1 != roots.size) return emptyList()
        val previous = siblings.getOrNull(indices.first() - 1) ?: return emptyList()
        return childBlockNames(previous.nodeId)
    }

    fun indentSelection(slot: String? = null): Boolean {
        val roots = selectedRoots()
        val slots = indentSlotsForSelection()
        if (slots.isEmpty() || (slot != null && slot !in slots)) return false
        return batchMutation { roots.all { id -> selectedNodeId = id; indentSelected(slot) } }
    }

    fun outdentSelection(): Boolean {
        val roots = selectedRoots().reversed()
        if (roots.isEmpty()) return false
        return batchMutation { roots.all { id -> selectedNodeId = id; outdentSelected() } }
    }

    fun pasteSelection(clipboards: List<VisualSubtreeClipboard>, slot: String? = null, beforeSelection: Boolean = false): Boolean {
        if (clipboards.isEmpty() || clipboards.size > 256) return false
        if (beforeSelection && slot != null) return false
        val target = if (beforeSelection) selectedRoots().firstOrNull() ?: selectedNodeId else selectedNodeId
        val inserted = mutableListOf<String>()
        val remapping = mutableMapOf<String, String>()
        return batchMutation {
            val success = clipboards.all { clipboard ->
                if (slot != null || beforeSelection) selectedNodeId = target
                pasteSubtree(clipboard, intoChildBlockName = slot, remappedIds = remapping,
                    beforeNodeId = target.takeIf { beforeSelection })?.let { inserted += it; true } ?: false
            }
            if (!success) false else {
                // References between independently selected roots must point to their copies too.
                val document = editableDocument() ?: return@batchMutation false
                val copiedIds = remapping.values.toSet()
                document.nodes.indices.forEach { index ->
                    val node = document.nodes[index]
                    if (node.nodeId in copiedIds) {
                        val rewritten = rewriteDeclaredJsonReferences(node.json, remapping).asJsonObject
                        // Structural identities were already assigned by pasteSubtree. Inserting
                        // a container inside its original branch must not remap the destination
                        // parent/block into the copy, creating a cycle or moving it into itself.
                        for (key in listOf("nodeId", "blockId", "parentId", "childBlocks")) {
                            node.json.get(key)?.let { rewritten.add(key, it.deepCopy()) } ?: rewritten.remove(key)
                        }
                        document.nodes[index] = EditableNode(rewritten)
                    }
                }
                selectedNodeIds = inserted.toSet()
                if (beforeSelection) selectedNodeId = inserted.firstOrNull()
                document.serialize(rootBlockId) == currentSource || commit(document)
            }
        }
    }

    /** Replace only contiguous siblings, keeping the surrounding tree and a single undo boundary. */
    fun replaceSelectionWith(clipboards: List<VisualSubtreeClipboard>): Boolean {
        val roots = selectedRoots()
        val selected = rows.filter { it.nodeId in roots }
        if (selected.isEmpty() || selected.map { it.blockId }.distinct().size != 1) return false
        val siblings = rows.filter { it.blockId == selected.first().blockId }
        val indices = siblings.indices.filter { siblings[it].nodeId in roots }
        if (indices.last() - indices.first() + 1 != roots.size) return false
        return batchMutation {
            selectOnly(roots.first())
            if (clipboards.isNotEmpty() && !pasteSelection(clipboards, beforeSelection = true)) return@batchMutation false
            val inserted = selectedNodeIds.toSet().takeIf { clipboards.isNotEmpty() }.orEmpty()
            if (!deleteRoots(roots.toSet())) return@batchMutation false
            if (inserted.isNotEmpty()) selectNodes(inserted)
            true
        }
    }
    val isReadOnly: Boolean = forceReadOnly || parseDocument(migratedSource, rootBlockId) == null
    private val undoStack = mutableListOf<EditorHistoryEntry>()
    private val redoStack = mutableListOf<EditorHistoryEntry>()
    internal val undoGroup: Long? get() = undoStack.lastOrNull()?.group
    internal val redoGroup: Long? get() = redoStack.lastOrNull()?.group

    internal fun historyGroups(): Set<Long> = (undoStack + redoStack).mapNotNull { it.group }.toSet()
    internal fun checkpoint() = EditorStateCheckpoint(currentSource, selectedNodeId, selectedNodeIds.toSet(), undoStack.toList(), redoStack.toList())
    internal fun restoreCheckpoint(state: EditorStateCheckpoint) {
        currentSource = state.source
        selectionMutationDepth++
        try { selectedNodeId = state.primary; selectedNodeIds = state.selection }
        finally { selectionMutationDepth-- }
        undoStack.clear(); undoStack.addAll(state.undo)
        redoStack.clear(); redoStack.addAll(state.redo)
    }
    internal fun tagLastUndo(group: Long) {
        check(undoStack.isNotEmpty())
        undoStack[undoStack.lastIndex] = undoStack.last().copy(group = group)
    }
    internal fun canReplaceSource(expected: String, candidate: String): Boolean = !isReadOnly && currentSource == expected &&
        candidate.toByteArray(Charsets.UTF_8).size <= 1_048_576 && parseDocument(candidate, rootBlockId) != null
    // Cache immutable view data only; mutations always obtain their own editable document.
    private var rowCacheSource: String? = null
    private var rowCache: List<VisualNodeRow> = emptyList()
    private var displayCacheSource: String? = null
    private var displayCacheNames: Map<String, String>? = null
    private var displayCache: List<EditorProgramNode> = emptyList()

    val isDirty: Boolean get() = currentSource != savedSource
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val rows: List<VisualNodeRow>
        get() {
            if (rowCacheSource != currentSource) {
                rowCache = java.util.Collections.unmodifiableList(parseDocument(currentSource, rootBlockId)?.rows
                    ?: parseVisualNodes(currentSource, rootBlockId))
                rowCacheSource = currentSource
            }
            return rowCache
        }

    fun insertNoop(afterNodeId: String? = selectedNodeId): String? = insertNode(
        kind = "task.noop",
        nodeVersion = 1,
        args = JsonObject(),
        childBlockNames = emptyList(),
        afterNodeId = afterNodeId,
    )

    fun insertBlock(
        contract: BlockContract,
        args: JsonObject,
        afterNodeId: String? = selectedNodeId,
        intoChildBlockName: String? = null,
    ): String? = insertNode(
        kind = contract.kind,
        nodeVersion = contract.nodeVersion,
        args = args,
        childBlockNames = contract.childBlocks,
        afterNodeId = afterNodeId,
        intoChildBlockName = intoChildBlockName,
    )

    /** Insert related leaf blocks atomically and expose the whole batch as one undo step. */
    fun stageBlockInsertion(blocks: List<Pair<BlockContract, JsonObject>>, position: EditorInsertPosition,
        anchorNodeId: String?, childSlot: String?): String? {
        if (isReadOnly) return null
        val candidate = insertionCandidate()
        return if (candidate.insertBlocks(blocks, position, anchorNodeId, childSlot)) candidate.currentSource else null
    }

    private fun insertionCandidate(): VisualEditorState = create(currentSource, rootBlockId, idFactory).also {
        // Staging is isolated, but even discarded candidates must reserve IDs across all plugins.
        it.projectIds = projectIds
    }

    /** Stage a bounded ordinary block forest; only the caller's validated final source is committed. */
    fun stageTreeInsertion(trees: List<VisualBlockTree>, position: EditorInsertPosition,
        anchor: String?, childSlot: String?): String? {
        if (isReadOnly || trees.isEmpty() || position == EditorInsertPosition.REPLACE) return null
        var count = 0
        fun valid(tree: VisualBlockTree, depth: Int): Boolean {
            if (++count > 256 || depth > 32 || !tree.contract.childBlocks.containsAll(tree.children.keys)) return false
            return tree.children.values.flatten().all { valid(it, depth + 1) }
        }
        if (!trees.all { valid(it, 0) }) return null
        val candidate = insertionCandidate()
        fun add(tree: VisualBlockTree, after: String?, slot: String?): String? {
            val id = candidate.insertBlock(tree.contract, tree.arguments, after, slot) ?: return null
            for ((name, children) in tree.children) {
                var previous: String? = id
                children.forEachIndexed { index, child ->
                    previous = add(child, previous, if (index == 0) name else null) ?: return null
                }
            }
            return id
        }
        if (position != EditorInsertPosition.LIST_BOTTOM && anchor != null && rows.none { it.nodeId == anchor }) return null
        if (position in setOf(EditorInsertPosition.ABOVE, EditorInsertPosition.INSIDE) && anchor == null) return null
        if (position == EditorInsertPosition.INSIDE && childSlot !in childBlockNames(requireNotNull(anchor))) return null
        var previous = if (position == EditorInsertPosition.LIST_BOTTOM) null else anchor
        trees.forEachIndexed { index, tree ->
            val id = add(tree, previous, if (index == 0 && position == EditorInsertPosition.INSIDE) childSlot else null) ?: return null
            if (index == 0 && position == EditorInsertPosition.ABOVE) {
                candidate.selectedNodeId = id
                if (!candidate.moveSelected(-1)) return null
            }
            previous = id
        }
        return candidate.currentSource
    }

    fun insertBlocks(
        blocks: List<Pair<BlockContract, JsonObject>>,
        position: EditorInsertPosition = EditorInsertPosition.BELOW,
        anchorNodeId: String? = selectedNodeId,
        intoChildBlockName: String? = null,
    ): Boolean {
        if (blocks.isEmpty()) return true
        if (blocks.size > 256 || position == EditorInsertPosition.REPLACE) return false
        if (position != EditorInsertPosition.LIST_BOTTOM && anchorNodeId != null && rows.none { it.nodeId == anchorNodeId }) return false
        if (position in setOf(EditorInsertPosition.ABOVE, EditorInsertPosition.INSIDE) && anchorNodeId == null) return false
        if (position == EditorInsertPosition.INSIDE && intoChildBlockName !in childBlockNames(requireNotNull(anchorNodeId))) return false
        val sourceBefore = currentSource
        val selectionBefore = selectedNodeId
        val selectionsBefore = selectedNodeIds
        val undoBefore = undoStack.toList()
        val redoBefore = redoStack.toList()
        fun rollback(): Boolean {
            currentSource = sourceBefore
            selectedNodeId = selectionBefore
            selectedNodeIds = selectionsBefore
            undoStack.clear()
            undoStack.addAll(undoBefore)
            redoStack.clear()
            redoStack.addAll(redoBefore)
            return false
        }
        var previous = if (position == EditorInsertPosition.LIST_BOTTOM) null else anchorNodeId
        for ((index, block) in blocks.withIndex()) {
            val (contract, arguments) = block
            val inserted = try { insertBlock(contract, arguments, afterNodeId = previous,
                intoChildBlockName = if (index == 0 && position == EditorInsertPosition.INSIDE) intoChildBlockName else null)
            } catch (_: Exception) { return rollback() }
            val positioned = try { inserted != null && (index != 0 || position != EditorInsertPosition.ABOVE || moveSelected(-1))
            } catch (_: Exception) { return rollback() }
            if (!positioned) return rollback()
            previous = inserted
        }
        // Each insertBlock commits through the regular path. Collapse those history entries so
        // one Undo removes the entire gesture instead of leaving an orphaned pointer-down.
        undoStack.clear()
        undoStack.addAll(undoBefore)
        undoStack += EditorHistoryEntry(sourceBefore)
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
        return true
    }

    fun replaceSelectedBlock(contract: BlockContract, args: JsonObject): Boolean {
        val selected = selectedNodeId ?: return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == selected } ?: return false
        if (contract.kind in positionLoopKinds && nearestAncestorOfKind(node.parentId, setOf("control.repeat", "control.while", "control.cycle")) == null) return false
        val removedNodeIds = mutableSetOf<String>()
        fun collectBlock(blockId: String) {
            document.nodes.filter { it.blockId == blockId }.forEach { child ->
                if (removedNodeIds.add(child.nodeId)) child.childBlocks.values.forEach(::collectBlock)
            }
        }
        node.childBlocks.values.forEach(::collectBlock)
        document.nodes.removeAll { it.nodeId in removedNodeIds }
        val usedBlockIds = document.nodes.flatMapTo(mutableSetOf()) { candidate ->
            listOf(candidate.blockId) + candidate.childBlocks.values
        }.apply { add(rootBlockId) }
        val childBlocks = contract.childBlocks.associateWith {
            uniqueId("block", usedBlockIds).also(usedBlockIds::add)
        }
        node.json.addProperty("kind", contract.kind)
        node.json.addProperty("nodeVersion", contract.nodeVersion)
        node.json.add("args", args.deepCopy())
        if (childBlocks.isEmpty()) node.json.remove("childBlocks") else {
            node.json.add("childBlocks", JsonObject().apply { childBlocks.forEach(::addProperty) })
        }
        return commit(document)
    }

    fun insertFlowCall(
        targetFlowId: String,
        arguments: JsonObject = JsonObject(),
        afterNodeId: String? = selectedNodeId,
    ): String? {
        val args = JsonObject().apply {
            addProperty("targetFlowId", targetFlowId)
            add("arguments", arguments.deepCopy())
        }
        return insertNode("flow.call", 1, args, emptyList(), afterNodeId)
    }

    fun deleteSelected(): Boolean {
        val selected = selectedNodeId ?: return false
        return deleteRoots(setOf(selected))
    }

    private fun deleteRoots(selected: Set<String>): Boolean {
        val previousRows = rows
        val previousIndex = previousRows.indexOfFirst { it.nodeId in selected }
        val document = editableDocument() ?: return false
        val roots = document.nodes.filter { it.nodeId in selected }
        if (roots.size != selected.size) return false
        val byBlock = document.nodes.groupBy { it.blockId }
        val ownedBlocks = mutableSetOf<String>()
        val removedNodes = selected.toMutableSet()
        fun collect(node: EditableNode) {
            node.childBlocks.values.forEach { blockId ->
                if (!ownedBlocks.add(blockId)) return@forEach
                byBlock[blockId].orEmpty().forEach { child ->
                    removedNodes.add(child.nodeId)
                    collect(child)
                }
            }
        }
        roots.forEach(::collect)
        document.nodes.removeAll { it.nodeId in removedNodes }
        if (!commit(document)) return false
        val remainingRows = rows
        selectedNodeId = when {
            remainingRows.isEmpty() -> null
            previousIndex > 0 -> remainingRows[(previousIndex - 1).coerceAtMost(remainingRows.lastIndex)].nodeId
            else -> remainingRows.first().nodeId
        }
        return true
    }

    fun moveSelected(offset: Int): Boolean {
        require(offset == -1 || offset == 1)
        val selected = selectedNodeId ?: return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == selected } ?: return false
        val siblings = document.nodes.filter { it.blockId == node.blockId }.sortedBy { it.orderKey }
        val from = siblings.indexOfFirst { it.nodeId == selected }
        val to = from + offset
        if (from < 0 || to !in siblings.indices) return false
        val reordered = siblings.toMutableList().apply {
            val moving = removeAt(from)
            add(to, moving)
        }
        rebalance(reordered)
        return commit(document)
    }

    fun indentSelected(childBlockName: String? = null): Boolean {
        val selected = selectedNodeId ?: return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == selected } ?: return false
        val siblings = document.nodes.filter { it.blockId == node.blockId }.sortedBy { it.orderKey }
        val index = siblings.indexOfFirst { it.nodeId == selected }
        val owner = siblings.getOrNull(index - 1) ?: return false
        val targetBlock = childBlockName?.let(owner.childBlocks::get)
            ?: owner.childBlocks.entries.sortedBy { it.key }.firstOrNull()?.value
            ?: return false
        val targetSiblings = document.nodes.filter { it.blockId == targetBlock }.sortedBy { it.orderKey }
        val key = appendOrderKey(targetSiblings) ?: return false
        node.json.addProperty("blockId", targetBlock)
        node.json.addProperty("parentId", owner.nodeId)
        node.orderKey = key
        return commit(document)
    }

    fun outdentSelected(): Boolean {
        val selected = selectedNodeId ?: return false
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == selected } ?: return false
        val owner = node.parentId?.let { parent -> document.nodes.firstOrNull { it.nodeId == parent } }
            ?: return false
        val destination = document.nodes.filter { it.blockId == owner.blockId }.sortedBy { it.orderKey }
        val ownerIndex = destination.indexOfFirst { it.nodeId == owner.nodeId }
        if (ownerIndex < 0) return false
        var key = between(owner.orderKey, destination.getOrNull(ownerIndex + 1)?.orderKey)
        if (key == null) {
            rebalance(destination)
            key = between(owner.orderKey, destination.getOrNull(ownerIndex + 1)?.orderKey) ?: return false
        }
        node.json.addProperty("blockId", owner.blockId)
        node.json.add("parentId", owner.parentId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        node.orderKey = key
        return commit(document)
    }

    fun copySelectedSubtree(): VisualSubtreeClipboard? {
        val selected = selectedNodeId ?: return null
        val document = editableDocument() ?: return null
        val root = document.nodes.firstOrNull { it.nodeId == selected } ?: return null
        val included = linkedSetOf(root.nodeId)
        fun collect(owner: EditableNode) {
            owner.childBlocks.values.forEach { blockId ->
                document.nodes.filter { it.blockId == blockId }.sortedBy { it.orderKey }.forEach { child ->
                    if (included.add(child.nodeId)) collect(child)
                }
            }
        }
        collect(root)
        return VisualSubtreeClipboard(
            rootNodeId = root.nodeId,
            nodes = document.nodes.filter { it.nodeId in included }.map { it.json.deepCopy() },
        )
    }

    fun pasteSubtree(
        clipboard: VisualSubtreeClipboard,
        afterNodeId: String? = selectedNodeId,
        intoChildBlockName: String? = null,
        remappedIds: MutableMap<String, String>? = null,
        beforeNodeId: String? = null,
    ): String? {
        val document = editableDocument() ?: return null
        val sourceNodes = clipboard.nodes.map(::EditableNode)
        val sourceRoot = sourceNodes.singleOrNull { it.nodeId == clipboard.rootNodeId } ?: return null
        if (beforeNodeId != null && intoChildBlockName != null) return null
        val selected = (beforeNodeId ?: afterNodeId)?.let { id -> document.nodes.firstOrNull { it.nodeId == id } }
        if (beforeNodeId != null && selected == null) return null
        val targetChildBlock = intoChildBlockName?.let { selected?.childBlocks?.get(it) }
            ?: if (intoChildBlockName == null) null else return null
        val after = if (targetChildBlock == null) selected else null
        val targetBlock = targetChildBlock ?: after?.blockId ?: rootBlockId
        val targetParent = if (targetChildBlock != null) selected?.nodeId else after?.parentId
        val siblings = document.nodes.filter { it.blockId == targetBlock }.sortedBy { it.orderKey }
        val insertionIndex = after?.let { sibling ->
            siblings.indexOfFirst { it.nodeId == sibling.nodeId }.takeIf { it >= 0 }?.plus(if (beforeNodeId == null) 1 else 0)
        } ?: siblings.size
        var rootOrder = between(
            siblings.getOrNull(insertionIndex - 1)?.orderKey,
            siblings.getOrNull(insertionIndex)?.orderKey,
        )
        if (rootOrder == null) {
            rebalance(siblings)
            rootOrder = between(
                siblings.getOrNull(insertionIndex - 1)?.orderKey,
                siblings.getOrNull(insertionIndex)?.orderKey,
            ) ?: return null
        }

        val existingNodeIds = document.nodes.mapTo(mutableSetOf(), EditableNode::nodeId)
        val existingBlockIds = document.nodes.flatMapTo(mutableSetOf()) { node ->
            listOf(node.blockId) + node.childBlocks.values
        }.apply { add(rootBlockId) }
        val nodeIds = sourceNodes.associate { source ->
            source.nodeId to uniqueId("node", existingNodeIds).also(existingNodeIds::add)
        }
        val ownedBlocks = sourceNodes.flatMap { it.childBlocks.values }.distinct()
        val blockIds = ownedBlocks.associateWith {
            uniqueId("block", existingBlockIds).also(existingBlockIds::add)
        }
        val references = nodeIds + blockIds
        val copies = sourceNodes.map { source ->
            val json = rewriteDeclaredJsonReferences(source.json, references).asJsonObject
            json.addProperty("nodeId", nodeIds.getValue(source.nodeId))
            if (source.nodeId == sourceRoot.nodeId) {
                json.addProperty("blockId", targetBlock)
                json.add("parentId", targetParent?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                json.addProperty("orderKey", rootOrder)
            } else {
                json.addProperty("blockId", blockIds[source.blockId] ?: return null)
                json.addProperty("parentId", nodeIds[source.parentId] ?: return null)
            }
            EditableNode(json)
        }
        document.nodes += copies
        if (!commit(document)) return null
        remappedIds?.putAll(references)
        return nodeIds.getValue(sourceRoot.nodeId).also { selectedNodeId = it }
    }

    fun updateArguments(nodeId: String, arguments: JsonObject): Boolean {
        val document = editableDocument() ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == nodeId } ?: return false
        node.json.add("args", arguments.deepCopy())
        // Legacy recognition leaves gain empty branches only on an explicit parameter save.
        if (node.kind in setOf("vision.findimage", "vision.findgray") && node.childBlocks.isEmpty()) {
            val usedIds = document.nodes.flatMapTo(mutableSetOf()) { listOf(it.nodeId, it.blockId) + it.childBlocks.values }
                .apply { add(rootBlockId) }
            val branches = JsonObject()
            listOf("success", "failure").forEach { slot ->
                val id = uniqueId("block", usedIds).also(usedIds::add)
                branches.addProperty(slot, id)
            }
            node.json.add("childBlocks", branches)
        }
        return commit(document)
    }

    /**
     * Returns the selected node itself or its closest structural owner with one
     * of [kinds].  It deliberately follows `parentId`, never visual row depth,
     * so metrics stay correct after indent/outdent and source migration.
     */
    fun nearestAncestorOfKind(nodeId: String? = selectedNodeId, kinds: Set<String>): String? {
        val document = editableDocument() ?: return null
        val byId = document.nodes.associateBy(EditableNode::nodeId)
        var current = nodeId?.let(byId::get)
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.nodeId)) {
            if (current.kind in kinds) return current.nodeId
            current = current.parentId?.let(byId::get)
        }
        return null
    }

    /** Adds one native `elseif` branch to a selected conditional node. */
    fun addElseIfBranch(arguments: JsonObject, nodeId: String? = selectedNodeId): Boolean {
        if (!validConfiguredConditionArguments(arguments)) return false
        val document = editableDocument() ?: return false
        val ownerId = nodeId ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == ownerId && it.kind == "control.if" }
            ?: return false
        val args = node.json.getAsJsonObject("args")?.deepCopy() ?: return false
        val branches = args.getAsJsonArray("elseIf")?.deepCopy() ?: JsonArray()
        if (branches.size() >= MAX_ELSE_IF_BRANCHES) return false
        branches.add(arguments.deepCopy())
        args.add("elseIf", branches)
        val existingBlockIds = document.nodes.flatMapTo(mutableSetOf()) { candidate ->
            listOf(candidate.blockId) + candidate.childBlocks.values
        }.apply { add(rootBlockId) }
        val childBlocks = node.json.getAsJsonObject("childBlocks")?.deepCopy() ?: return false
        childBlocks.addProperty("elseIf${branches.size() - 1}", uniqueId("block", existingBlockIds))
        node.json.add("args", args)
        node.json.add("childBlocks", childBlocks)
        return commit(document)
    }

    /** Removes the final `elseif` branch and all nodes owned by it. */
    fun removeLastElseIfBranch(nodeId: String? = selectedNodeId): Boolean {
        val document = editableDocument() ?: return false
        val ownerId = nodeId ?: return false
        val node = document.nodes.firstOrNull { it.nodeId == ownerId && it.kind == "control.if" }
            ?: return false
        val args = node.json.getAsJsonObject("args")?.deepCopy() ?: return false
        val branches = args.getAsJsonArray("elseIf")?.deepCopy() ?: return false
        if (branches.size() == 0) return false
        val index = branches.size() - 1
        val childBlocks = node.json.getAsJsonObject("childBlocks")?.deepCopy() ?: return false
        val ownedBlock = childBlocks.get("elseIf$index")?.asString ?: return false
        val removed = mutableSetOf<String>()
        fun collect(blockId: String) {
            document.nodes.filter { it.blockId == blockId }.forEach { child ->
                if (removed.add(child.nodeId)) child.childBlocks.values.forEach(::collect)
            }
        }
        collect(ownedBlock)
        document.nodes.removeAll { it.nodeId in removed }
        branches.remove(index)
        if (branches.size() == 0) args.remove("elseIf") else args.add("elseIf", branches)
        childBlocks.remove("elseIf$index")
        node.json.add("args", args)
        node.json.add("childBlocks", childBlocks)
        return commit(document)
    }

    fun nodeArguments(nodeId: String): JsonObject? = editableDocument()?.nodes
        ?.firstOrNull { it.nodeId == nodeId }
        ?.json?.getAsJsonObject("args")?.deepCopy()

    fun childBlockNames(nodeId: String): List<String> = editableDocument()?.nodes
        ?.firstOrNull { it.nodeId == nodeId }
        ?.childBlocks?.keys?.toList().orEmpty()

    fun undo(group: Long? = null): Boolean {
        if (undoStack.isEmpty()) return false
        if (undoGroup != null && undoGroup != group) return false
        val entry = undoStack.removeAt(undoStack.lastIndex)
        redoStack += EditorHistoryEntry(currentSource, entry.group)
        currentSource = entry.source
        pruneSelection()
        return true
    }

    fun redo(group: Long? = null): Boolean {
        if (redoStack.isEmpty()) return false
        if (redoGroup != null && redoGroup != group) return false
        val entry = redoStack.removeAt(redoStack.lastIndex)
        undoStack += EditorHistoryEntry(currentSource, entry.group)
        currentSource = entry.source
        pruneSelection()
        return true
    }

    /** Raw editing preserves undo and rejects a changed draft; callers must compile-validate first. */
    fun replaceRawSource(expected: String, replacement: String): String? {
        if (isReadOnly) return "当前插件只读"
        if (currentSource != expected) return "插件已变化，请重新打开源码编辑，避免覆盖新的修改"
        if (replacement.toByteArray(Charsets.UTF_8).size > 1_048_576) return "源码超过 1 MiB"
        val document = parseDocument(replacement, rootBlockId)?.editable ?: return "JSONL 或积木层级结构无效"
        commit(document)
        pruneSelection()
        return null
    }

    /** Marks exactly the submitted snapshot saved; later edits remain dirty. */
    fun markSaved(submittedSource: String) {
        savedSource = submittedSource
    }

    /** A project-wide rename is a committed transaction, not an undoable single-node mutation. */
    fun reloadCommittedSource(source: String) {
        projectIds?.reserveSource(source)
        currentSource = source
        savedSource = source
        undoStack.clear()
        redoStack.clear()
        pruneSelection()
    }

    private fun insertNode(
        kind: String,
        nodeVersion: Int,
        args: JsonObject,
        childBlockNames: List<String>,
        afterNodeId: String?,
        intoChildBlockName: String? = null,
    ): String? {
        val document = editableDocument() ?: return null
        val selected = afterNodeId?.let { id -> document.nodes.firstOrNull { it.nodeId == id } }
        val childBlockId = intoChildBlockName?.let { name -> selected?.childBlocks?.get(name) }
            ?: if (intoChildBlockName == null) null else return null
        val after = if (childBlockId == null) selected else null
        val blockId = childBlockId ?: after?.blockId ?: rootBlockId
        val parentId = if (childBlockId != null) selected?.nodeId else after?.parentId
        if (kind in positionLoopKinds && nearestAncestorOfKind(parentId, setOf("control.repeat", "control.while", "control.cycle")) == null) return null
        val siblings = document.nodes.filter { it.blockId == blockId }.sortedBy { it.orderKey }
        val insertionIndex = after?.let { selected ->
            siblings.indexOfFirst { it.nodeId == selected.nodeId }.takeIf { it >= 0 }?.plus(1)
        } ?: siblings.size
        var previous = siblings.getOrNull(insertionIndex - 1)?.orderKey
        var next = siblings.getOrNull(insertionIndex)?.orderKey
        var key = between(previous, next)
        if (key == null) {
            rebalance(siblings)
            previous = siblings.getOrNull(insertionIndex - 1)?.orderKey
            next = siblings.getOrNull(insertionIndex)?.orderKey
            key = requireNotNull(between(previous, next))
        }
        val nodeId = uniqueId("node", document.nodes.mapTo(mutableSetOf()) { it.nodeId })
        val blockIds = document.nodes.flatMapTo(mutableSetOf()) { node ->
            listOf(node.blockId) + node.childBlocks.values
        }.apply { add(rootBlockId) }
        val childBlocks = childBlockNames.associateWith {
            uniqueId("block", blockIds).also(blockIds::add)
        }
        val json = JsonObject().apply {
            addProperty("flowSchemaVersion", 1)
            addProperty("nodeId", nodeId)
            addProperty("blockId", blockId)
            add("parentId", parentId?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            addProperty("orderKey", key)
            addProperty("kind", kind)
            addProperty("nodeVersion", nodeVersion)
            if (childBlocks.isNotEmpty()) {
                add("childBlocks", JsonObject().apply {
                    childBlocks.forEach(::addProperty)
                })
            }
            addProperty("depth", 0)
            add("args", args.deepCopy())
        }
        document.nodes += EditableNode(json)
        if (!commit(document)) return null
        selectedNodeId = nodeId
        return nodeId
    }

    private fun uniqueId(prefix: String, existing: Set<String>): String {
        val first = idFactory()
        if (first.isBlank()) return allocateProjectIdentity(prefix, existing)
        if ("$prefix-$first" !in existing) return "$prefix-$first"
        repeat(32) {
            val candidate = "$prefix-${idFactory()}"
            if (candidate !in existing) return candidate
        }
        error("无法生成唯一节点 ID")
    }

    fun allocateProjectIdentity(prefix: String, existing: Set<String>): String =
        projectIds?.allocate(prefix, existing) ?: CompactId.nextNumeric(prefix, existing)

    private fun appendOrderKey(siblings: List<EditableNode>): String? {
        var key = between(siblings.lastOrNull()?.orderKey, null)
        if (key == null) {
            rebalance(siblings)
            key = between(siblings.lastOrNull()?.orderKey, null)
        }
        return key
    }

    private fun editableDocument(): EditableDocument? =
        if (isReadOnly) null else parseDocument(currentSource, rootBlockId)?.editable

    private fun commit(document: EditableDocument): Boolean {
        val next = document.serialize(rootBlockId) ?: return false
        if (next == currentSource) return false
        projectIds?.reserveIds(document.nodes.flatMap { listOf(it.nodeId, it.blockId) + it.childBlocks.values } + rootBlockId)
        undoStack += EditorHistoryEntry(currentSource)
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
        currentSource = next
        return true
    }

    companion object {
        fun createProjectScoped(source: String, rootBlockId: String, identities: VisualProjectIds): VisualEditorState =
            create(source, rootBlockId).also {
                identities.reserveSource(it.currentSource)
                identities.reserveIds(listOf(rootBlockId))
                it.projectIds = identities
            }

        fun create(
            source: String,
            rootBlockId: String,
            idFactory: () -> String = { "" },
        ): VisualEditorState = when (val migration = BlockMigrationEngine.migrateFlowSource(source)) {
            is FlowMigrationResult.Success -> VisualEditorState(
                source,
                migration.source,
                rootBlockId,
                idFactory,
                forceReadOnly = false,
            )
            is FlowMigrationResult.Failure -> VisualEditorState(
                source,
                source,
                rootBlockId,
                idFactory,
                forceReadOnly = true,
            )
        }

        private const val MAX_HISTORY = 100
        private const val MAX_ELSE_IF_BRANCHES = 32
    }
}

internal data class VisualSubtreeClipboard(
    val rootNodeId: String,
    val nodes: List<JsonObject>,
)

private fun rewriteDeclaredJsonReferences(
    element: JsonElement,
    replacements: Map<String, String>,
    fieldName: String? = null,
): JsonElement = when {
    element.isJsonObject -> JsonObject().apply {
        element.asJsonObject.entrySet().forEach { (name, value) ->
            if (name == "childBlocks" && value.isJsonObject) {
                add(name, JsonObject().apply {
                    value.asJsonObject.entrySet().forEach { (slot, block) ->
                        val original = block.asString
                        addProperty(slot, replacements[original] ?: original)
                    }
                })
            } else {
                add(name, rewriteDeclaredJsonReferences(value, replacements, name))
            }
        }
    }
    element.isJsonArray -> JsonArray().apply {
        element.asJsonArray.forEach { add(rewriteDeclaredJsonReferences(it, replacements, fieldName)) }
    }
    element.isJsonPrimitive && element.asJsonPrimitive.isString &&
        fieldName?.let { it == "nodeId" || it == "parentId" || it == "blockId" || it.endsWith("NodeId") || it.endsWith("BlockId") } == true -> {
        val value = element.asString
        replacements[value]?.let { com.google.gson.JsonPrimitive(it) } ?: element.deepCopy()
    }
    else -> element.deepCopy()
}

private class EditableNode(val json: JsonObject) {
    val nodeId: String get() = json.requiredString("nodeId")
    val blockId: String get() = json.requiredString("blockId")
    val parentId: String? get() = json.get("parentId")?.takeUnless { it.isJsonNull }?.asString
    var orderKey: String
        get() = json.requiredString("orderKey")
        set(value) { json.addProperty("orderKey", value) }
    val kind: String get() = json.requiredString("kind")
    val childBlocks: Map<String, String>
        get() = json.getAsJsonObject("childBlocks")?.entrySet().orEmpty()
            .associate { (name, value) -> name to value.asString }
}

private class EditableDocument(val nodes: MutableList<EditableNode>) {
    fun serialize(rootBlockId: String): String? {
        val projection = project(nodes, rootBlockId) ?: return null
        return buildString {
            projection.forEach { (node, depth) ->
                node.json.addProperty("depth", depth)
                append(node.json).append('\n')
            }
        }
    }
}

private data class ParsedDocument(
    val editable: EditableDocument,
    val rows: List<VisualNodeRow>,
)

internal data class VisualVariableCheckInput(val source: String, val rootBlockId: String, val readOnly: Boolean)
internal data class VisualVariableCheckNode(val row: VisualNodeRow, val parentId: String?, val args: JsonObject?, val disabled: Boolean,
    val display: com.autoscript.studio.compiler.NodeDisplaySettings = com.autoscript.studio.compiler.NodeDisplaySettings())

/** Exactly one validated parse; detached JSON belongs exclusively to this analysis. */
internal fun variableCheckNodes(input: VisualVariableCheckInput): List<VisualVariableCheckNode> {
    if (input.readOnly) return emptyList()
    val parsed = parseDocument(input.source, input.rootBlockId) ?: return emptyList()
    val byId = parsed.editable.nodes.associateBy { it.nodeId }
    val disabled = mutableSetOf<String>()
    return parsed.rows.map { row ->
        val node = byId.getValue(row.nodeId)
        val skipped = node.json.get("disabled")?.asBoolean == true || node.parentId in disabled
        if (skipped) disabled += row.nodeId
        VisualVariableCheckNode(row, node.parentId, node.json.get("args")?.takeIf { it.isJsonObject }?.asJsonObject, skipped,
            com.autoscript.studio.compiler.NodeDisplaySettings.parse(node.json.get("display")))
    }
}

private fun parseDocument(source: String, rootBlockId: String): ParsedDocument? {
    val nodes = mutableListOf<EditableNode>()
    val sourceLines = mutableMapOf<String, Int>()
    val lines = source.split('\n').toMutableList().apply {
        if (lastOrNull()?.isEmpty() == true) removeAt(lastIndex)
        if (size == 1 && single().isEmpty()) clear()
    }
    lines.forEachIndexed { index, line ->
        if (line.isBlank()) return null
        val json = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
        json.get("disabled")?.let { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) return null }
        if (runCatching { com.autoscript.studio.compiler.NodeDisplaySettings.parse(json.get("display")) }.isFailure) return null
        if (runCatching { com.autoscript.studio.compiler.NodeRandomizationSettings.validate(json.get("randomization")) }.isFailure) return null
        val node = runCatching { EditableNode(json) }.getOrNull() ?: return null
        if (json.has("randomization") && !com.autoscript.studio.compiler.NodeRandomizationSettings.supports(node.kind)) return null
        if (runCatching {
                node.nodeId.isNotBlank() && node.blockId.isNotBlank() && node.kind.isNotBlank() &&
                    node.orderKey.matches(Regex("[0-9A-Za-z]{1,64}"))
            }.getOrDefault(false).not()
        ) return null
        if (sourceLines.put(node.nodeId, index + 1) != null) return null
        nodes += node
    }
    val projection = project(nodes, rootBlockId) ?: return null
    val childSlots = nodes.flatMap { owner ->
        owner.childBlocks.map { (name, blockId) -> blockId to name }
    }.toMap()
    projection.forEach { (node, expectedDepth) ->
        val storedDepth = node.json.get("depth") ?: return@forEach
        if (!storedDepth.isJsonPrimitive || runCatching { storedDepth.asInt }.getOrNull() != expectedDepth) {
            return null
        }
    }
    return ParsedDocument(
        editable = EditableDocument(nodes),
        rows = projection.map { (node, depth) ->
            VisualNodeRow(
                nodeId = node.nodeId,
                blockId = node.blockId,
                orderKey = node.orderKey,
                kind = node.kind,
                depth = depth,
                childSlot = childSlots[node.blockId],
                sourceLine = sourceLines.getValue(node.nodeId),
            )
        },
    )
}

private fun project(nodes: List<EditableNode>, rootBlockId: String): List<Pair<EditableNode, Int>>? {
    if (nodes.map { it.nodeId }.distinct().size != nodes.size) return null
    if (nodes.groupBy { it.blockId }.values.any { block ->
            block.map { it.orderKey }.distinct().size != block.size
        }
    ) return null
    val byId = nodes.associateBy { it.nodeId }
    val owners = mutableMapOf<String, EditableNode>()
    for (node in nodes) {
        for (blockId in runCatching { node.childBlocks.values }.getOrElse { return null }) {
            if (blockId == rootBlockId || owners.put(blockId, node) != null) return null
        }
    }
    for (node in nodes) {
        if (node.blockId == rootBlockId) {
            if (node.parentId != null) return null
        } else {
            val owner = owners[node.blockId] ?: return null
            if (node.parentId != owner.nodeId || byId[node.parentId] == null) return null
        }
    }
    val members = nodes.groupBy { it.blockId }.mapValues { (_, value) -> value.sortedBy { it.orderKey } }
    val visited = mutableSetOf<String>()
    val output = mutableListOf<Pair<EditableNode, Int>>()
    fun visit(blockId: String, depth: Int): Boolean {
        for (node in members[blockId].orEmpty()) {
            if (!visited.add(node.nodeId)) return false
            output += node to depth
            for (child in node.childBlocks.toSortedMap().values) {
                if (!visit(child, depth + 1)) return false
            }
        }
        return true
    }
    if (!visit(rootBlockId, 0) || visited.size != nodes.size) return null
    return output
}

private fun JsonObject.requiredString(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: error("missing $name")

private const val ORDER_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

private fun between(previous: String?, next: String?): String? {
    if (previous != null && !previous.matches(Regex("[0-9A-Za-z]{1,64}"))) return null
    if (next != null && !next.matches(Regex("[0-9A-Za-z]{1,64}"))) return null
    if (previous != null && next != null && previous >= next) return null
    val output = StringBuilder()
    var lowerIndex = 0
    var upperIndex = 0
    var upperBounded = next != null
    while (output.length < 64) {
        val low = previous?.getOrNull(lowerIndex)?.let(ORDER_ALPHABET::indexOf) ?: -1
        val high = if (upperBounded) next?.getOrNull(upperIndex)?.let(ORDER_ALPHABET::indexOf) ?: -1
        else ORDER_ALPHABET.length
        when {
            low == high && low >= 0 -> {
                output.append(ORDER_ALPHABET[low]); lowerIndex++; upperIndex++
            }
            high - low > 1 -> {
                val middle = Math.floorDiv(low + high, 2)
                output.append(ORDER_ALPHABET[middle])
                if (middle == 0) output.append(ORDER_ALPHABET[ORDER_ALPHABET.length / 2])
                break
            }
            low == -1 && high == 0 -> {
                output.append(ORDER_ALPHABET[0]); upperIndex++
            }
            low >= 0 -> {
                output.append(ORDER_ALPHABET[low]); lowerIndex++; upperBounded = false
            }
            else -> return null
        }
    }
    val result = output.toString()
    return result.takeIf {
        it.length <= 64 && previous?.let { bound -> bound < it } != false &&
            next?.let { bound -> it < bound } != false
    }
}

private fun rebalance(nodes: List<EditableNode>) {
    val step = 62L * 62 * 62 * 62 / (nodes.size + 1)
    require(step >= 16) { "单个 block 节点过多" }
    nodes.forEachIndexed { index, node ->
        var value = step * (index + 1)
        val output = CharArray(4) { '0' }
        for (position in output.indices.reversed()) {
            output[position] = ORDER_ALPHABET[(value % 62).toInt()]
            value /= 62
        }
        node.orderKey = String(output)
    }
}
