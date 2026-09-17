package com.autoscript.project.store

import java.io.File
import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

const val CURRENT_PROJECT_FORMAT_VERSION = 2
const val LEGACY_PROJECT_FORMAT_VERSION = 1
const val MAX_LUA_SOURCE_BYTES = 16 * 1024 * 1024
const val MAX_FLOW_SOURCE_BYTES = 64 * 1024 * 1024
const val MAX_IMAGE_RESOURCE_BYTES = 32L * 1024 * 1024
const val MAX_GLYPH_DICTIONARY_BYTES = 8L * 1024 * 1024

enum class ProjectResourceKind {
    IMAGE,
    GLYPH_DICTIONARY,
}

enum class ProjectSourceMode {
    @SerializedName("lua")
    LUA,

    @SerializedName("visual")
    VISUAL,
}

data class ProjectDesign(
    val width: Int = 720,
    val height: Int = 1280,
    val scaleMode: String = "letterbox",
    val orientationPolicy: String = "follow",
)

data class ProjectFlow(
    val flowId: String,
    val path: String,
    val rootBlockId: String,
    val params: List<JsonObject> = emptyList(),
    val returns: JsonElement? = null,
)

data class ProjectManifestDocument(
    val formatVersion: Int = CURRENT_PROJECT_FORMAT_VERSION,
    val flowSchemaVersion: Int = 1,
    val runtimeApi: String = "1.5",
    val projectId: String,
    val name: String,
    val sourceMode: ProjectSourceMode,
    val entryPoint: String? = null,
    val entryFlowId: String? = null,
    val flows: List<ProjectFlow> = emptyList(),
    val resources: List<JsonObject> = emptyList(),
    val capabilities: List<String> = listOf("core.task"),
    val design: ProjectDesign = ProjectDesign(),
    val runnerUi: JsonObject? = null,
    val ownerId: String? = null,
    val cloudId: String? = null,
    val syncState: String? = null,
    val signature: JsonElement? = null,
    val licensePolicy: JsonElement? = null,
)

data class ProjectSummary(
    val projectId: String,
    val name: String,
    val sourceMode: ProjectSourceMode,
    val designWidth: Int,
    val designHeight: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long?,
)

data class ProjectSnapshot(
    val directory: File,
    val manifest: ProjectManifestDocument,
    val luaSource: String?,
    val flowSources: Map<String, String>,
)

/** 本地备份槽位（参考新版每个项目 3 个槽位）；`createdAt == null` 表示空槽。 */
data class BackupSlot(
    val index: Int,
    val createdAt: Long?,
    val bytes: Long?,
    val remark: String?,
    val projectName: String?,
) {
    val occupied: Boolean get() = createdAt != null
}

/** 备份管理页的一行：一个有槽位备份的项目，本地项目可能已被删除。 */
data class BackupProjectSummary(
    val projectId: String,
    val projectName: String,
    val slots: List<BackupSlot>,
    val localProjectExists: Boolean,
    val latestBackupAt: Long?,
    val totalBytes: Long,
)

/**
 * 源文件管理里的虚拟分组。
 *
 * 参考新版易编精灵：分组只是编辑器视图（它存 `分组配置.json`），Flow 文件仍在同一目录，
 * 所以分组不进入 `project.json`，也不参与备份；一个 Flow 最多属于一个分组。
 */
data class SourceGroup(
    val name: String,
    val flowIds: List<String> = emptyList(),
)

class ProjectStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ProjectWriteConflictException : IllegalStateException("main.lua 已被其他写入修改，请重新打开项目")

class FlowWriteConflictException(flowId: String) :
    IllegalStateException("Flow $flowId 已被其他写入修改，请重新打开项目")

class ProjectManifestConflictException :
    IllegalStateException("项目清单已被其他写入修改，请重新打开项目")
