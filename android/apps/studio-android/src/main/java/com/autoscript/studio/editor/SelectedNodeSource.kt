package com.autoscript.studio

import com.autoscript.studio.compiler.SourceLocation
import com.autoscript.studio.compiler.VisualLuaSource
import com.autoscript.studio.compiler.VisualSourceDocument
import com.autoscript.studio.compiler.VisualSourceException
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class PluginSourceRow(val id: String, val kind: String, val index: Int, val label: String) {
    val title get() = BlockCatalog.find(kind)?.title ?: kind
}

internal fun pluginSourceRows(source: String, nodeOrder: List<String> = emptyList()): List<PluginSourceRow> {
    require(source.toByteArray(Charsets.UTF_8).size <= VisualLuaSource.MAX_BYTES) { "插件超过 1 MiB" }
    val rows = source.lineSequence().filter(String::isNotBlank).mapIndexed { index, line ->
        require(index < 4096) { "积木数量超过 4096" }
        val node = JsonParser.parseString(line).asJsonObject
        val kind = node["kind"].asString
        PluginSourceRow(node["nodeId"].asString, kind, index,
            applyProgramDisplayLabel(legacyDockProgramLabel(BlockCatalog.find(kind),
                node["args"]?.takeIf { it.isJsonObject }?.asJsonObject, kind) + randomizationRowSuffix(node.getAsJsonObject("randomization"), kind),
                com.autoscript.studio.compiler.NodeDisplaySettings.parse(node["display"])))
    }.toList()
    require(rows.map { it.id }.distinct().size == rows.size) { "重复积木身份" }
    if (nodeOrder.isEmpty()) return rows
    val order = nodeOrder.withIndex().associate { it.value to it.index }
    return rows.sortedBy { order[it.id] ?: Int.MAX_VALUE }.mapIndexed { index, row -> row.copy(index = index) }
}

/** Detached descriptor for exactly one node. Only its arguments may return to the real tree. */
internal class SelectedNodeSource private constructor(
    private val original: String,
    private val lineIndex: Int,
    private val actual: JsonObject,
    private val detached: JsonObject,
    private val root: String,
    val text: String,
) {
    fun parse(text: String, variables: Set<String>, checkCancelled: () -> Unit = {}): VisualSourceDocument {
        val identity = JsonObject().apply {
            addProperty("id", actual["nodeId"].asString)
            addProperty("disabled", actual["disabled"]?.asBoolean ?: false)
            actual["display"]?.let { add("display", it.deepCopy()) }
            actual["randomization"]?.let { add("randomization", it.deepCopy()) }
        }
        val result = try {
            pluginSourceToolchain.parse("--@node $identity\n$text", "$detached\n", root, variables, checkCancelled = checkCancelled)
        } catch (error: VisualSourceException) {
            throw VisualSourceException(error.location.copy(line = (error.location.line - 1).coerceAtLeast(1)),
                error.message.orEmpty().substringAfter('：'))
        }
        val nodes = result.text.lineSequence().filter(String::isNotBlank).map { JsonParser.parseString(it).asJsonObject }.toList()
        fun reject(): Nothing = throw VisualSourceException(SourceLocation(1, 1),
            "本行只能修改当前积木参数，不能增加积木、改变类型、身份或分支结构；结构修改请用参数面板或插件源码")
        if (nodes.size != 1) reject()
        val node = nodes.single()
        val metadata = node.deepCopy().apply { remove("args") }
        if (metadata != detached.deepCopy().apply { remove("args") }) reject()
        val updated = actual.deepCopy().apply { add("args", node["args"].deepCopy()) }
        // Do not serialize other rows, blank lines or trailing newline differently.
        val lines = original.split('\n').toMutableList()
        if (actual["args"] != updated["args"]) lines[lineIndex] = updated.toString()
        return VisualSourceDocument(lines.joinToString("\n"), result.nodes.mapValues { (_, location) ->
            location.copy(line = (location.line - 1).coerceAtLeast(1), endLine = (location.endLine - 1).coerceAtLeast(1))
        })
    }

    companion object {
        fun create(source: String, nodeId: String, checkCancelled: () -> Unit = {}): SelectedNodeSource {
            pluginSourceRows(source)
            val lines = source.split('\n')
            val index = lines.indexOfFirst { line ->
                checkCancelled()
                line.isNotBlank() && JsonParser.parseString(line).asJsonObject["nodeId"]?.asString == nodeId
            }
            require(index >= 0) { "所选积木已不存在，请重新选择" }
            val actual = JsonParser.parseString(lines[index]).asJsonObject
            val root = "source-root-$nodeId"
            val detached = actual.deepCopy().apply {
                addProperty("blockId", root)
                add("parentId", JsonNull.INSTANCE)
                addProperty("depth", 0)
            }
            val export = pluginSourceToolchain.export("$detached\n", root, checkCancelled)
            val editable = export.text.lineSequence().filterNot {
                it.trimStart().startsWith("--@node") || it.startsWith("--!")
            }.joinToString("\n")
            return SelectedNodeSource(source, index, actual, detached, root, editable)
        }
    }
}

internal fun sourceRowOffset(text: String, location: SourceLocation): Int {
    // Exported location begins with the hidden identity line, so select the actual statement.
    val lines = text.lines()
    val line = (location.line + 1).coerceAtMost(lines.size)
    return lines.take(line - 1).sumOf { it.length + 1 }.coerceAtMost(text.length)
}
