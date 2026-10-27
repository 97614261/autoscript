package com.autoscript.project.store

import com.autoscript.script.ui.ScriptUiDefinition
import com.autoscript.script.ui.UiControl
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

enum class ResourceUsageSource { SAVED_FLOW, DRAFT_FLOW, LUA, INTERFACE, IMAGE_RECIPE }

data class ProjectResourceUsage(
    val source: ResourceUsageSource,
    val location: String,
    val line: Int? = null,
    val nodeId: String? = null,
    val blockKind: String? = null,
    val parameter: String? = null,
    val viaDirectory: Boolean = false,
)

data class ProjectResourceUsageReport(val path: String, val usages: List<ProjectResourceUsage>, val totalCount: Int) {
    val truncated: Boolean get() = totalCount > usages.size
}

class ProjectResourceInUseException(val report: ProjectResourceUsageReport) :
    IllegalArgumentException("资源仍被 ${report.totalCount} 处引用，原文件已保留")

/** Deletion and the read-only report must agree, including references in saved-but-edited plugins. */
internal fun scanResourceUsages(
    snapshot: ProjectSnapshot,
    path: String,
    drafts: Map<String, String>,
    recipes: List<ProjectImageRecipe>,
    checkCancelled: () -> Unit = {},
): ProjectResourceUsageReport {
    require(drafts.keys.all { id -> snapshot.manifest.flows.any { it.flowId == id } }) { "插件列表已变化，请刷新后重试" }
    val deadline = System.nanoTime() + 10_000_000_000L
    var scannedChars = 0L
    val results = ArrayList<ProjectResourceUsage>()
    var count = 0
    fun checkpoint() {
        checkCancelled()
        check(!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) { "使用位置检查超时，图片保留，请重试" }
    }
    fun budget(source: String) {
        scannedChars += source.length
        require(scannedChars <= 64L * 1024 * 1024) { "使用位置检查超过源码预算，不能确认图片是否未使用" }
        checkpoint()
    }
    fun record(usage: ProjectResourceUsage) {
        count++
        if (results.size < 512) results.add(usage)
    }
    fun visual(flowId: String, source: String, origin: ResourceUsageSource) {
        budget(source)
        val location = snapshot.manifest.flows.single { it.flowId == flowId }.path
        source.lineSequence().forEachIndexed { index, line ->
            checkpoint()
            if (line.isBlank()) return@forEachIndexed
            require(line.length <= 1024 * 1024) { "插件 $location 第 ${index + 1} 行超过检查预算，图片保留" }
            var nodeId: String? = null
            var kind: String? = null
            var matchCount = 0
            val matches = ArrayList<Pair<String, Boolean>>()
            try {
                JsonReader(StringReader(line)).use { reader ->
                    reader.beginObject()
                    var depth = 1
                    var tokens = 0
                    var argsSeen = false
                    while (depth > 0) {
                        if (++tokens % 1024 == 0) checkpoint()
                        require(tokens <= 100_000 && depth <= 64)
                        val parameter = reader.path
                        require(parameter.length <= 2048)
                        val token = reader.peek()
                        when (token) {
                            JsonToken.BEGIN_OBJECT -> { reader.beginObject(); depth++ }
                            JsonToken.BEGIN_ARRAY -> { reader.beginArray(); depth++ }
                            JsonToken.END_OBJECT -> { reader.endObject(); depth-- }
                            JsonToken.END_ARRAY -> { reader.endArray(); depth-- }
                            JsonToken.NAME -> {
                                if (reader.nextName() == "args" && depth == 1) {
                                    require(reader.peek() == JsonToken.BEGIN_OBJECT)
                                    argsSeen = true
                                }
                            }
                            JsonToken.STRING -> {
                                val value = reader.nextString()
                                when (parameter) {
                                    "$.nodeId" -> { require(value.length <= 128); nodeId = value }
                                    "$.kind" -> { require(value.length <= 128); kind = value }
                                    else -> if (parameter.startsWith("$.args.") || parameter.startsWith("$.args[")) {
                                        val directory = value.startsWith("assets/images/") && value.endsWith('/') && path.startsWith(value)
                                        if (value == path || directory) {
                                            matchCount++
                                            if (results.size + matches.size < 512) matches.add(parameter.removePrefix("$.") to directory)
                                        }
                                    }
                                }
                            }
                            JsonToken.NUMBER -> reader.nextString()
                            JsonToken.BOOLEAN -> reader.nextBoolean()
                            JsonToken.NULL -> reader.nextNull()
                            else -> error("invalid node")
                        }
                    }
                    require(argsSeen && reader.peek() == JsonToken.END_DOCUMENT)
                }
            } catch (_: Exception) {
                // Propagate cancellation/deadline failures; never expose parser input in the UI or logs.
                checkpoint()
                throw ProjectStoreException("无法完整检查插件 $location 第 ${index + 1} 行，请修复后重试，图片保留")
            }
            matches.forEach { (parameter, directory) ->
                record(ProjectResourceUsage(origin, location, index + 1, nodeId, kind, parameter, directory))
            }
            count += matchCount - matches.size
        }
    }
    when (snapshot.manifest.sourceMode) {
        ProjectSourceMode.VISUAL -> snapshot.flowSources.forEach { (id, source) -> visual(id, source, ResourceUsageSource.SAVED_FLOW) }
        ProjectSourceMode.LUA -> snapshot.luaSources.forEach { (file, source) ->
            budget(source)
            source.lineSequence().forEachIndexed { index, line ->
                checkpoint()
                if (line.contains(path)) record(ProjectResourceUsage(ResourceUsageSource.LUA, file, index + 1))
            }
        }
    }
    drafts.forEach { (id, source) ->
        if (source != snapshot.flowSources[id]) visual(id, source, ResourceUsageSource.DRAFT_FLOW)
    }
    snapshot.manifest.runnerUi?.let { json ->
        val ui = try { ScriptUiDefinition.parse(json.toString()) }
        catch (_: Exception) { throw ProjectStoreException("脚本界面配置无法完整检查，原图片保留，请修复后重试") }
        ui.fields.filter { it.control == UiControl.IMAGE && it.initialValue == path }.forEach { field ->
            val page = ui.pages.single { it.id == (field.ui?.pageId ?: "main") }
            record(ProjectResourceUsage(ResourceUsageSource.INTERFACE, "${page.title} / ${field.label}（${field.id}）", parameter = "initialValue"))
        }
    }
    recipes.filter { it.tail?.references(path) == true }.forEach {
        record(ProjectResourceUsage(ResourceUsageSource.IMAGE_RECIPE, it.name, blockKind = it.tail?.kind))
    }
    checkpoint()
    return ProjectResourceUsageReport(path, results.toList(), count)
}
