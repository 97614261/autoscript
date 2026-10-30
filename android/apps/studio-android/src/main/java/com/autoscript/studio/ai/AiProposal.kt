package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal data class AiBlock(val kind: String, val args: JsonObject, val children: Map<String, List<AiBlock>>)
internal data class AiUpdate(val nodeId: String, val args: JsonObject, val removeArgs: List<String>)
internal data class AiProposal(val message: String, val blocks: List<AiBlock>, val updates: List<AiUpdate>,
    val replaceSelection: Boolean, val missingResources: List<String>, val raw: String) {
    val hasChanges get() = blocks.isNotEmpty() || updates.isNotEmpty() || replaceSelection
}

internal fun aiJsonObject(text: String, maxBytes: Int = 128 * 1024): JsonObject {
    require(text.toByteArray(Charsets.UTF_8).size <= maxBytes) { "回答过长，请分段生成" }
    val trimmed = text.trim()
    val json = if (trimmed.startsWith("```json\n") && trimmed.endsWith("```")) trimmed.removePrefix("```json\n").removeSuffix("```").trim()
        else if (trimmed.startsWith("```\n") && trimmed.endsWith("```")) trimmed.removePrefix("```\n").removeSuffix("```").trim() else trimmed
    // Check nesting before Gson's recursive parser sees provider-controlled input.
    var quoted = false; var escaped = false; var depth = 0
    for (character in json) {
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 48) { "回答嵌套过深，请简化任务" } }
            '}', ']' -> { depth--; require(depth >= 0) { "回答 JSON 格式无效" } }
        }
    }
    require(!quoted && depth == 0) { "回答 JSON 不完整，请重试" }
    try {
        var tokens = 0
        fun inspect(reader: JsonReader) {
            require(++tokens <= 30_000) { "回答结构过大" }
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject(); val names = mutableSetOf<String>()
                    while (reader.hasNext()) { require(names.add(reader.nextName())) { "回答包含重复字段" }; inspect(reader) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while (reader.hasNext()) inspect(reader); reader.endArray() }
                JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> reader.skipValue()
                else -> error("回答 JSON 格式无效")
            }
        }
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            inspect(reader); require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        return JsonReader(StringReader(json)).use { reader -> reader.strictness = Strictness.STRICT; JsonParser.parseReader(reader).asJsonObject }
    } catch (_: Exception) { error("回答不是有效的积木方案，请重试") }
}

private fun JsonObject.aiFields(allowed: Set<String>) {
    require(keySet().all { it in allowed }) { "回答包含不支持的字段，请重新生成" }
}
private fun JsonElement.aiString(): String {
    require(isJsonPrimitive && asJsonPrimitive.isString) { "回答中的文字字段格式无效" }
    return asString
}
private fun JsonObject.aiArray(key: String): JsonArray = get(key)?.let {
    require(it.isJsonArray) { "$key 必须是列表" }; it.asJsonArray
} ?: JsonArray()

internal fun parseAiProposal(text: String, mode: AiMode, allowedKinds: Set<String>): AiProposal {
    val json = aiJsonObject(text)
    json.aiFields(setOf("message", "blocks", "updates", "replaceSelection", "missingResources"))
    val message = json["message"]?.aiString().orEmpty()
    require(message.isNotBlank() && message.length <= 12_000) { "方案说明为空或过长" }
    var count = 0
    fun block(value: JsonElement, depth: Int): AiBlock {
        require(depth < 16 && ++count <= 256) { "一次最多生成256行、16层积木，请分段处理" }
        require(value.isJsonObject) { "积木格式无效" }
        val objectValue = value.asJsonObject
        objectValue.aiFields(setOf("kind", "args", "children"))
        val kind = objectValue["kind"]?.aiString().orEmpty()
        val contract = BlockCatalog.insertable.singleOrNull { it.kind == kind }
        require(contract != null && kind in allowedKinds) { "使用了未提供的函数，请重新生成或缩小任务" }
        val args = objectValue["args"]?.also { require(it.isJsonObject) { "积木参数必须是对象" } }?.asJsonObject ?: JsonObject()
        val childJson = objectValue["children"]?.also { require(it.isJsonObject) { "积木分支格式无效" } }?.asJsonObject ?: JsonObject()
        val extraBranches = if (kind == "control.if") (0 until (args["elseIf"]?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0)).map { "elseIf$it" } else emptyList()
        val slots = contract.childBlocks + extraBranches
        require(childJson.keySet().all { it in slots }) { "${contract.title}包含无效分支" }
        return AiBlock(kind, args.deepCopy(), slots.associateWith { slot ->
            childJson.aiArray(slot).map { block(it, depth + 1) }
        })
    }
    val blocks = json.aiArray("blocks").map { block(it, 0) }
    val updates = json.aiArray("updates").also { require(it.size() <= 256) }.map { value ->
        require(value.isJsonObject)
        val update = value.asJsonObject
        update.aiFields(setOf("nodeId", "args", "removeArgs"))
        val id = update["nodeId"]?.aiString().orEmpty()
        require(id.length in 1..160 && update["args"]?.isJsonObject == true) { "修改目标或参数格式无效" }
        val removed = update.aiArray("removeArgs").map { it.aiString() }
        require(removed.size <= 128 && removed.all { it.length in 1..128 })
        AiUpdate(id, update.getAsJsonObject("args").deepCopy(), removed)
    }
    require(updates.map { it.nodeId }.distinct().size == updates.size) { "同一积木被重复修改，请重新生成" }
    val replace = json["replaceSelection"]?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
    } ?: false
    val missing = json.aiArray("missingResources").map { it.aiString() }
    require(missing.size <= 32 && missing.all { it.length in 1..256 }) { "待补充资源过多，请缩小任务" }
    require(updates.isEmpty() || (!replace && blocks.isEmpty())) { "参数修改与整段替换不能混用" }
    when (mode) {
        AiMode.GENERATE -> require(!replace && updates.isEmpty()) { "生成模式不能修改已有积木" }
        AiMode.MODIFY -> require(blocks.isEmpty() || replace) { "修改模式须明确替换所选内容" }
        else -> require(blocks.isEmpty() && updates.isEmpty() && !replace) { "解释和排错模式不会修改积木" }
    }
    return AiProposal(message, blocks, updates, replace, missing, text)
}

internal fun aiClipboards(blocks: List<AiBlock>): List<VisualSubtreeClipboard> {
    var sequence = 0
    val lines = mutableListOf<JsonObject>()
    fun append(block: AiBlock, parent: String?, blockId: String, order: Int, depth: Int): String {
        val index = ++sequence
        val id = "ai-node-$index"
        val children = block.children.keys.associateWith { "ai-block-$index-$it" }
        lines += JsonObject().apply {
            addProperty("flowSchemaVersion", 1); addProperty("nodeId", id); addProperty("blockId", blockId)
            add("parentId", parent?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
            addProperty("orderKey", (order + 1).toString(36).padStart(5, '0'))
            addProperty("kind", block.kind); addProperty("nodeVersion", requireNotNull(BlockCatalog.find(block.kind)).nodeVersion)
            addProperty("depth", depth); add("args", block.args.deepCopy())
            if (children.isNotEmpty()) add("childBlocks", JsonObject().apply { children.forEach(::addProperty) })
        }
        block.children.forEach { (slot, nested) -> nested.forEachIndexed { at, child -> append(child, id, children.getValue(slot), at, depth + 1) } }
        return id
    }
    blocks.forEachIndexed { index, value -> append(value, null, "ai-root", index, 0) }
    val scratch = VisualEditorState.create(lines.joinToString("\n"), "ai-root")
    require(!scratch.isReadOnly) { "生成的积木结构无效" }
    check(scratch.selectNodes(scratch.rows.filter { it.depth == 0 }.map { it.nodeId }))
    return scratch.copySelection()
}

internal enum class AiInsertPlace(val title: String) { AFTER("所选后面"), BEFORE("所选前面"), INSIDE("所选分支内"), END("插件末尾") }
internal data class AiCandidate(val source: String, val added: Int, val removed: Int, val changed: Int,
    val beforeRows: List<String>, val afterRows: List<String>)

internal fun aiCandidate(source: String, root: String, selection: Set<String>, primary: String?, proposal: AiProposal,
    mode: AiMode, place: AiInsertPlace, slot: String?, identities: VisualProjectIds): AiCandidate {
    require(proposal.missingResources.isEmpty()) { "请先补充方案所需的变量或资源，再重新生成" }
    require(proposal.hasChanges && mode in setOf(AiMode.GENERATE, AiMode.MODIFY)) { "这条答复没有可应用的修改" }
    val scratch = VisualEditorState.createProjectScoped(source, root, identities)
    check(!scratch.isReadOnly) { "当前插件只读" }
    check(scratch.selectNodes(selection)) { "选择已失效" }
    if (primary != null && primary in selection) {
        // selectNodes keeps a valid primary; source/selection ownership is rechecked by the caller.
        scratch.selectedNodeId = primary
        check(scratch.selectNodes(selection))
    }
    val before = scratch.rows.size
    val selectedNodes = scratch.copySelection().flatMap { it.nodes }.map { it["nodeId"].asString }.toSet()
    var removed = 0; var changed = 0
    if (mode == AiMode.MODIFY) {
        require(selection.isNotEmpty()) { "请先选择需要修改的积木" }
        if (proposal.updates.isNotEmpty()) {
            for (update in proposal.updates) {
                require(update.nodeId in selectedNodes) { "AI 尝试修改所选范围之外的积木，已拒绝" }
                val args = requireNotNull(scratch.nodeArguments(update.nodeId))
                update.removeArgs.forEach(args::remove)
                update.args.entrySet().forEach { args.add(it.key, it.value.deepCopy()) }
                if (scratch.updateArguments(update.nodeId, args)) changed++
            }
        } else {
            require(proposal.replaceSelection) { "没有需要修改的积木" }
            removed = selectedNodes.size
            check(scratch.replaceSelectionWith(aiClipboards(proposal.blocks))) { "整段替换请选择同一层连续的积木" }
        }
    } else {
        val clips = aiClipboards(proposal.blocks)
        require(clips.isNotEmpty()) { "没有生成积木" }
        when (place) {
            AiInsertPlace.END -> scratch.selectOnly(null)
            else -> require(primary != null) { "请先选择插入位置" }
        }
        check(scratch.pasteSelection(clips, slot = slot.takeIf { place == AiInsertPlace.INSIDE }, beforeSelection = place == AiInsertPlace.BEFORE)) {
            "插入位置无效，请重新选择"
        }
        if (place == AiInsertPlace.INSIDE) require(slot != null) { "请选择要加入的分支" }
    }
    require(source != scratch.currentSource) { "方案未改变任何内容" }
    val added = scratch.rows.size - before + removed
    fun preview(state: VisualEditorState) = state.programDisplayNodes().take(1500).map { "${"  ".repeat(it.depth.coerceAtMost(16))}${it.label}" }
    return AiCandidate(scratch.currentSource, added, removed, changed,
        preview(VisualEditorState.create(source, root)), preview(scratch))
}

internal fun aiContractDefinitions(kinds: Set<String>): String = JsonArray().apply {
    BlockCatalog.insertable.filter { it.kind in kinds }.forEach { contract ->
        add(JsonObject().apply {
            addProperty("kind", contract.kind); addProperty("title", contract.title); addProperty("meaning", contract.summary)
            add("children", JsonArray().apply { contract.childBlocks.forEach(::add) })
            add("properties", JsonArray().apply { contract.properties.forEach { property -> add(JsonObject().apply {
                addProperty("name", property.path); addProperty("label", property.label); addProperty("type", property.editor.name)
                addProperty("required", property.required)
                property.defaultValue?.let { addProperty("defaultExample", it) }
                if (property.choices.isNotEmpty()) add("choices", JsonArray().apply { property.choices.forEach(::add) })
                property.dependsOn?.let { addProperty("dependsOn", it) }
                property.resourceKind?.let { addProperty("resource", it.name) }
            }) } })
        })
    }
}.toString()
