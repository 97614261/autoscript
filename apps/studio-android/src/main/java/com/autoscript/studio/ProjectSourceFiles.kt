package com.autoscript.studio

import com.autoscript.project.store.ProjectFlow
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectSourceMode
import com.autoscript.project.store.ProjectStore
import com.autoscript.project.store.SourceGroup
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** 源文件管理里的一行：一个 Flow 及其显示名、磁盘信息和所属虚拟分组。 */
internal data class SourceFileEntry(
    val flowId: String,
    val name: String,
    val path: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val group: String?,
    val isEntry: Boolean,
)

internal data class SourceFileTree(
    val groups: List<SourceGroup> = emptyList(),
    val entries: List<SourceFileEntry> = emptyList(),
) {
    fun entriesIn(group: String?): List<SourceFileEntry> = entries.filter { it.group == group }

    fun entry(flowId: String): SourceFileEntry? = entries.firstOrNull { it.flowId == flowId }
}

internal data class SourceDeleteResult(
    val snapshot: ProjectSnapshot,
    val deleted: List<String>,
    val failures: List<String>,
)

/** Flow 在源文件管理里的显示名 = 路径去掉目录和 `.jsonl`。 */
internal fun ProjectFlow.displayName(): String = path.substringAfterLast('/').removeSuffix(".jsonl")

/**
 * 项目源文件适配层。
 *
 * Compose 只拿这里的模型和结果；所有写操作都经 [ProjectStore]，由它维护清单、引用闭包、
 * 原子写入和 generation stale。分组按新版易编精灵的做法是虚拟视图（`.studio/source-groups.json`），
 * 不移动文件。
 */
internal class ProjectSourceFiles(
    private val store: ProjectStore,
    private val idFactory: () -> String = { "flow-${UUID.randomUUID().toString().take(8)}" },
) {
    fun load(snapshot: ProjectSnapshot): SourceFileTree {
        if (snapshot.manifest.sourceMode != ProjectSourceMode.VISUAL) return SourceFileTree()
        val groups = store.readSourceGroups(snapshot.manifest.projectId)
        val groupOf = groups.flatMap { group -> group.flowIds.map { it to group.name } }.toMap()
        val entries = snapshot.manifest.flows.map { flow ->
            val file = File(snapshot.directory, flow.path)
            SourceFileEntry(
                flowId = flow.flowId,
                name = flow.displayName(),
                path = flow.path,
                lastModified = file.lastModified(),
                sizeBytes = file.length(),
                group = groupOf[flow.flowId],
                isEntry = flow.flowId == snapshot.manifest.entryFlowId,
            )
        }
        return SourceFileTree(groups, entries)
    }

    fun createFlow(snapshot: ProjectSnapshot, name: String, group: String? = null): ProjectSnapshot {
        val flowIds = snapshot.flowIds()
        val flowId = freshFlowId(flowIds)
        val created = store.createFlow(snapshot.manifest.projectId, flowId, flowIds, fileName = name)
        if (group != null) addToGroup(created, group, listOf(flowId))
        return created
    }

    fun renameFlow(snapshot: ProjectSnapshot, flowId: String, name: String): ProjectSnapshot =
        store.renameFlow(snapshot.manifest.projectId, flowId, name, snapshot.flowIds())

    fun copyFlow(snapshot: ProjectSnapshot, flowId: String, name: String): ProjectSnapshot {
        val flowIds = snapshot.flowIds()
        return store.copyFlow(snapshot.manifest.projectId, flowId, freshFlowId(flowIds), name, flowIds)
    }

    /** 逐个删除；入口 Flow 和仍被 `flow.call` 引用的 Flow 会被 Store 拒绝，失败原因逐条返回。 */
    fun deleteFlows(snapshot: ProjectSnapshot, flowIds: List<String>): SourceDeleteResult {
        var current = snapshot
        val deleted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        flowIds.forEach { flowId ->
            val flow = current.manifest.flows.firstOrNull { it.flowId == flowId } ?: return@forEach
            runCatching { store.deleteFlow(current.manifest.projectId, flowId, current.flowIds()) }
                .onSuccess { current = it; deleted += flowId }
                .onFailure { failures += "${flow.displayName()}：${it.message ?: "删除失败"}" }
        }
        return SourceDeleteResult(current, deleted, failures)
    }

    fun createGroup(snapshot: ProjectSnapshot, name: String): List<SourceGroup> {
        val groups = store.readSourceGroups(snapshot.manifest.projectId)
        require(groups.none { it.name == name.trim() }) { "分组已存在：$name" }
        return store.writeSourceGroups(snapshot.manifest.projectId, groups + SourceGroup(name.trim()))
    }

    fun renameGroup(snapshot: ProjectSnapshot, current: String, name: String): List<SourceGroup> {
        val groups = store.readSourceGroups(snapshot.manifest.projectId)
        require(groups.any { it.name == current }) { "分组不存在：$current" }
        return store.writeSourceGroups(
            snapshot.manifest.projectId,
            groups.map { if (it.name == current) it.copy(name = name.trim()) else it },
        )
    }

    /** 删除分组只解散分组，成员 Flow 回到未分组，不删除文件。 */
    fun deleteGroups(snapshot: ProjectSnapshot, names: Collection<String>): List<SourceGroup> {
        val groups = store.readSourceGroups(snapshot.manifest.projectId)
        return store.writeSourceGroups(snapshot.manifest.projectId, groups.filterNot { it.name in names })
    }

    fun addToGroup(snapshot: ProjectSnapshot, name: String, flowIds: Collection<String>): List<SourceGroup> {
        val target = name.trim()
        val groups = store.readSourceGroups(snapshot.manifest.projectId)
        val moving = flowIds.toSet()
        val stripped = groups.map { group -> group.copy(flowIds = group.flowIds.filterNot { it in moving }) }
        val merged = if (stripped.any { it.name == target }) {
            stripped.map { group -> if (group.name == target) group.copy(flowIds = group.flowIds + moving) else group }
        } else {
            stripped + SourceGroup(target, moving.toList())
        }
        return store.writeSourceGroups(snapshot.manifest.projectId, merged)
    }

    fun removeFromGroup(snapshot: ProjectSnapshot, flowIds: Collection<String>): List<SourceGroup> {
        val moving = flowIds.toSet()
        val groups = store.readSourceGroups(snapshot.manifest.projectId)
        return store.writeSourceGroups(
            snapshot.manifest.projectId,
            groups.map { group -> group.copy(flowIds = group.flowIds.filterNot { it in moving }) },
        )
    }

    /** 没有被任何 `flow.call` 引用、又不是入口的 Flow（旧版“未调用插件”）。 */
    fun unreferencedFlows(snapshot: ProjectSnapshot): List<ProjectFlow> {
        val targets = snapshot.flowSources.values.flatMapTo(mutableSetOf(), ::flowCallTargets)
        return snapshot.manifest.flows.filter { it.flowId != snapshot.manifest.entryFlowId && it.flowId !in targets }
    }

    private fun freshFlowId(taken: Set<String>): String {
        repeat(32) {
            val candidate = idFactory()
            if (candidate !in taken) return candidate
        }
        error("无法生成唯一插件 ID")
    }

    private fun ProjectSnapshot.flowIds(): Set<String> = manifest.flows.map(ProjectFlow::flowId).toSet()

    private fun flowCallTargets(source: String): List<String> =
        source.lineSequence().filter(String::isNotBlank).mapNotNull { line ->
            val node = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@mapNotNull null
            if (node.get("kind")?.takeIf { it.isJsonPrimitive }?.asString != "flow.call") return@mapNotNull null
            node.getAsJsonObject("args")?.get("targetFlowId")?.takeIf { it.isJsonPrimitive }?.asString
        }.toList()
}
