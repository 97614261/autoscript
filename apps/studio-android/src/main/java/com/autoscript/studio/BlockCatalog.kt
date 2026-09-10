package com.autoscript.studio

import com.autoscript.studio.generated.BlockCategory
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.GeneratedBlockCatalog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

internal data class BlockSearchResult(
    val contract: BlockContract,
    val missingCapabilities: Set<String>,
) {
    val isAvailable: Boolean get() = missingCapabilities.isEmpty()
}

internal object BlockCatalog {
    val all: List<BlockContract> = GeneratedBlockCatalog.all
    private val byKind = all.associateBy(BlockContract::kind)

    fun find(kind: String): BlockContract? = byKind[kind]

    fun search(
        query: String,
        capabilities: Set<String>,
        category: BlockCategory? = null,
    ): List<BlockSearchResult> {
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            .filter(String::isNotEmpty)
        return all.asSequence()
            .filter { category == null || it.category == category }
            .filter { contract ->
                val haystack = buildString {
                    append(contract.title).append(' ')
                    append(contract.kind).append(' ')
                    append(contract.summary).append(' ')
                    append(contract.searchTerms.joinToString(" "))
                }.lowercase(Locale.ROOT)
                terms.all(haystack::contains)
            }
            .map { contract ->
                BlockSearchResult(contract, contract.requiredCapabilities - capabilities)
            }
            .sortedWith(
                compareBy<BlockSearchResult> { it.contract.category.ordinal }
                    .thenBy { it.contract.title }
                    .thenBy { it.contract.kind },
            )
            .toList()
    }
}

internal sealed interface FlowMigrationResult {
    data class Success(val source: String, val changed: Boolean) : FlowMigrationResult
    data class Failure(val line: Int, val message: String) : FlowMigrationResult
}

internal object BlockMigrationEngine {
    fun migrateFlowSource(source: String): FlowMigrationResult {
        if (source.isEmpty()) return FlowMigrationResult.Success(source, false)
        val hadFinalNewline = source.endsWith('\n')
        val lines = source.split('\n').let { split ->
            if (hadFinalNewline) split.dropLast(1) else split
        }
        var changed = false
        val migrated = lines.mapIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank()) return FlowMigrationResult.Failure(index + 1, "Flow包含空白行")
            val node = runCatching { JsonParser.parseString(line).asJsonObject }.getOrElse {
                return FlowMigrationResult.Failure(index + 1, "节点JSON损坏")
            }
            when (val result = migrateNode(node)) {
                is NodeMigrationResult.Current -> {
                    if (result.changed) changed = true
                    if (result.changed) result.node.toString() else rawLine
                }
                is NodeMigrationResult.Unsupported -> {
                    return FlowMigrationResult.Failure(index + 1, result.message)
                }
            }
        }
        if (!changed) return FlowMigrationResult.Success(source, false)
        return FlowMigrationResult.Success(
            migrated.joinToString("\n", postfix = if (hadFinalNewline) "\n" else ""),
            true,
        )
    }

    internal fun migrateNode(
        source: JsonObject,
        contracts: List<BlockContract> = BlockCatalog.all,
    ): NodeMigrationResult {
        val node = source.deepCopy()
        val kind = node.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return NodeMigrationResult.Unsupported("节点缺少kind")
        val version = node.get("nodeVersion")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asInt }.getOrNull() }
            ?: return NodeMigrationResult.Unsupported("节点${kind}缺少nodeVersion")
        val contract = contracts.singleOrNull { it.kind == kind }
            ?: return NodeMigrationResult.Current(node, false)
        if (version > contract.nodeVersion) {
            return NodeMigrationResult.Unsupported(
                "节点${kind}版本${version}高于当前支持版本${contract.nodeVersion}",
            )
        }
        var current = version
        var changed = false
        while (current < contract.nodeVersion) {
            val migration = contract.migrations.singleOrNull { it.fromVersion == current }
                ?: return NodeMigrationResult.Unsupported("节点${kind}缺少${current}版本迁移路径")
            val args = node.getAsJsonObject("args")
                ?: return NodeMigrationResult.Unsupported("节点${kind}缺少args")
            for ((from, to) in migration.renameArguments) {
                if (!args.has(from)) continue
                if (args.has(to)) {
                    return NodeMigrationResult.Unsupported("节点${kind}迁移参数冲突：$from/$to")
                }
                args.add(to, args.remove(from))
            }
            current = migration.toVersion
            node.addProperty("nodeVersion", current)
            changed = true
        }
        return NodeMigrationResult.Current(node, changed)
    }
}

internal sealed interface NodeMigrationResult {
    data class Current(val node: JsonObject, val changed: Boolean) : NodeMigrationResult
    data class Unsupported(val message: String) : NodeMigrationResult
}
